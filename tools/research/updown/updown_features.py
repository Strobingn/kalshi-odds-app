"""The up/down model's inputs, in the order the ONNX model takes them. Stdlib only.

The app's port is app/src/main/java/com/dirk/kalshiodds/signal/updown/UpDownFeatures.kt; UpDownFeaturesTest checks
it against rows written by this file (fixtures/updown_parity.json). Change both together.

Inputs, as the app holds them:
  spot_hist  Coinbase BTC-USD, one slot a second ending at "now" (NaN = no print that second)
  mid_hist   the YES mid ((bid + ask) / 2) at the end of each second of the window so far, NaN when unknown
  strike, tte_s (seconds to close), sec_in (seconds into the window), the YES top of book.
"""
from __future__ import annotations

import math

STD_WINDOW_S = 900      # CfIndexBook.STD_WINDOW_S
MIN_RETURNS = 120       # CfIndexBook.MIN_RETURNS
FRESH_S = 5             # CfIndexBook.MAX_AGE_MS
SECONDS_PER_YEAR = 365.25 * 24 * 3600
SETTLE_W = 60.0
BTC_NOISE = 0.5e-4      # DigitalOptionFairValue.indexNoiseLog("BTC")
ENTRY_FROM_S = 20       # PaperAi.ENTRY_FROM_S
ENTRY_UNTIL_S = 880     # PaperAi.ENTRY_UNTIL_S

NAMES = [
    "tleft", "mid", "logit_mid", "spread", "imb", "fair", "logit_fair", "edge_logit", "z", "vol_bp",
    "r30", "r60", "r180", "r300", "dmid30", "dmid60", "dmid180",
]


def _isnan(v) -> bool:
    return v is None or (isinstance(v, float) and math.isnan(v))


def logit(p: float, lo: float = 0.005) -> float:
    p = min(max(p, lo), 1.0 - lo)
    return math.log(p / (1.0 - p))


def norm_cdf(x: float) -> float:
    # Abramowitz & Stegun 7.1.26, as DigitalOptionFairValue.erf
    s = 1.0 if x >= 0 else -1.0
    ax = abs(x) / math.sqrt(2.0)
    t = 1.0 / (1.0 + 0.3275911 * ax)
    yv = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * math.exp(-ax * ax)
    return 0.5 * (1.0 + s * yv)


def value_ago(hist, back: int):
    """Last value at or before `back` seconds ago (hist[-1] is now), at most FRESH_S older; None when there is none."""
    n = len(hist)
    j = n - 1 - back
    for k in range(j, max(j - FRESH_S, -1), -1):
        if k < 0:
            break
        v = hist[k]
        if not _isnan(v):
            return v
    return None


def std1m(hist):
    """RMS of consecutive 1-second log returns over the last STD_WINDOW_S seconds, scaled to a minute."""
    n = len(hist)
    lo = max(0, n - 1 - STD_WINDOW_S)
    ss, cnt = 0.0, 0
    for k in range(lo + 1, n):
        a, b = hist[k - 1], hist[k]
        if _isnan(a) or _isnan(b) or a <= 0 or b <= 0:
            continue
        r = math.log(b / a)
        ss += r * r
        cnt += 1
    if cnt < MIN_RETURNS:
        return None
    return math.sqrt(ss / cnt * 60.0)


def fair_yes(spot: float, strike: float, tte_s: float, sd1m: float, observed_mean_log):
    """ScalpFair.fairYes -> DigitalOptionFairValue.pSettleAtLeast with the Coinbase noise."""
    if tte_s <= 0:
        return None
    sig = sd1m * math.sqrt(SECONDS_PER_YEAR / 60.0)
    ln_s, ln_k = math.log(spot), math.log(strike)
    obs = observed_mean_log if observed_mean_log is not None else ln_s
    s2 = sig * sig / SECONDS_PER_YEAR
    w, t = SETTLE_W, tte_s
    if t >= w:
        mean, var = ln_s, s2 * ((t - w) + w / 3.0)
    else:
        f = t / w
        mean, var = ((w - t) * obs + t * ln_s) / w, f * f * s2 * t / 3.0
    sd = math.sqrt(var + BTC_NOISE * BTC_NOISE)
    if sd <= 1e-15:
        return 1.0 if mean >= ln_k else 0.0
    return min(max(norm_cdf((mean - ln_k) / sd), 0.0), 1.0)


def features(spot_hist, strike, tte_s, sec_in, yes_bid, yes_bid_qty, yes_ask, yes_ask_qty, mid_hist):
    """The NAMES row, or None when the app would not have it (no fresh spot, vol not warmed up, no two-sided book)."""
    if _isnan(yes_bid) or _isnan(yes_ask) or yes_ask <= yes_bid - 1e-9:
        return None
    spot = value_ago(spot_hist, 0)
    sd = std1m(spot_hist)
    if spot is None or sd is None or sd <= 0 or not strike or strike <= 0:
        return None
    observed = None
    if tte_s < SETTLE_W:
        k_from = len(spot_hist) - 1 - int(round(SETTLE_W - tte_s))
        xs = [math.log(v) for v in spot_hist[max(k_from, 0):] if not _isnan(v) and v > 0]
        observed = sum(xs) / len(xs) if xs else None
    fair = fair_yes(spot, strike, tte_s, sd, observed)
    if fair is None:
        return None
    mid = (yes_bid + yes_ask) / 2.0
    bq = 0.0 if _isnan(yes_bid_qty) else yes_bid_qty
    aq = 0.0 if _isnan(yes_ask_qty) else yes_ask_qty
    imb = (bq - aq) / (bq + aq) if bq + aq > 0 else 0.0
    z = math.log(spot / strike) / (sd * math.sqrt(max(tte_s, 1.0) / 60.0))
    rets = []
    for h in (30, 60, 180, 300):
        then = value_ago(spot_hist, h)
        rets.append(math.log(spot / then) / (sd * math.sqrt(h / 60.0)) if then else 0.0)
    dm = []
    for h in (30, 60, 180):
        then = value_ago(mid_hist, h) if len(mid_hist) > h else None
        dm.append(mid - then if then is not None else 0.0)
    lm, lf = logit(mid, 0.01), logit(fair, 0.005)
    row = [
        tte_s / 900.0, mid, lm, yes_ask - yes_bid, imb, fair, lf, lf - lm, max(min(z, 8.0), -8.0), sd * 1e4,
        *[max(min(r, 6.0), -6.0) for r in rets], *dm,
    ]
    return row
