"""Faithful port of the reconstructable DipHunter decision path.

Calls the same formulas as:
  FeatureVector, FallbackWeights, DipHunterModel.predict,
  DigitalOptionFairValue (incl. sigmaFromCloses), SpotFeatureMath,
  DirectionSanity, TapeConflict, ScoringEngine (light blend: heavy ML /
  extended AI default OFF; AI and related-crypto weights 0), EdgeFeatures /
  EdgeModel (schema-2 market offset), TickBook flow/momentum/velocity,
  NetExpectedValue.bestSide (side by net EV at the ask), KalshiFee,
  TicketBuilder.resolveSide / modelBeatsImplied, SkipFilter.

`DecisionEngine(lock_mode="legacy")` keeps the 0.3.10 side picker (tape hero
first, |edge| alert gate, tanh lock) for before/after comparisons.

Unavailable historically (set neutral / drop out of the blend):
  order-book imbalance, depth near/far, quote-pull / cancel spikes,
  WebSocket taker/aggressor flags, sub-second BTC lead-lag,
  Heavy ML sequence channels (imbalance/aggressor bins), NewsPulse,
  RivalFlow, MM shadow, conformal, meta-label, path sim,
  OnlineAdapter / Calibrator (cold-start identity).
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from decimal import Decimal, ROUND_CEILING

from weights import forward, load_fallback_weights

MIN_TICK = 0.001
MAX_TICK = 0.999
STAKE_USD = 5.0
FEE_RATE = 0.07
CONTRACT = 1.0
EDGE_ALERT_PP = 5.0
MIN_CONFIDENCE = 0.45
MAX_SPREAD_CENTS = 8.0
MIN_LIQUIDITY = 500.0
LATE_TTE_MS = 180_000
SECONDS_PER_YEAR = 365.25 * 24.0 * 3600.0
WINDOW_SECONDS = 900.0
MIN_RELATIVE_GAP = 0.0002
MIN_ABS_GAP_USD = 0.50
VELOCITY_LOOKBACK = 16
DIRK_MIN_PROFIT = 10.0

# ScoringEngine.W_*. AI (8-feature MLP) and related-crypto are 0: the MLP was
# trained on BTC+WTI with mismatched inputs and scored worst of every forecast;
# "related" blended another coin's YES mid (different strike) in as this one's.
W = {
    "early": dict(ai=0.0, flow=0.12, related=0.0, velocity=0.10, imbalance=0.10, leadLag=0.10, depth=0.10, cancel=0.10, spot=0.08),
    "late": dict(ai=0.0, flow=0.14, related=0.0, velocity=0.14, imbalance=0.14, leadLag=0.06, depth=0.14, cancel=0.14, spot=0.06),
}


def usable(p: float | None) -> float | None:
    if p is None or not math.isfinite(p):
        return None
    if p < MIN_TICK - 1e-12 or p > MAX_TICK + 1e-12:
        return None
    return float(p)


def clip_price(p: float) -> float:
    return min(MAX_TICK, max(MIN_TICK, p))


def _bd(x: float) -> Decimal:
    return Decimal(str(x))


def kalshi_total_cost(contracts: int, price: float, fee_rate: float = FEE_RATE) -> float:
    """KalshiFee.totalCost — ceil_6dp model fee, then ceil_cent(C*P + fee)."""
    n = max(int(contracts), 0)
    if n <= 0:
        return 0.0
    p = _bd(clip_price(price)).quantize(Decimal("0.0001"))
    r = _bd(min(0.25, max(0.0, fee_rate)))
    model = (r * Decimal(n) * p * (Decimal(1) - p)).copy_abs()
    trade = model.quantize(Decimal("0.000001"), rounding=ROUND_CEILING)
    position = p * Decimal(n)
    debit = (position + trade).quantize(Decimal("0.01"), rounding=ROUND_CEILING)
    return float(debit)


def kalshi_fee_total(contracts: int, price: float, fee_rate: float = FEE_RATE) -> float:
    n = max(int(contracts), 0)
    if n <= 0:
        return 0.0
    return max(0.0, kalshi_total_cost(n, price, fee_rate) - clip_price(price) * n)


def size_all_in(price: float, stake: float = STAKE_USD, fee_rate: float = FEE_RATE) -> tuple[int, float, float]:
    """Max C with C*P + fee <= stake. Returns (C, cost, fee)."""
    p = usable(price)
    if p is None:
        return 0, 0.0, 0.0
    # upper bound without fee, then walk down
    c = int(math.floor((stake / p) + 1e-9))
    while c > 0 and kalshi_total_cost(c, p, fee_rate) > stake + 1e-9:
        c -= 1
    if c <= 0:
        return 0, 0.0, 0.0
    cost = kalshi_total_cost(c, p, fee_rate)
    return c, cost, cost - c * clip_price(p)


def net_profit_if_win(contracts: int, price: float, fee_rate: float = FEE_RATE) -> float:
    if contracts <= 0:
        return 0.0
    return contracts * CONTRACT - kalshi_total_cost(contracts, price, fee_rate)


def dirk_ok(price: float, stake: float = STAKE_USD, min_profit: float = DIRK_MIN_PROFIT) -> bool:
    c, cost, _ = size_all_in(price, stake)
    if c <= 0:
        return False
    return (c * CONTRACT - cost) + 1e-9 >= min_profit


# --- FeatureVector / MLP -----------------------------------------------------

def series_id(ticker: str) -> float:
    return 1.0 if "WTI" in ticker.upper() else 0.0


def build_features(
    mid: float,
    volume: float,
    close_epoch_ms: int | None,
    now_ms: int,
    mids: list[float],
    ticker: str,
    open_interest: float = 0.0,
) -> list[float]:
    secs = 900.0 if close_epoch_ms is None else max(0.0, (close_epoch_ms - now_ms) / 1000.0)
    tte_frac = min(1.0, max(0.0, secs / 900.0))
    volume_norm = math.log1p(max(0.0, volume)) / math.log1p(1_000_000.0)
    oi_norm = math.log1p(max(0.0, open_interest)) / math.log1p(1_000_000.0)
    recent = mids[-8:]
    if len(recent) >= 3:
        mean = sum(recent) / len(recent)
        vol = math.sqrt(sum((x - mean) ** 2 for x in recent) / len(recent))
        volatility = min(1.0, max(0.0, vol))
    else:
        volatility = 0.05
    momentum = (recent[-1] - recent[0]) if len(recent) >= 2 else 0.0
    momentum = min(1.0, max(-1.0, momentum))
    mean_rev = 0.5 - mid
    return [
        min(1.0, max(0.0, mid)),
        volume_norm,
        tte_frac,
        volatility,
        momentum,
        mean_rev,
        series_id(ticker),
        oi_norm,
    ]


def standardize(raw: list[float], mean: list[float], std: list[float]) -> list[float]:
    out = []
    for i, x in enumerate(raw):
        s = 1.0 if std[i] < 1e-6 else std[i]
        out.append((x - mean[i]) / s)
    return out


class FeatureHistory:
    def __init__(self, max_points: int = 48):
        self.max_points = max_points
        self.by: dict[str, list[tuple[float, float, int]]] = {}

    def push(self, ticker: str, mid: float, volume: float, now_ms: int) -> None:
        q = self.by.setdefault(ticker, [])
        if q and now_ms - q[-1][2] < 200 and abs(q[-1][0] - mid) < 1e-6:
            return
        q.append((mid, volume, now_ms))
        if len(q) > self.max_points:
            del q[0]

    def mids(self, ticker: str) -> list[float]:
        return [p[0] for p in self.by.get(ticker, [])]


@dataclass
class MlpPrediction:
    yes: float
    no: float
    confidence: float


class DipHunterMlp:
    def __init__(self):
        self.w = load_fallback_weights()
        self.hist = FeatureHistory()

    def predict(
        self,
        ticker: str,
        market_mid: float,
        volume: float,
        close_epoch_ms: int | None,
        now_ms: int,
        open_interest: float = 0.0,
    ) -> MlpPrediction:
        self.hist.push(ticker, market_mid, volume, now_ms)
        series = self.hist.mids(ticker)
        raw = build_features(market_mid, volume, close_epoch_ms, now_ms, series, ticker, open_interest)
        scaled = standardize(raw, self.w["mean"], self.w["std"])
        probs = forward(scaled, self.w)
        p_no = min(0.98, max(0.02, probs[0]))
        p_yes = min(0.98, max(0.02, probs[1]))
        s = p_no + p_yes
        if s > 1e-9:
            p_no /= s
            p_yes /= s
        # tfliteReady=false → 0.6 term (embedded fallback), same as DipHunterModel
        conf = (
            0.40
            + 0.25 * min(len(series) / 12.0, 1.0)
            + 0.20 * (1.0 - abs(p_yes - 0.5) * 0.5)
            + 0.15 * 0.6
        )
        conf = min(0.95, max(0.15, conf))
        return MlpPrediction(yes=p_yes, no=p_no, confidence=conf)


# --- Digital fair / spot -----------------------------------------------------

def erf(x: float) -> float:
    sign = -1.0 if x < 0 else 1.0
    ax = abs(x)
    t = 1.0 / (1.0 + 0.3275911 * ax)
    y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * math.exp(-ax * ax)
    return sign * y


def norm_cdf(x: float) -> float:
    if not math.isfinite(x):
        return 1.0 if x > 0 else 0.0
    return 0.5 * (1.0 + erf(x / math.sqrt(2.0)))


def p_finish_above(spot: float, strike: float, tte_seconds: float, sigma_annual: float) -> float | None:
    if not (math.isfinite(spot) and math.isfinite(strike) and spot > 0 and strike > 0):
        return None
    if not math.isfinite(tte_seconds) or tte_seconds <= 0:
        return 1.0 if spot > strike else 0.0
    if not math.isfinite(sigma_annual) or sigma_annual <= 1e-8:
        return None
    t_years = min(1.0, max(1e-10, tte_seconds / SECONDS_PER_YEAR))
    vol_sqrt_t = sigma_annual * math.sqrt(t_years)
    if vol_sqrt_t <= 1e-12:
        return 1.0 if spot > strike else 0.0
    d2 = (math.log(spot / strike) - 0.5 * sigma_annual * sigma_annual * t_years) / vol_sqrt_t
    return min(1.0, max(0.0, norm_cdf(d2)))


def distance_vol_units(spot: float, strike: float, tte_seconds: float, sigma_annual: float) -> float | None:
    if not (math.isfinite(spot) and math.isfinite(strike) and spot > 0 and strike > 0):
        return None
    if not math.isfinite(sigma_annual) or sigma_annual <= 1e-8:
        return None
    t_years = min(1.0, max(1e-10, max(tte_seconds, 1.0) / SECONDS_PER_YEAR))
    vol_sqrt_t = sigma_annual * math.sqrt(t_years)
    if vol_sqrt_t <= 1e-12:
        return None
    return math.log(spot / strike) / vol_sqrt_t


def realized_vol_bar_std(closes: list[float]) -> float | None:
    """1-minute log-return sample std (AssetSpotFeatures.realizedVol15m)."""
    if len(closes) < 5:
        return None
    rets = []
    prev = closes[0]
    for c in closes[1:]:
        if prev > 0 and c > 0:
            rets.append(math.log(c / prev))
        prev = c
    if len(rets) < 4:
        return None
    mean = sum(rets) / len(rets)
    var = sum((r - mean) ** 2 for r in rets) / (len(rets) - 1)
    std = math.sqrt(max(0.0, var))
    return std if std > 0 else None


def sigma_annual_from_bar_std(bar_std: float) -> float:
    return min(5.0, max(0.01, bar_std * math.sqrt(SECONDS_PER_YEAR / 60.0)))


# σ for the digital (DigitalOptionFairValue.sigmaFromCloses): EWMA of squared
# 1m log returns, half-life SIGMA_HALFLIFE_BARS, over the last SIGMA_BARS returns.
# Backtest 2026-09-27: beat the old 16-bar sample std on IS and OOS log-loss;
# scaling σ up (×1.25) was worse, so no inflation.
SIGMA_BARS = 60
SIGMA_HALFLIFE_BARS = 10.0


def sigma_annual_from_closes(closes: list[float]) -> float | None:
    rets = []
    prev = None
    for c in closes[-(SIGMA_BARS + 1):]:
        if prev is not None and prev > 0 and c > 0:
            rets.append(math.log(c / prev))
        prev = c
    if len(rets) < 4:
        return None
    lam = 0.5 ** (1.0 / SIGMA_HALFLIFE_BARS)
    var = rets[0] * rets[0]
    for r in rets[1:]:
        var = lam * var + (1.0 - lam) * r * r
    if var <= 0.0:
        return None
    return sigma_annual_from_bar_std(math.sqrt(var))


# --- Edge model schema 2 (market offset) --------------------------------------
# Android twin: prediction/EdgeFeatures.kt. Order is the JSON contract.

EDGE_SCHEMA = 2
EDGE_FEATURES = [
    "digital_gap",
    "dist_to_strike_vol",
    "spot_ret_1m",
    "spot_ret_5m",
    "tte_frac",
    "spread",
    "tod_sin",
    "tod_cos",
    "has_spot",
]
EDGE_P_MIN = 0.005
EDGE_P_MAX = 0.995


def logit(p: float) -> float:
    q = min(EDGE_P_MAX, max(EDGE_P_MIN, p))
    return math.log(q / (1.0 - q))


FAIR_MIN_PP = 0.5
FAIR_MAX_PP = 99.5


def tail_scaled_fair_pp(raw_fair_pp: float, mid: float) -> float:
    """ScoringEngine.tailScaledFairPp: treat the blend's pp nudges as log-odds nudges.

    Each channel adds up to ~8pp at any price. Scaling the net nudge by
    4·m·(1−m) (dp ≈ p(1−p)·dlogit) keeps it at full size at 50¢ and shrinks it
    to ~2% of that at 1¢, instead of inventing a 13% win chance at a 5¢ ask.
    """
    m = min(0.995, max(0.005, mid))
    fair = mid * 100.0 + (raw_fair_pp - mid * 100.0) * 4.0 * m * (1.0 - m)
    return min(FAIR_MAX_PP, max(FAIR_MIN_PP, fair))


def edge_features(
    mid: float,
    spread: float | None,
    spot: float | None,
    strike: float | None,
    tte_seconds: float,
    sigma_annual: float | None,
    ret_1m: float | None,
    ret_5m: float | None,
    now_ms: int,
) -> list[float]:
    """Raw (unstandardized) schema-2 features. Missing spot → zeros + has_spot 0."""
    tte = min(1800.0, max(0.0, tte_seconds))
    digital = None
    dist = None
    if spot is not None and strike is not None and sigma_annual is not None:
        digital = p_finish_above(spot, strike, tte, sigma_annual)
        dist = distance_vol_units(spot, strike, tte, sigma_annual)
    gap = 0.0
    if digital is not None:
        gap = min(4.0, max(-4.0, logit(digital) - logit(mid)))
    sec_of_day = (now_ms // 1000) % 86_400
    ang = 2.0 * math.pi * sec_of_day / 86_400.0
    return [
        gap,
        min(8.0, max(-8.0, dist)) if dist is not None else 0.0,
        min(0.02, max(-0.02, ret_1m)) if ret_1m is not None else 0.0,
        min(0.05, max(-0.05, ret_5m)) if ret_5m is not None else 0.0,
        min(1.0, max(0.0, tte / 900.0)),
        min(0.2, max(0.0, spread)) if spread is not None else 0.0,
        math.sin(ang),
        math.cos(ang),
        1.0 if digital is not None else 0.0,
    ]


def edge_predict(model: dict, raw: list[float], mid: float) -> float:
    """sigmoid(logit(mid) + b + Σ w·z). All-zero weights and bias return the mid."""
    z = logit(mid) + float(model["bias"])
    for w, x, m, s in zip(model["weights"], raw, model["mean"], model["std"]):
        z += w * ((x - m) / (s if s > 1e-9 else 1.0))
    z = max(-30.0, min(30.0, z))
    return min(EDGE_P_MAX, max(EDGE_P_MIN, 1.0 / (1.0 + math.exp(-z))))


def spot_adjust_pp(mid_pp: float, ret: float | None, rvol15: float | None, funding: float | None = None) -> float | None:
    if ret is None and funding is None and rvol15 is None:
        return None
    ret_term = 6.0 * math.tanh(ret / 0.003) if ret is not None else 0.0
    fund_term = 2.0 * math.tanh(funding / 0.0004) if funding is not None else 0.0
    vol_term = -1.2 * math.tanh(rvol15 / 0.008) if rvol15 is not None else 0.0
    return min(98.0, max(2.0, mid_pp + ret_term + fund_term + vol_term))


# --- Direction / tape --------------------------------------------------------

def gap_usd(strike: float) -> float:
    return max(MIN_ABS_GAP_USD, abs(strike) * MIN_RELATIVE_GAP)


def directional_fair_pp(signed_usd: float, strike: float) -> float:
    scale = max(abs(strike) * 0.002, 1.0)
    p = 0.50 + 0.48 * math.tanh(signed_usd / scale)
    return min(98.0, max(2.0, p * 100.0))


def direction_sanity(spot: float | None, strike: float | None, spot_return: float | None, fair_pp: float, predicted_side: str, digital_pp: float | None = None, mode: str = "digital") -> tuple[str, float, bool]:
    """DirectionSanity.apply. mode: "digital" (shipped), "legacy" (0.3.10 tanh + max), "off"."""
    if mode == "legacy":
        return _direction_sanity_legacy(spot, strike, spot_return, fair_pp, predicted_side)
    if mode == "off":
        return predicted_side, fair_pp, False
    if spot is None or strike is None or spot <= 0 or strike <= 0:
        return predicted_side, fair_pp, False
    raw = spot - strike
    gap = gap_usd(strike)
    if abs(raw) < gap:
        return predicted_side, fair_pp, False
    rising = spot_return is not None and spot_return > 0
    falling = spot_return is not None and spot_return < 0
    want_yes = raw > 0
    confirmed = False
    if want_yes and (rising or spot_return is None or abs(raw) >= 4.0 * gap):
        confirmed = True
    if (not want_yes) and (falling or spot_return is None or abs(raw) >= 4.0 * gap):
        confirmed = True
    if not confirmed:
        return predicted_side, fair_pp, False
    dir_pp = digital_pp if digital_pp is not None else directional_fair_pp(raw, strike)
    blend = min(FAIR_MAX_PP, max(FAIR_MIN_PP, fair_pp))
    if mode == "replace":
        fair = dir_pp
    elif mode == "max":
        fair = max(blend, dir_pp) if want_yes else min(blend, dir_pp)
    else:  # "digital": halfway between the blend and the time/vol-aware digital
        fair = 0.5 * blend + 0.5 * dir_pp
    side = "YES" if fair >= 50.0 else "NO"
    return side, min(FAIR_MAX_PP, max(FAIR_MIN_PP, fair)), True


def _direction_sanity_legacy(spot: float | None, strike: float | None, spot_return: float | None, fair_pp: float, predicted_side: str) -> tuple[str, float, bool]:
    if spot is None or strike is None or spot <= 0 or strike <= 0:
        return predicted_side, fair_pp, False
    raw = spot - strike
    gap = gap_usd(strike)
    if abs(raw) < gap:
        return predicted_side, fair_pp, False
    rising = spot_return is not None and spot_return > 0
    falling = spot_return is not None and spot_return < 0
    want_yes = raw > 0
    confirmed = False
    if want_yes and (rising or spot_return is None or abs(raw) >= 4.0 * gap):
        confirmed = True
    if (not want_yes) and (falling or spot_return is None or abs(raw) >= 4.0 * gap):
        confirmed = True
    if not confirmed:
        return predicted_side, fair_pp, False
    side = "YES" if want_yes else "NO"
    dir_pp = directional_fair_pp(raw, strike)
    fair = min(98.0, max(2.0, fair_pp))
    if want_yes:
        fair = max(fair, max(dir_pp, 52.0))
        if fair < 50.5:
            fair = 52.0
    else:
        fair = min(fair, min(dir_pp, 48.0))
        if fair > 49.5:
            fair = 48.0
    return side, min(98.0, max(2.0, fair)), True


def tape_primary(yes_ask: float | None, no_ask: float | None, spot: float | None, strike: float | None, fair_yes: float | None, previous: str | None, yes_bid: float | None = None, no_bid: float | None = None) -> str:
    from_spot = None
    if spot is not None and strike is not None and spot > 0 and strike > 0:
        g = spot - strike
        rel = abs(g) / strike
        if not (rel < MIN_RELATIVE_GAP and abs(g) < MIN_ABS_GAP_USD):
            if g > 0:
                from_spot = "YES"
            elif g < 0:
                from_spot = "NO"
    mid = None
    if yes_bid is not None and yes_ask is not None:
        mid = 0.5 * (yes_bid + yes_ask)
    elif no_ask is not None:
        mid = 1.0 - no_ask
    from_mkt = None
    if mid is not None:
        if mid > 0.52:
            from_mkt = "YES"
        elif mid < 0.48:
            from_mkt = "NO"
    from_fair = None
    if fair_yes is not None:
        if fair_yes > 0.52:
            from_fair = "YES"
        elif fair_yes < 0.48:
            from_fair = "NO"
    decided = from_spot or from_mkt or from_fair
    prev = previous if previous in ("YES", "NO") else None
    if decided is None:
        return prev or "YES"
    if prev is None or prev == decided:
        return decided
    if from_spot is not None:
        return from_spot
    if from_mkt is not None and from_fair is not None and from_mkt == from_fair:
        return from_mkt
    return prev


def resolve_side(primary: str | None, predicted: str | None, net_edge_pp: float | None) -> str | None:
    """TicketBuilder.resolveSide: the EV-at-ask side first, the tape hero only as a fallback."""
    if predicted in ("YES", "NO"):
        return predicted
    if primary in ("YES", "NO"):
        return primary
    if net_edge_pp is None:
        return None
    if net_edge_pp > 0:
        return "YES"
    if net_edge_pp < 0:
        return "NO"
    return None


def model_beats_implied(model: float | None, implied: float | None, fee_rate: float = FEE_RATE, margin: float = 0.03, stake: float = STAKE_USD) -> bool:
    if model is None or implied is None:
        return False
    p = clip_price(implied)
    c = max(1, int(math.floor(stake / p + 1e-9)))
    fee = kalshi_fee_total(c, p, fee_rate) / c
    return model > p + fee + margin


# --- Regime / blend ----------------------------------------------------------

def tte_regime(close_ms: int | None, now_ms: int) -> str:
    if close_ms is None:
        return "EARLY"
    return "LATE" if close_ms - now_ms <= LATE_TTE_MS else "EARLY"


def classify_regime(mids: list[float], times_ms: list[int], vel: float | None) -> str:
    if len(mids) < 3:
        return "QUIET"
    rets = [(b - a) * 100.0 for a, b in zip(mids, mids[1:])]
    if not rets:
        return "QUIET"
    mean = sum(rets) / len(rets)
    var = sum((v - mean) ** 2 for v in rets) / len(rets)
    vol = math.sqrt(max(0.0, var))
    net = (mids[-1] - mids[0]) * 100.0
    if vel is not None:
        vel_pp = vel * 100.0
    elif len(times_ms) == len(mids) and len(times_ms) >= 2:
        dt = (times_ms[-1] - times_ms[0]) / 1000.0
        vel_pp = ((mids[-1] - mids[0]) / dt) * 100.0 if dt > 1e-6 else 0.0
    else:
        vel_pp = 0.0
    same = net * vel_pp >= -1e-6
    if vol >= 2.2 or abs(vel_pp) >= 2.0:
        return "VOL_SPIKE"
    if abs(net) >= 2.5 and abs(vel_pp) >= 0.25 and same:
        return "TREND"
    if vol >= 0.9 and abs(net) < 2.0:
        return "CHOP"
    if vol < 0.35 and abs(vel_pp) < 0.20 and abs(net) < 1.2:
        return "QUIET"
    if abs(net) >= 2.0 and same:
        return "TREND"
    return "CHOP"


def blend_weights(tte: str, regime: str, has: dict[str, bool]) -> dict[str, float] | None:
    late = tte == "LATE"
    base = W["late" if late else "early"]
    w = {k: (base[k] if has.get(k, k == "flow") else 0.0) for k in base}
    # flow is always on in ScoringEngine.blendWeights
    w["flow"] = base["flow"]
    if regime == "TREND":
        w["velocity"] *= 1.25
        w["leadLag"] *= 1.20
    elif regime == "CHOP":
        w["velocity"] *= 0.55
        w["ai"] *= 1.15
    elif regime == "VOL_SPIKE":
        w["cancel"] *= 1.25
        w["depth"] *= 1.20
        w["ai"] *= 0.85
    elif regime == "QUIET":
        w["flow"] *= 0.85
        w["related"] *= 1.10
    s = sum(w.values())
    if s < 1e-9:
        return None
    return {k: v / s for k, v in w.items()}


def flow_score(mids: list[float], volumes: list[float]) -> float:
    if len(mids) < 2:
        return 0.0
    signed = 0.0
    weight = 0.0
    for i in range(1, len(mids)):
        d = mids[i] - mids[i - 1]
        size = max(0.0, volumes[i] - volumes[i - 1]) if i < len(volumes) and i - 1 < len(volumes) else 0.0
        side = 1.0 if d > 1e-6 else (-1.0 if d < -1e-6 else 0.0)
        ww = 1.0 + math.log(1.0 + max(0.0, size))
        signed += side * ww
        weight += ww
    if weight < 1e-9:
        return 0.0
    return min(1.0, max(-1.0, signed / weight))


def velocity_per_sec(mids: list[float], times: list[int]) -> float | None:
    if len(mids) < 2 or len(times) != len(mids):
        return None
    dt = times[-1] - times[0]
    if dt < 1:
        return None
    return (mids[-1] - mids[0]) / (dt / 1000.0)


def acceleration_per_sec(mids: list[float], times: list[int]) -> float | None:
    if len(mids) < 4 or len(times) != len(mids):
        return None
    mid = len(mids) // 2
    older = velocity_per_sec(mids[:mid], times[:mid])
    recent = velocity_per_sec(mids[mid:], times[mid:])
    if older is None or recent is None:
        return None
    return recent - older


def net_ev(fair_yes: float, mid: float, spread: float | None, prefer: str, stake: float = STAKE_USD) -> dict:
    p_yes = min(0.98, max(0.02, fair_yes))
    m = min(0.98, max(0.02, mid))
    half = max(0.0, spread or 0.0) / 2.0
    raw = (p_yes - m) * 100.0
    side = prefer if prefer in ("YES", "NO") else ("YES" if raw >= 0 else "NO")
    p_side = p_yes if side == "YES" else 1.0 - p_yes
    paid = (m if side == "YES" else 1.0 - m) + half
    paid = min(0.99, max(MIN_TICK, paid))
    c = max(1, int(math.floor(stake / paid + 1e-9)))
    fee = kalshi_fee_total(c, paid) / c
    net = p_side - paid - fee
    return dict(side=side, net_ev=net, net_edge_pp=net * 100.0, raw_edge_pp=raw, fee=fee, paid=paid, half=half)


def net_ev_at_ask(fair_yes: float, side: str, ask: float | None, mid: float, spread: float | None, stake: float = STAKE_USD) -> dict:
    """NetExpectedValue.atAsk: pay the real ask when there is one, else side-mid + half spread."""
    p_yes = min(0.995, max(0.005, fair_yes))
    m = min(0.98, max(0.02, mid))
    half = max(0.0, spread or 0.0) / 2.0
    p_side = p_yes if side == "YES" else 1.0 - p_yes
    paid = usable(ask) if ask is not None else None
    if paid is None:
        paid = (m if side == "YES" else 1.0 - m) + half
    paid = min(0.99, max(MIN_TICK, paid))
    c = max(1, int(math.floor(stake / paid + 1e-9)))
    fee = kalshi_fee_total(c, paid) / c
    net = p_side - paid - fee
    return dict(side=side, net_ev=net, net_edge_pp=net * 100.0, raw_edge_pp=(p_yes - m) * 100.0, fee=fee, paid=paid, half=half)


def best_side_ev(fair_yes: float, yes_ask: float | None, no_ask: float | None, mid: float, spread: float | None, stake: float = STAKE_USD) -> dict:
    """NetExpectedValue.bestSide: the side whose win chance clears its ask + fee by the most."""
    yes = net_ev_at_ask(fair_yes, "YES", yes_ask, mid, spread, stake)
    no = net_ev_at_ask(fair_yes, "NO", no_ask, mid, spread, stake)
    return yes if yes["net_ev"] >= no["net_ev"] else no


def pick_side(fair_yes: float, yes_ask: float | None, no_ask: float | None, mid: float, spread: float | None, stake: float = STAKE_USD) -> dict:
    """NetExpectedValue.pick: best EV side if positive, else the probability lean (negative EV)."""
    best = best_side_ev(fair_yes, yes_ask, no_ask, mid, spread, stake)
    if best["net_ev"] > 0.0:
        return best
    lean = "YES" if fair_yes >= 0.5 else "NO"
    return net_ev_at_ask(fair_yes, lean, yes_ask if lean == "YES" else no_ask, mid, spread, stake)


def skip_filter(confidence: float, spread: float | None, volume: float | None, oi: float | None) -> tuple[bool, str | None]:
    reasons = []
    if confidence < MIN_CONFIDENCE:
        reasons.append("low confidence")
    if spread is not None and spread * 100.0 > MAX_SPREAD_CENTS:
        reasons.append("wide spread")
    observed = max([x for x in (volume, oi) if x is not None], default=None)
    if observed is not None and observed < MIN_LIQUIDITY:
        reasons.append("thin liquidity")
    return (True, None) if not reasons else (False, " · ".join(reasons))


def should_show(passed: bool, edge_pp: float | None, net_edge_pp: float | None) -> bool:
    """ScoringEngine.maybeAlert: net EV at the ask must clear the threshold (never |raw edge|)."""
    if net_edge_pp is None or not passed:
        return False
    return net_edge_pp >= EDGE_ALERT_PP


@dataclass
class Decision:
    ticker: str
    series: str
    now_ms: int
    elapsed_min: int
    tte_sec: int
    tte_bucket: str
    coin: str
    result: str
    strike: float | None
    spot: float | None
    dist_bps: float | None
    yes_bid: float | None
    yes_ask: float | None
    no_ask: float | None
    mid: float
    spread: float | None
    fill_yes: float | None
    fill_no: float | None
    fill_yes_stress: float | None
    fill_no_stress: float | None
    mlp_yes: float
    digital_fair: float | None
    fair_yes: float
    delta_pp: float
    net_edge_pp: float
    net_ev: float
    predicted_side: str
    primary_side: str
    app_side: str
    fv_side: str
    confidence: float
    passed_filter: bool
    would_alert: bool
    model_beats_yes: bool
    model_beats_no: bool
    regime: str
    volume: float
    oi: float


def close_fill(side: str, yes_ask_close: float | None, yes_bid_close: float | None) -> float | None:
    """Primary taker fill: candle CLOSE ask.

    YES ask = yes_ask.close_dollars.
    NO / DOWN ask = 1 − yes_bid.close_dollars.
    Docs: https://docs.kalshi.com/api-reference/market/get-market-candlesticks
    """
    if side == "YES":
        return usable(yes_ask_close)
    if yes_bid_close is None:
        return None
    return usable(1.0 - yes_bid_close)


def conservative_fill(side: str, yes_ask_close: float | None, yes_ask_high: float | None, yes_bid_close: float | None, yes_bid_low: float | None) -> float | None:
    """Stress-test only: worse of close/high. Not the primary fill."""
    if side == "YES":
        cands = [usable(yes_ask_close), usable(yes_ask_high)]
        vals = [x for x in cands if x is not None]
        return max(vals) if vals else None
    close_no = usable(1.0 - yes_bid_close) if yes_bid_close is not None else None
    high_no = usable(1.0 - yes_bid_low) if yes_bid_low is not None else None
    vals = [x for x in (close_no, high_no) if x is not None]
    return max(vals) if vals else None


class DecisionEngine:
    """Per-market sequential scorer. Push one minute at a time (no look-ahead)."""

    def __init__(self, lock_mode: str = "digital", edge_model: dict | None = None, tail_scale: bool = True):
        self.mlp = DipHunterMlp()
        self.last_primary: dict[str, str] = {}
        self.lock_mode = lock_mode
        self.edge_model = edge_model
        self.tail_scale = tail_scale

    def score_minute(self, *, ticker: str, series: str, coin: str, result: str, strike: float | None, now_ms: int, close_ms: int, elapsed_min: int, yes_bid: float | None, yes_ask: float | None, yes_ask_high: float | None, yes_bid_low: float | None, mid: float, volume: float, oi: float, mids: list[float], times: list[int], volumes: list[float], spot: float | None, ret_1m: float | None, ret_5m: float | None, rvol15: float | None, related_mid: float | None = None, sigma_annual: float | None = None) -> Decision:
        spread = None
        if yes_bid is not None and yes_ask is not None:
            spread = max(0.0, yes_ask - yes_bid)
        no_ask = usable(1.0 - yes_bid) if yes_bid is not None else None
        fill_yes = close_fill("YES", yes_ask, yes_bid)
        fill_no = close_fill("NO", yes_ask, yes_bid)
        fill_yes_stress = conservative_fill("YES", yes_ask, yes_ask_high, yes_bid, yes_bid_low)
        fill_no_stress = conservative_fill("NO", yes_ask, yes_ask_high, yes_bid, yes_bid_low)

        mlp = self.mlp.predict(ticker, mid, volume, close_ms, now_ms, oi)
        mlp_pp = mlp.yes * 100.0
        mid_pp = mid * 100.0
        tte = tte_regime(close_ms, now_ms)
        tte_sec = max(0, int((close_ms - now_ms) / 1000))
        take = min(VELOCITY_LOOKBACK, len(mids))
        vm = mids[-take:]
        vt = times[-take:]
        vel = velocity_per_sec(vm, vt)
        acc = acceleration_per_sec(vm, vt) if take >= 4 else None
        regime = classify_regime(mids, times, vel)

        raw_flow = flow_score(mids, volumes)
        flow = min(1.0, max(-1.0, 0.65 * raw_flow + 0.35 * 0.0))
        mom_pp = ((mids[-1] - mids[0]) * 100.0) if len(mids) >= 2 else 0.0
        mom_pp = min(40.0, max(-40.0, mom_pp))
        flow_adj = min(98.0, max(2.0, mid_pp + 8.0 * math.tanh(flow) + 0.35 * mom_pp))

        vel_adj = None
        if vel is not None:
            vel_adj = min(98.0, max(2.0, mid_pp + 6.0 * math.tanh(vel * 100.0 / 2.0) + 2.0 * math.tanh(((acc or 0.0) * 100.0) / 2.0)))
        related_pp = None  # channel removed (see W)
        spot_adj = spot_adjust_pp(mid_pp, ret_5m if ret_5m is not None else ret_1m, rvol15)

        has = dict(
            ai=True,
            flow=True,
            related=False,
            velocity=vel_adj is not None,
            imbalance=False,
            leadLag=False,
            depth=False,
            cancel=False,
            spot=spot_adj is not None,
        )
        w = blend_weights(tte, regime, has) or {}
        raw_fair = (
            mlp_pp * w.get("ai", 0)
            + flow_adj * w.get("flow", 0)
            + (related_pp or 0) * w.get("related", 0)
            + (vel_adj or 0) * w.get("velocity", 0)
            + (spot_adj or 0) * w.get("spot", 0)
        )
        if self.tail_scale:
            fair = tail_scaled_fair_pp(raw_fair, mid)
        else:
            fair = min(98.0, max(2.0, raw_fair))
        predicted = "YES" if (fair - mid_pp) >= 0 else "NO"

        sigma = sigma_annual
        if sigma is None and rvol15 is not None:
            sigma = sigma_annual_from_bar_std(rvol15)
        digital = None
        if spot is not None and strike is not None and sigma is not None:
            p = p_finish_above(spot, strike, float(tte_sec or 900), sigma)
            digital = p * 100.0 if p is not None else None

        # ScoringEngine: spotRet = spotReturn1m ?: spotReturn5m
        predicted, fair, _ = direction_sanity(spot, strike, ret_1m if ret_1m is not None else ret_5m, fair, predicted, digital, self.lock_mode)
        if self.edge_model is not None:
            feats = edge_features(mid, spread, spot, strike, float(tte_sec), sigma, ret_1m, ret_5m, now_ms)
            fair = edge_predict(self.edge_model, feats, mid) * 100.0
        delta = fair - mid_pp

        if self.lock_mode == "legacy":
            predicted = "YES" if delta >= 0 else "NO"
            ev = net_ev(fair / 100.0, mid, spread, predicted)
        else:
            ev = pick_side(fair / 100.0, fill_yes, fill_no, mid, spread)
            predicted = ev["side"]
        n_ticks = len(mids)
        conf = mlp.confidence
        conf *= 0.75 + 0.25 * min(n_ticks / 12.0, 1.0)
        if spread is not None:
            conf *= 1.0 - min(0.40, max(0.0, spread / 0.20))
        conf *= {"VOL_SPIKE": 0.75, "CHOP": 0.85, "QUIET": 0.95, "TREND": 1.05}.get(regime, 1.0)
        if tte == "LATE":
            conf *= 0.90
        conf = min(0.95, max(0.10, conf))
        passed, _ = skip_filter(conf, spread, volume, oi)
        if self.lock_mode == "legacy":
            rank = ev["net_edge_pp"]
            would = passed and abs(rank) >= EDGE_ALERT_PP
        else:
            would = should_show(passed, delta, ev["net_edge_pp"])

        no_bid = usable(1.0 - yes_ask) if yes_ask is not None else None
        primary = tape_primary(yes_ask, no_ask, spot, strike, fair / 100.0, self.last_primary.get(ticker), yes_bid, no_bid)
        self.last_primary[ticker] = primary
        if self.lock_mode == "legacy":
            app_side = (primary if primary in ("YES", "NO") else predicted)
        else:
            app_side = resolve_side(primary, predicted, ev["net_edge_pp"]) or predicted

        # Fair-value-only side: digital if present else direction-from-spot else mid fade
        if digital is not None:
            fv_side = "YES" if digital >= 50.0 else "NO"
        elif spot is not None and strike is not None:
            fv_side = "YES" if spot > strike else "NO"
        else:
            fv_side = "YES" if mid >= 0.5 else "NO"

        dist_bps = None
        if spot is not None and strike is not None and strike > 0:
            dist_bps = abs(spot - strike) / strike * 10_000.0

        return Decision(
            ticker=ticker,
            series=series,
            now_ms=now_ms,
            elapsed_min=elapsed_min,
            tte_sec=tte_sec,
            tte_bucket=tte,
            coin=coin,
            result=result,
            strike=strike,
            spot=spot,
            dist_bps=dist_bps,
            yes_bid=yes_bid,
            yes_ask=yes_ask,
            no_ask=no_ask,
            mid=mid,
            spread=spread,
            fill_yes=fill_yes,
            fill_no=fill_no,
            fill_yes_stress=fill_yes_stress,
            fill_no_stress=fill_no_stress,
            mlp_yes=mlp.yes,
            digital_fair=digital,
            fair_yes=fair / 100.0,
            delta_pp=delta,
            net_edge_pp=ev["net_edge_pp"],
            net_ev=ev["net_ev"],
            predicted_side=predicted,
            primary_side=primary,
            app_side=app_side,
            fv_side=fv_side,
            confidence=conf,
            passed_filter=passed,
            would_alert=would,
            model_beats_yes=model_beats_implied(fair / 100.0, fill_yes),
            model_beats_no=model_beats_implied(1.0 - fair / 100.0, fill_no),
            regime=regime,
            volume=volume,
            oi=oi,
        )
