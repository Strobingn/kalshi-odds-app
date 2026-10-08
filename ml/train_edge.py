#!/usr/bin/env python3
"""
Offline edge trainer for DipHunter.

Default model: a **market-anchored offset logistic**
(docs/ml-review-2026-09-27.md, item 2):

    logit P(YES) = logit(clip(mid)) + b + Σ w_i · (x_i − mean_i) / std_i

`logit(mid)` is a fixed offset. It is not a learned or standardized feature.
With w = 0 and b = 0 the model *is* the Kalshi mid, so every nonzero weight
has to earn its place on held-out data. The fit is L2-regularized (toward 0,
i.e. toward the market) Newton / IRLS run to convergence. Stdlib only.

Data: every decision minute (elapsed minutes 1..13) of every settled
KXBTC15M / KXETH15M / KXSOL15M market in the `--days` window, paged through
the whole window. Spot comes from Coinbase 1m candles that had closed by the
decision candle (no look-ahead, see `spot_known_at`).

Validation: walk-forward, time-ordered, with fold boundaries on market close
times. A fold only trains on markets that settled before its first decision.
It reports Brier / log-loss of the model vs the market mid on the holdout,
and a simulated P&L that picks the side by expected value at the **ask**
(the same rule as the app's `EvSide`): one bet per market, first minute
where `max(ev_yes, ev_no) > margin`, filled at the candle-close ask.

Exports a compact JSON the Android app can import (Data → Import model, or
Data → Get latest model from the rolling release).

    python3 ml/train_edge.py
    python3 ml/train_edge.py --days 30 --out ml/edge_model.json
    python3 ml/train_edge.py --cache tools/backtest/cache   # reuse the backtest pull
    python3 ml/train_edge.py --fixture   # no network; synthetic walk-forward
"""
from __future__ import annotations

import argparse
import bisect
import json
import math
import random
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

REPO = Path(__file__).resolve().parents[1]
ML_DIR = REPO / "ml"
KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
COINBASE = "https://api.exchange.coinbase.com"
SERIES = ["KXBTC15M", "KXETH15M", "KXSOL15M"]
PRODUCT = {"KXBTC15M": "BTC-USD", "KXETH15M": "ETH-USD", "KXSOL15M": "SOL-USD"}
FEATURE_NAMES = [
    "dist_to_strike_vol",
    "tte_frac",
    "market_mid",
    "imbalance",
    "spread",
    "momentum",
    "realized_vol",
    "cross_asset",
    "time_of_day",
    "digital_fair",
    # Simple return over the full previous 15-minute window (open-900s to
    # open): the 15-minute sign-reversal tilt (arXiv 2608.21888).
    "prev_window_return",
    # 1.0 when the window CLOSES into a perpetual funding settlement
    # (00/08/16 UTC): documented different payoff geometry and weaker
    # reversal accuracy in those hours (arXiv 2608.21888 / 2607.09426).
    "is_funding_hour",
    # mid^2 interaction: the GWU favorite-longshot study (313,972 contracts)
    # found expensive contracts win MORE than price implies and cheap ones
    # less. A quadratic in mid lets the fit express that curvature instead
    # of a linear tilt only.
    "mid_squared",
]
MID_INDEX = FEATURE_NAMES.index("market_mid")
SECONDS_PER_YEAR = 365.25 * 24 * 3600
FEE_RATE = 0.07
CONF_MARGIN = 0.03
UA = "DipHunterTrainer/1.1"
# App: ExternalMarketFeatures.realizedVol(closes.takeLast(16)).
SPOT_LOOKBACK_BARS = 60
# EWMA decay for realized vol (matches the app's ExternalMarketFeatures).
EWMA_LAMBDA = 0.86
# Ignore spot bars older than this at a decision (data gaps must not feed
# hours-old spot into dist / digital fair).
SPOT_MAX_AGE_S = 60 * (SPOT_LOOKBACK_BARS + 5)
# App: EdgeFeatures.build coerces dist_to_strike_vol to [-8, 8].
DIST_CLIP = 8.0

KIND_OFFSET = "offset_logistic"
KIND_LOGISTIC = "logistic"
# logit(clip(mid, MID_CLIP, 1 − MID_CLIP)). Kalshi's usable tick range
# (KalshiPrice 0.1¢–99.9¢), so w = 0 reproduces every usable mid.
MID_CLIP = 0.001
# Columns the offset model may weight. Everything else gets weight 0.
# market_mid is NOT a column: logit(mid) is already the offset. A second
# linear mid term is what lets the fit walk off a price that is already
# the better forecast.
# prev_window_return tests the 15-minute sign reversal (arXiv 2608.21888);
# is_funding_hour lets calibration shift for windows closing into perp
# funding; mid_squared fits favorite-longshot curvature. mid_squared is
# monotone in the mid on [0, 1], so it can stand in for part of a linear mid
# term: L2 and the holdout promotion gate are what keep it honest.
# Left out on purpose: market_mid, tte_frac / spread (no direction),
# imbalance (always 0 in training — no historical L2), momentum /
# realized_vol (the app builds them from its tick buffer, training from
# 1m candles — no parity), time_of_day (trainer UTC vs app New York clock).
OFFSET_FEATURES = ["dist_to_strike_vol", "cross_asset", "digital_fair", "prev_window_return", "is_funding_hour", "mid_squared"]
# Rows are clustered: the 13 minutes of one market share one outcome, so
# the effective sample is the market count. 0.05 keeps a calibrated
# market close to w = 0 at a few hundred markets (see test_train_edge.py).
DEFAULT_L2 = 0.05
# EvSide.DEFAULT_MARGIN in the app (and TicketBuilder.modelBeatsImplied):
# skip unless EV clears 3¢ per contract after the fee.
EV_MARGIN = 0.03
# Decision minutes, same as tools/backtest (elapsed minutes since open).
FIRST_DECISION_MINUTE = 1
LAST_DECISION_MINUTE = 13
MIN_TRAIN_ROWS = 20
MAX_PAGES = 400


@dataclass
class Sample:
    """One decision minute of one settled market."""

    x: list[float]
    y: int
    mid: float
    ts: int
    close_ts: int
    ticker: str
    yes_ask: float | None = None
    no_ask: float | None = None


# --- HTTP -------------------------------------------------------------------


class RateLimiter:
    """Thread-safe minimum spacing between requests to one host."""

    def __init__(self, per_second: float) -> None:
        self.min_dt = 1.0 / per_second
        self.lock = threading.Lock()
        self.next_t = 0.0

    def wait(self) -> None:
        with self.lock:
            now = time.monotonic()
            t = max(now, self.next_t)
            self.next_t = t + self.min_dt
        if t > now:
            time.sleep(t - now)


# Kalshi basic tier allows 20 reads/s (docs.kalshi.com/getting_started/rate_limits);
# stay at half. Coinbase public candles: 10 req/s; stay at 5.
KALSHI_LIMIT = RateLimiter(10.0)
COINBASE_LIMIT = RateLimiter(5.0)


def http_get(url: str, retries: int = 5, limiter: RateLimiter | None = None) -> Any:
    last: Exception | None = None
    for attempt in range(retries):
        if limiter is not None:
            limiter.wait()
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": UA})
            with urllib.request.urlopen(req, timeout=45) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = e
            if e.code in (429, 500, 502, 503):
                retry_after = e.headers.get("Retry-After") if e.headers else None
                wait = min(2 ** attempt, 20)
                try:
                    wait = max(wait, float(retry_after)) if retry_after else wait
                except ValueError:
                    pass
                time.sleep(wait)
                continue
            if e.code == 404:
                return {}
            raise
        except urllib.error.URLError as e:
            last = e
            time.sleep(min(2 ** attempt, 12))
    raise RuntimeError(f"GET failed {url}: {last}")


def parse_iso(ts: str | None) -> datetime | None:
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except Exception:
        return None


def iso(ts: int | float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat().replace("+00:00", "Z")


# --- Math -------------------------------------------------------------------


def erf(x: float) -> float:
    sign = -1.0 if x < 0 else 1.0
    ax = abs(x)
    t = 1.0 / (1.0 + 0.3275911 * ax)
    y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * math.exp(-ax * ax)
    return sign * y


def norm_cdf(x: float) -> float:
    return 0.5 * (1.0 + erf(x / math.sqrt(2.0)))


def digital_fair(spot: float, strike: float, tte_s: float, sigma: float) -> float | None:
    if spot <= 0 or strike <= 0 or sigma <= 1e-8:
        return None
    t = max(tte_s, 1.0) / SECONDS_PER_YEAR
    vol = sigma * math.sqrt(t)
    if vol <= 1e-12:
        return 1.0 if spot > strike else 0.0
    d2 = (math.log(spot / strike) - 0.5 * sigma * sigma * t) / vol
    return min(1.0, max(0.0, norm_cdf(d2)))


def dist_vol(spot: float, strike: float, tte_s: float, sigma: float) -> float | None:
    if spot <= 0 or strike <= 0 or sigma <= 1e-8:
        return None
    t = max(tte_s, 1.0) / SECONDS_PER_YEAR
    vol = sigma * math.sqrt(t)
    if vol <= 1e-12:
        return None
    return math.log(spot / strike) / vol


def sigmoid(z: float) -> float:
    z = max(-30.0, min(30.0, z))
    return 1.0 / (1.0 + math.exp(-z))


def softplus(z: float) -> float:
    """log(1 + e^z), overflow-safe."""
    if z > 0:
        return z + math.log1p(math.exp(-z))
    return math.log1p(math.exp(z))


def logit_clip(p: float, clip: float = MID_CLIP) -> float:
    q = min(1.0 - clip, max(clip, p))
    return math.log(q / (1.0 - q))


# --- Candles / spot -----------------------------------------------------------


def _f(x: Any) -> float | None:
    try:
        return float(x) if x is not None else None
    except (TypeError, ValueError):
        return None


def _px(obj: dict | None) -> float | None:
    """Close price in dollars from a Kalshi candle OHLC block."""
    if not obj:
        return None
    if "close_dollars" in obj:
        return _f(obj.get("close_dollars"))
    v = _f(obj.get("close"))
    if v is not None and v > 1.0:
        v /= 100.0  # legacy integer cents
    return v


def candle_quote(c: dict) -> tuple[float | None, float | None]:
    """(yes_bid, yes_ask) at the candle close."""
    return _px(c.get("yes_bid")), _px(c.get("yes_ask"))


def candle_mid(c: dict) -> float | None:
    bid, ask = candle_quote(c)
    if bid is not None and ask is not None:
        return (bid + ask) / 2.0
    return _px(c.get("price"))


def usable(p: float | None) -> float | None:
    """KalshiPrice.usable: 0.1¢–99.9¢ or None."""
    if p is None or not math.isfinite(p):
        return None
    if p < 0.001 - 1e-12 or p > 0.999 + 1e-12:
        return None
    return float(p)


def historical_cutoff_ts() -> int:
    """GET /historical/cutoff — markets settled before this live only under /historical."""
    try:
        body = http_get(f"{KALSHI}/historical/cutoff", limiter=KALSHI_LIMIT) or {}
    except Exception:
        return 0
    dt = parse_iso(body.get("market_settled_ts")) if isinstance(body, dict) else None
    return int(dt.timestamp()) if dt else 0


def _page(path: str, params: dict) -> Iterable[dict]:
    cursor = None
    for _ in range(MAX_PAGES):
        q = dict(params)
        q["limit"] = 200
        if cursor:
            q["cursor"] = cursor
        data = http_get(f"{KALSHI}{path}?{urllib.parse.urlencode(q)}", limiter=KALSHI_LIMIT) or {}
        batch = data.get("markets") or []
        yield from batch
        cursor = data.get("cursor")
        if not batch or not cursor:
            return


def fetch_settled(series: str, days: int, max_markets: int = 0, now: float | None = None) -> list[dict]:
    """Every settled market of [series] that closed in the last [days].

    Pages the live `GET /markets?status=settled` listing by settlement time,
    plus `GET /historical/markets` for anything older than the historical
    cutoff. [max_markets] > 0 keeps only the most recent ones.
    """
    now = time.time() if now is None else now
    min_ts = int(now - days * 86400)
    max_ts = int(now)
    cutoff = historical_cutoff_ts()
    found: dict[str, dict] = {}

    def keep(m: dict) -> None:
        if (m.get("result") or "").lower() not in ("yes", "no"):
            return
        ct = parse_iso(m.get("close_time"))
        if not ct or not (min_ts <= ct.timestamp() <= max_ts) or not m.get("ticker"):
            return
        found[m["ticker"]] = m

    live = {
        "series_ticker": series,
        "status": "settled",
        "min_settled_ts": max(min_ts, cutoff),
        "max_settled_ts": max_ts,
    }
    for m in _page("/markets", live):
        keep(m)
    if cutoff and min_ts < cutoff:
        # Historical listing has no status / time filter; filter locally.
        for m in _page("/historical/markets", {"series_ticker": series}):
            keep(m)
    out = sorted(found.values(), key=lambda m: parse_iso(m.get("close_time")) or datetime.min.replace(tzinfo=timezone.utc))
    if max_markets > 0:
        out = out[-max_markets:]
    return out


def fetch_candles(series: str, ticker: str, open_ts: int, close_ts: int) -> list[dict]:
    q = {"start_ts": open_ts - 60, "end_ts": close_ts + 60, "period_interval": 1}
    data = http_get(
        f"{KALSHI}/series/{series}/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}",
        limiter=KALSHI_LIMIT,
    )
    sticks = (data or {}).get("candlesticks") or []
    if sticks:
        return sticks
    hist = http_get(
        f"{KALSHI}/historical/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}",
        limiter=KALSHI_LIMIT,
    )
    return (hist or {}).get("candlesticks") or []


def fetch_spot(product: str, start: int, end: int) -> list[tuple[int, float]]:
    """1-minute Coinbase closes as (bucket_start_ts, close), oldest first.

    Pages 280 bars per request (the endpoint caps at 300).
    """
    span = 60 * 280
    by_ts: dict[int, float] = {}
    cursor = start
    while cursor < end:
        chunk_end = min(end, cursor + span)
        q = {
            "granularity": 60,
            "start": datetime.fromtimestamp(cursor, tz=timezone.utc).isoformat(),
            "end": datetime.fromtimestamp(chunk_end, tz=timezone.utc).isoformat(),
        }
        try:
            data = http_get(f"{COINBASE}/products/{product}/candles?{urllib.parse.urlencode(q)}", limiter=COINBASE_LIMIT)
        except Exception as e:
            print(f"  spot {product} chunk failed: {e}", flush=True)
            data = []
        if isinstance(data, list):
            for r in data:
                if r and len(r) >= 5:
                    by_ts[int(r[0])] = float(r[4])
        cursor = chunk_end
    return sorted(by_ts.items())


def spot_known_at(spot: list[tuple[int, float]], decision_ts: int, keep: int = SPOT_LOOKBACK_BARS) -> list[float]:
    """Closes whose 1m bar has finished by [decision_ts] — no look-ahead.

    A Coinbase bucket starting at t closes at t + 60. The market's own
    settlement spot must never leak into a mid-window feature row.
    [spot] must be sorted by bucket start.
    """
    hi = bisect.bisect_right(spot, (decision_ts - 60, math.inf))
    oldest = decision_ts - SPOT_MAX_AGE_S
    known = [c for ts, c in spot[max(0, hi - keep) : hi] if ts >= oldest]
    return known[-keep:]


def realized_vol_annual(closes: list[float]) -> float | None:
    """EWMA sigma over 1-minute log returns (lambda = 0.86, ~60 bars).

    The 16-bar sample std was noisy: sigma is the digital fair's only
    parameter, and jitter in it moved the fair ~30% farther from the market
    than EWMA in testing. Same formula as the app's
    ExternalMarketFeatures.realizedVol — trainer and phone must agree.
    """
    if len(closes) < 5:
        return None
    rets = [math.log(b / a) for a, b in zip(closes, closes[1:]) if a > 0 and b > 0]
    if len(rets) < 4:
        return None
    lam = EWMA_LAMBDA
    # Unbiased-start EWMA: seed with the sample variance, then weight the rest.
    mean = sum(rets) / len(rets)
    var = sum((r - mean) ** 2 for r in rets) / len(rets)
    w_sum = 1.0
    for r in rets:
        var = lam * var + (1.0 - lam) * (r - mean) ** 2
        w_sum = lam * w_sum + (1.0 - lam)
    std = math.sqrt(max(var / w_sum, 0.0))
    if std <= 0:
        return None
    return min(5.0, max(0.01, std * math.sqrt(SECONDS_PER_YEAR / 60.0)))


def spot_return(closes: list[float], bars: int) -> float:
    """Simple return over the last [bars] 1m closes (app: spotReturn5m)."""
    if len(closes) <= bars or closes[-1 - bars] <= 0:
        return 0.0
    return (closes[-1] - closes[-1 - bars]) / closes[-1 - bars]


def prev_window_return(spot_rows: list[tuple[int, float]], open_ts: int) -> float:
    """Simple return over the PREVIOUS window (open-900 .. open), 0 when unknown.

    Parity: the app's SpotTape.returnOver(15 min, at open) — bars that closed
    by open_ts only (no look-ahead). The 15-minute sign-reversal tilt
    (arXiv 2608.21888): positive prior move -> slightly lower P(YES).
    """
    lo = open_ts - 900
    hi = open_ts
    known = [c for ts, c in spot_rows if lo + 60 <= ts + 60 <= hi]
    # first and last bar closes strictly inside the previous window
    first = None
    last = None
    for ts, c in spot_rows:
        if ts + 60 <= lo:
            first = c
        elif ts + 60 <= hi and ts >= lo:
            if last is None or ts > (last[0] if last else -1):
                last = (ts, c)
    if first is None or last is None or last[1] <= 0 or first <= 0:
        return 0.0
    return (last[1] - first) / first


def features_for(market: dict, candles: list[dict], spot_rows: list[tuple[int, float]], idx: int) -> list[float] | None:
    mid = candle_mid(candles[idx])
    if mid is None:
        return None
    close_dt = parse_iso(market.get("close_time"))
    end_ts = int(candles[idx].get("end_period_ts") or 0)
    tte = max(0.0, (close_dt.timestamp() if close_dt else end_ts) - end_ts)
    open_dt = parse_iso(market.get("open_time"))
    open_ts = int(open_dt.timestamp()) if open_dt else (int(close_dt.timestamp()) - 900 if close_dt else end_ts - 900)
    window = [candle_mid(c) for c in candles[max(0, idx - 7) : idx + 1]]
    mids = [m for m in window if m is not None]
    momentum = (mids[-1] - mids[0]) if len(mids) >= 2 else 0.0
    bid, ask = candle_quote(candles[idx])
    spread = (ask - bid) if bid is not None and ask is not None else 0.02
    imbalance = 0.0
    spot = spot_known_at(spot_rows, end_ts)
    sigma = realized_vol_annual(spot) if spot else None
    strike = _f(market.get("floor_strike"))
    spot_px = spot[-1] if spot else None
    dist = dist_vol(spot_px, strike, tte, sigma) if spot_px and strike and sigma else 0.0
    fair = digital_fair(spot_px, strike, tte, sigma) if spot_px and strike and sigma else mid
    hour = datetime.fromtimestamp(end_ts or time.time(), tz=timezone.utc).hour
    rvol = 0.0
    if len(mids) >= 3:
        mu = sum(mids) / len(mids)
        rvol = math.sqrt(sum((m - mu) ** 2 for m in mids) / len(mids))
    return [
        float(max(-DIST_CLIP, min(DIST_CLIP, dist or 0.0))),
        float(min(2.0, max(0.0, tte / 900.0))),
        float(min(1.0, max(0.0, mid))),
        float(imbalance),
        float(min(1.0, max(0.0, spread))),
        float(max(-1.0, min(1.0, momentum))),
        float(min(1.0, max(0.0, rvol))),
        float(max(-0.2, min(0.2, spot_return(spot, 5)))),
        float((hour * 60) / (24 * 60)),
        float(fair if fair is not None else mid),
        float(max(-0.05, min(0.05, prev_window_return(spot_rows, open_ts)))),
        1.0 if (close_dt.timestamp() if close_dt else end_ts) % 28800 < 900 else 0.0,
        float(mid * mid),
    ]


def market_samples(market: dict, candles: list[dict], spot_rows: list[tuple[int, float]]) -> list[Sample]:
    """Every decision minute (elapsed 1..13) of one settled market."""
    result = (market.get("result") or "").lower()
    if result not in ("yes", "no"):
        return []
    close_dt = parse_iso(market.get("close_time"))
    if not close_dt:
        return []
    close_ts = int(close_dt.timestamp())
    open_dt = parse_iso(market.get("open_time"))
    open_ts = int(open_dt.timestamp()) if open_dt else close_ts - 900
    label = 1 if result == "yes" else 0
    bars = sorted(
        (c for c in candles if int(c.get("end_period_ts") or 0) > 0),
        key=lambda c: int(c["end_period_ts"]),
    )
    out: list[Sample] = []
    for idx, c in enumerate(bars):
        end_ts = int(c["end_period_ts"])
        if end_ts > close_ts:
            break
        elapsed = int(round((end_ts - open_ts) / 60.0))
        if elapsed < FIRST_DECISION_MINUTE or elapsed > LAST_DECISION_MINUTE:
            continue
        mid = candle_mid(c)
        if mid is None or not (0.0 < mid < 1.0):
            continue
        feats = features_for(market, bars, spot_rows, idx)
        if not feats:
            continue
        yes_bid, yes_ask = candle_quote(c)
        out.append(
            Sample(
                x=feats,
                y=label,
                mid=mid,
                ts=end_ts,
                close_ts=close_ts,
                ticker=str(market.get("ticker") or ""),
                yes_ask=usable(yes_ask),
                no_ask=usable(1.0 - yes_bid) if yes_bid is not None else None,
            )
        )
    return out


def collect(days: int, max_markets: int = 0, workers: int = 3) -> list[Sample]:
    """Live pull: settled markets, their 1m candles, and Coinbase spot."""
    samples: list[Sample] = []
    per = 0 if max_markets <= 0 else max(1, max_markets // len(SERIES))
    for series in SERIES:
        print(f"=== {series}", flush=True)
        markets = fetch_settled(series, days, per)
        print(f"  settled {len(markets)}", flush=True)
        if not markets:
            continue
        closes = [int(parse_iso(m["close_time"]).timestamp()) for m in markets]
        spot = fetch_spot(PRODUCT[series], min(closes) - 900 - 60 * (SPOT_LOOKBACK_BARS + 5), max(closes) + 60)
        print(f"  spot bars {len(spot)}", flush=True)

        def one(m: dict) -> list[Sample]:
            close_ts = int(parse_iso(m["close_time"]).timestamp())
            open_dt = parse_iso(m.get("open_time"))
            open_ts = int(open_dt.timestamp()) if open_dt else close_ts - 900
            candles = fetch_candles(series, m["ticker"], open_ts, close_ts)
            return market_samples(m, candles, spot)

        done = 0
        with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
            futs = {pool.submit(one, m): m for m in markets}
            skipped: list[dict] = []
            for fut in as_completed(futs):
                try:
                    samples.extend(fut.result())
                except Exception as e:
                    # 429s are transient: keep the market for one retry pass
                    # instead of dropping its 13 decision minutes forever.
                    skipped.append(futs[fut])
                    print(f"  skip {futs[fut].get('ticker')}: {e}", flush=True)
            for attempt in range(2):
                if not skipped:
                    break
                print(f"  retrying {len(skipped)} rate-limited markets (pass {attempt + 1})", flush=True)
                time.sleep(30.0 * (attempt + 1))
                retry, skipped = skipped, []
                with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
                    futs = {pool.submit(one, m): m for m in retry}
                    for fut in as_completed(futs):
                        try:
                            samples.extend(fut.result())
                        except Exception as e:
                            skipped.append(futs[fut])
                            print(f"  skip {futs[fut].get('ticker')}: {e}", flush=True)
                done += 1
                if done % 250 == 0:
                    print(f"  candles {done}/{len(markets)} rows {len(samples)}", flush=True)
    return samples


def samples_cache_path(cache: Path, days: int) -> Path:
    return cache / f"samples_{days}d.jsonl"


def load_samples_cache(cache: Path, days: int) -> list[Sample] | None:
    """Previously collected live samples for this window, or None.

    A 270-day pull takes hours and gets 429-skipped markets; once it is
    collected it is cached verbatim (feature rows are immutable — they
    only depend on settled data). Retrains refit on the same rows.
    """
    path = samples_cache_path(cache, days)
    if not path.is_file():
        return None
    out: list[Sample] = []
    with path.open() as f:
        for line in f:
            if not line.strip():
                continue
            try:
                o = json.loads(line)
                out.append(Sample(
                    x=[float(v) for v in o["x"]],
                    y=int(o["y"]),
                    mid=float(o["mid"]),
                    ts=int(o["ts"]),
                    close_ts=int(o["close_ts"]),
                    ticker=str(o["ticker"]),
                    yes_ask=float(o["yes_ask"]) if o.get("yes_ask") is not None else None,
                    no_ask=float(o["no_ask"]) if o.get("no_ask") is not None else None,
                ))
            except Exception:
                continue
    print(f"  samples cache {path}: {len(out)} rows", flush=True)
    return out if out else None


def save_samples_cache(cache: Path, days: int, samples: list[Sample]) -> None:
    cache.mkdir(parents=True, exist_ok=True)
    path = samples_cache_path(cache, days)
    tmp = path.with_suffix(".tmp")
    with tmp.open("w") as f:
        for s in samples:
            f.write(json.dumps({
                "x": s.x, "y": s.y, "mid": s.mid, "ts": s.ts,
                "close_ts": s.close_ts, "ticker": s.ticker,
                "yes_ask": s.yes_ask, "no_ask": s.no_ask,
            }) + "\n")
    tmp.replace(path)
    print(f"  wrote samples cache {path} ({len(samples)} rows)", flush=True)


def load_backtest_cache(cache: Path, days: int | None = None) -> list[Sample]:
    """Build samples from a `tools/backtest` cache (markets.jsonl, candles/, spot_*.json)."""
    markets = []
    mpath = cache / "markets.jsonl"
    if mpath.is_file():
        with mpath.open() as f:
            markets = [json.loads(line) for line in f if line.strip()]
    spots: dict[str, list[tuple[int, float]]] = {}
    for series, product in PRODUCT.items():
        p = cache / f"spot_{product}.json"
        if p.is_file():
            rows = json.loads(p.read_text())
            spots[series] = sorted({int(r[0]): float(r[4]) for r in rows if r and len(r) >= 5}.items())
    min_close_ms = None
    if days and markets:
        min_close_ms = max(int(m.get("close_ms") or 0) for m in markets) - days * 86_400_000
    samples: list[Sample] = []
    for m in markets:
        if not m.get("ticker") or not m.get("close_ms") or m.get("series") not in PRODUCT:
            continue
        if min_close_ms is not None and int(m["close_ms"]) < min_close_ms:
            continue
        cpath = cache / "candles" / f"{m['ticker']}.json"
        if not cpath.is_file():
            continue
        market = {
            "ticker": m["ticker"],
            "result": m.get("result"),
            "floor_strike": m.get("floor_strike"),
            "close_time": iso(int(m["close_ms"]) / 1000),
            "open_time": iso(int(m["open_ms"]) / 1000) if m.get("open_ms") else None,
        }
        candles = []
        for r in json.loads(cpath.read_text()):
            c: dict[str, Any] = {"end_period_ts": int(r.get("end_ts") or 0)}
            for key in ("yes_bid", "yes_ask", "price"):
                close = (r.get(key) or {}).get("close")
                if close is not None:
                    c[key] = {"close_dollars": close}
            candles.append(c)
        samples.extend(market_samples(market, candles, spots.get(m["series"], [])))
    return samples


# --- Fit ----------------------------------------------------------------------


def design_columns(kind: str, design: list[str] | None = None) -> list[int]:
    if kind == KIND_LOGISTIC:
        return list(range(len(FEATURE_NAMES)))
    names = OFFSET_FEATURES if design is None else design
    return [FEATURE_NAMES.index(n) for n in names]


def fit_scaler(X: list[list[float]], cols: list[int]) -> tuple[list[float], list[float]]:
    """Mean / std on [cols]; every other column passes through as 0 / 1."""
    n_feat = len(FEATURE_NAMES)
    mean = [0.0] * n_feat
    std = [1.0] * n_feat
    for c in cols:
        mu = sum(row[c] for row in X) / len(X)
        var = sum((row[c] - mu) ** 2 for row in X) / max(1, len(X) - 1)
        mean[c] = mu
        std[c] = math.sqrt(var) if var > 1e-12 else 1.0
    return mean, std


def standardize(X: list[list[float]]) -> tuple[list[list[float]], list[float], list[float]]:
    n = len(X[0])
    mean = [sum(row[i] for row in X) / len(X) for i in range(n)]
    std = []
    for i in range(n):
        var = sum((row[i] - mean[i]) ** 2 for row in X) / max(1, len(X) - 1)
        std.append(math.sqrt(var) if var > 1e-12 else 1.0)
    Z = [[(row[i] - mean[i]) / std[i] for i in range(n)] for row in X]
    return Z, mean, std


def _solve(A: list[list[float]], b: list[float]) -> list[float]:
    """Dense Gaussian elimination with partial pivoting (tiny systems only)."""
    n = len(b)
    M = [list(A[i]) + [b[i]] for i in range(n)]
    for c in range(n):
        piv = max(range(c, n), key=lambda r: abs(M[r][c]))
        if abs(M[piv][c]) < 1e-300:
            raise ValueError("singular Hessian")
        M[c], M[piv] = M[piv], M[c]
        for r in range(c + 1, n):
            f = M[r][c] / M[c][c]
            if f:
                row_r, row_c = M[r], M[c]
                for k in range(c, n + 1):
                    row_r[k] -= f * row_c[k]
    x = [0.0] * n
    for r in range(n - 1, -1, -1):
        x[r] = (M[r][n] - sum(M[r][k] * x[k] for k in range(r + 1, n))) / M[r][r]
    return x


def fit_logistic(
    X: list[list[float]],
    y: list[int],
    offset: list[float] | None = None,
    l2: float = DEFAULT_L2,
    penalize_bias: bool | None = None,
    max_iter: int = 100,
    tol: float = 1e-9,
) -> tuple[list[float], float]:
    """L2-regularized logistic regression by damped Newton (IRLS), to convergence.

    Minimizes mean log-loss of sigmoid(offset + b + w·x) plus
    ½·l2·(‖w‖² [+ b²]). With [offset] set the bias is penalized too (by
    default), so the prior is "the offset is right": w = b = 0.
    """
    n = len(X)
    d = len(X[0]) if X else 0
    if penalize_bias is None:
        penalize_bias = offset is not None
    off = offset if offset is not None else [0.0] * n
    reg = [l2 if penalize_bias else 0.0] + [l2] * d
    theta = [0.0] * (d + 1)  # [b, w_1..w_d]
    rows = [(1.0, *x) for x in X]

    def etas(th: list[float]) -> list[float]:
        return [o + sum(t * v for t, v in zip(th, r)) for o, r in zip(off, rows)]

    def loss(th: list[float], eta: list[float]) -> float:
        s = sum(softplus(e) - yi * e for e, yi in zip(eta, y)) / n
        return s + 0.5 * sum(r * t * t for r, t in zip(reg, th))

    eta = etas(theta)
    cur = loss(theta, eta)
    for _ in range(max_iter):
        g = [0.0] * (d + 1)
        H = [[0.0] * (d + 1) for _ in range(d + 1)]
        for e, yi, r in zip(eta, y, rows):
            p = sigmoid(e)
            res = p - yi
            wt = p * (1.0 - p)
            for a in range(d + 1):
                ra = r[a]
                g[a] += res * ra
                wa = wt * ra
                Ha = H[a]
                for b_ in range(a, d + 1):
                    Ha[b_] += wa * r[b_]
        for a in range(d + 1):
            g[a] = g[a] / n + reg[a] * theta[a]
            for b_ in range(a, d + 1):
                H[a][b_] /= n
                H[b_][a] = H[a][b_]
            H[a][a] += reg[a] + 1e-12
        if max(abs(v) for v in g) < tol:
            break
        step = _solve(H, g)
        decrease = sum(gi * si for gi, si in zip(g, step))
        t = 1.0
        accepted = False
        while t > 1e-8:
            cand = [th - t * s for th, s in zip(theta, step)]
            ceta = etas(cand)
            cl = loss(cand, ceta)
            if cl <= cur - 1e-4 * t * decrease:
                theta, eta, cur, accepted = cand, ceta, cl, True
                break
            t *= 0.5
        if not accepted or max(abs(t * s) for s in step) < 1e-12:
            break
    return theta[1:], theta[0]


def fit_platt(p: list[float], y: list[int]) -> tuple[float, float]:
    """One-feature logistic on logit(p)."""
    xs = [[logit_clip(pi, 1e-6)] for pi in p]
    w, b = fit_logistic(xs, y, l2=1e-6)
    return w[0], b


def fit_model(samples: list[Sample], kind: str = KIND_OFFSET, design: list[str] | None = None, l2: float = DEFAULT_L2) -> dict[str, Any]:
    """Fit on [samples]; returns the exported model dict (all 10 weights)."""
    cols = design_columns(kind, design)
    X = [s.x for s in samples]
    y = [s.y for s in samples]
    mean, std = fit_scaler(X, cols)
    Z = [[(x[c] - mean[c]) / std[c] for c in cols] for x in X]
    model: dict[str, Any] = {
        "kind": kind,
        "weights": [0.0] * len(FEATURE_NAMES),
        "bias": 0.0,
        "mean": mean,
        "std": std,
        "platt_a": 1.0,
        "platt_b": 0.0,
        "design": [FEATURE_NAMES[c] for c in cols],
        "l2": l2,
    }
    if kind == KIND_OFFSET:
        model["mid_clip"] = MID_CLIP
        off = [logit_clip(s.mid, MID_CLIP) for s in samples]
        w, b = fit_logistic(Z, y, offset=off, l2=l2)
    else:
        w, b = fit_logistic(Z, y, l2=l2)
    for j, c in enumerate(cols):
        model["weights"][c] = w[j]
    model["bias"] = b
    if kind == KIND_LOGISTIC:
        raw = [model_predict(model, s.x, s.mid) for s in samples]
        model["platt_a"], model["platt_b"] = fit_platt(raw, y)
    return model


def model_predict(model: dict[str, Any], x: list[float], mid: float | None = None) -> float:
    """Exactly what `EdgeModel.predictYes` computes on the phone."""
    z = float(model["bias"])
    for i, w in enumerate(model["weights"]):
        if w == 0.0:
            continue
        s = model["std"][i]
        s = 1.0 if s < 1e-6 else s
        z += w * (x[i] - model["mean"][i]) / s
    offset_kind = model.get("kind") == KIND_OFFSET
    clip = float(model.get("mid_clip", MID_CLIP))
    if offset_kind:
        z += logit_clip(x[MID_INDEX] if mid is None else mid, clip)
    p = sigmoid(z)
    a = float(model.get("platt_a", 1.0))
    pb = float(model.get("platt_b", 0.0))
    if a != 1.0 or pb != 0.0:
        p = sigmoid(a * logit_clip(p, 1e-6) + pb)
    lo = clip if offset_kind else 0.02
    return min(1.0 - lo, max(lo, p))


def predict_rows(X: list[list[float]], w: list[float], b: float, a: float = 1.0, pb: float = 0.0) -> list[float]:
    """Legacy helper: standardized rows → clamped P(YES)."""
    out = []
    for row in X:
        p = sigmoid(b + sum(wj * xj for wj, xj in zip(w, row)))
        if a != 1.0 or pb != 0.0:
            p = sigmoid(a * logit_clip(p, 1e-6) + pb)
        out.append(min(0.98, max(0.02, p)))
    return out


# --- Metrics / EV side ---------------------------------------------------------


def brier(p: list[float], y: list[int]) -> float:
    return sum((pi - yi) ** 2 for pi, yi in zip(p, y)) / len(y)


def logloss(p: list[float], y: list[int]) -> float:
    s = 0.0
    for pi, yi in zip(p, y):
        q = min(1 - 1e-9, max(1e-9, pi))
        s += -(yi * math.log(q) + (1 - yi) * math.log(1 - q))
    return s / len(y)


def calibration_error(p: list[float], y: list[int], bins: int = 10) -> float:
    """Expected calibration error; reported with proper scores, never alone."""
    if not p:
        return 1.0
    total = 0.0
    for bucket in range(bins):
        rows = [(pi, yi) for pi, yi in zip(p, y) if min(bins - 1, int(pi * bins)) == bucket]
        if rows:
            total += len(rows) / len(p) * abs(
                sum(pi for pi, _ in rows) / len(rows) - sum(yi for _, yi in rows) / len(rows)
            )
    return total


def fee_per_contract(price: float, fee_rate: float = FEE_RATE) -> float:
    """Kalshi taker fee `rate·P·(1−P)` per contract, before cent rounding.

    The app amortizes the rounded order fee over a $5 ticket
    (KalshiFee.perContract); the difference is well under 1¢.
    """
    return fee_rate * price * (1.0 - price)


def ev_side(p_yes: float, yes_ask: float | None, no_ask: float | None, margin: float = EV_MARGIN, fee_rate: float = FEE_RATE) -> tuple[str | None, float | None, float | None]:
    """EvSide rule: (side or None, ev_yes, ev_no) per contract at the ask."""
    ya = usable(yes_ask)
    na = usable(no_ask)
    ev_yes = (p_yes - ya - fee_per_contract(ya, fee_rate)) if ya is not None else None
    ev_no = ((1.0 - p_yes) - na - fee_per_contract(na, fee_rate)) if na is not None else None
    best = None
    if ev_yes is not None and (ev_no is None or ev_yes >= ev_no):
        best = ("YES", ev_yes)
    elif ev_no is not None:
        best = ("NO", ev_no)
    if best is None or best[1] <= margin:
        return None, ev_yes, ev_no
    return best[0], ev_yes, ev_no


def bet_log(rows: list[tuple[Sample, float]], margin: float = EV_MARGIN) -> list[dict[str, Any]]:
    """Every simulated EV-at-ask bet, newest last, for release publication.

    One bet per market (first minute clearing the margin, same as ev_pnl):
    ticker, minute offset, side, ask, fee, model P(YES), mid, EV, outcome.
    """
    by_ticker: dict[str, list[tuple[Sample, float]]] = {}
    for s, p in rows:
        by_ticker.setdefault(s.ticker, []).append((s, p))
    out: list[dict[str, Any]] = []
    for ticker, items in by_ticker.items():
        items.sort(key=lambda sp: sp[0].ts)
        for s, p in items:
            side, ev_yes, ev_no = ev_side(p, s.yes_ask, s.no_ask, margin)
            if side is None:
                continue
            ask = s.yes_ask if side == "YES" else s.no_ask
            fee = fee_per_contract(ask)
            won = (s.y == 1) == (side == "YES")
            out.append({
                "ticker": ticker,
                "minute": (s.ts - (s.close_ts - 900)) // 60,
                "side": side,
                "ask": ask,
                "fee": fee,
                "p_yes": p,
                "mid": s.mid,
                "ev": (ev_yes if side == "YES" else ev_no),
                "won": won,
                "pnl": (1.0 - ask - fee) if won else (-ask - fee),
            })
            break
    out.sort(key=lambda b: b["ticker"])
    return out


def bootstrap_pnl_ci(
    rows: list[tuple[Sample, float]],
    margin: float = EV_MARGIN,
    n_boot: int = 1000,
    level: float = 0.90,
    seed: int = 2026,
) -> dict[str, float]:
    """Block bootstrap over DAYS of the EV-at-ask betting rule.

    A market's minutes share one settlement, and a day's markets share a
    vol regime, so the resample unit is the UTC DAY (all of its bets move
    together). The CI is on per-contract P&L; `ci_low > 0` is the promotion
    gate — a higher hit rate alone never is.
    """
    by_day: dict[int, list[tuple[Sample, float]]] = {}
    for s, p in rows:
        by_day.setdefault(s.close_ts // 86400, []).append((s, p))
    days = sorted(by_day.values(), key=lambda d: min(s.ts for s, _ in d))
    if not days:
        return {"ci_low": 0.0, "ci_high": 0.0, "p_low": 0.0, "n_boot_markets": 0.0}
    rng = random.Random(seed)
    per_bet: list[float] = []
    for m in range(n_boot):
        pnl = 0.0
        n = 0
        for _ in range(len(days)):
            for s, p in days[rng.randrange(len(days))]:
                side, _, _ = ev_side(p, s.yes_ask, s.no_ask, margin)
                if side is None:
                    continue
                ask = s.yes_ask if side == "YES" else s.no_ask
                fee = fee_per_contract(ask)
                won = (s.y == 1) == (side == "YES")
                pnl += (1.0 - ask - fee) if won else (-ask - fee)
                n += 1
        if n:
            per_bet.append(pnl / n)
    if not per_bet:
        return {"ci_low": 0.0, "ci_high": 0.0, "p_low": 1.0, "n_boot_markets": 0.0}
    per_bet.sort()
    def q(frac: float) -> float:
        return per_bet[min(len(per_bet) - 1, max(0, int(frac * len(per_bet))))]
    lo, hi = q((1.0 - level) / 2.0), q(1.0 - (1.0 - level) / 2.0)
    p_low = sum(1.0 for v in per_bet if v <= 0.0) / len(per_bet)
    return {"ci_low": lo, "ci_high": hi, "p_low": p_low, "n_boot_markets": float(len(days))}


def ev_pnl(rows: list[tuple[Sample, float]], margin: float = EV_MARGIN) -> dict[str, float]:
    """One bet per market at the first minute whose EV at the ask clears [margin].

    Per-contract P&L at the candle-close ask: YES = yes_ask, NO = 1 − yes_bid.
    """
    by_ticker: dict[str, list[tuple[Sample, float]]] = {}
    for s, p in rows:
        by_ticker.setdefault(s.ticker, []).append((s, p))
    n = hits = 0
    pnl = asks = 0.0
    for items in by_ticker.values():
        items.sort(key=lambda sp: sp[0].ts)
        for s, p in items:
            side, _, _ = ev_side(p, s.yes_ask, s.no_ask, margin)
            if side is None:
                continue
            ask = s.yes_ask if side == "YES" else s.no_ask
            fee = fee_per_contract(ask)
            won = (s.y == 1) == (side == "YES")
            pnl += (1.0 - ask - fee) if won else (-ask - fee)
            asks += ask
            hits += int(won)
            n += 1
            break
    return {
        "n": float(n),
        "pnl": pnl,
        "hit_rate": (hits / n) if n else 0.0,
        "avg_ask": (asks / n) if n else 0.0,
        "pnl_per_bet": (pnl / n) if n else 0.0,
    }


def fold_splits(samples: list[Sample], folds: int = 4) -> list[tuple[list[Sample], list[Sample]]]:
    """(train, test) pairs. Folds are cut on market close times, so one
    market's minutes never straddle a boundary. Fold k trains only on
    markets that settled by fold k's first decision minute. Fold 0 is
    train-only."""
    order = sorted(samples, key=lambda s: (s.close_ts, s.ts, s.ticker))
    closes = sorted({s.close_ts for s in order})
    if len(closes) < 2:
        return []
    folds = max(2, min(folds, len(closes)))
    bounds = [closes[(k * len(closes)) // folds] for k in range(folds)] + [math.inf]
    out = []
    for k in range(1, folds):
        test = [s for s in order if bounds[k] <= s.close_ts < bounds[k + 1]]
        if not test:
            continue
        first_ts = min(s.ts for s in test)
        train = [s for s in order if s.close_ts <= first_ts]
        if len(train) >= MIN_TRAIN_ROWS:
            out.append((train, test))
    return out


def walk_forward_hold(
    samples: list[Sample],
    folds: int = 4,
    kind: str = KIND_OFFSET,
    design: list[str] | None = None,
    l2: float = DEFAULT_L2,
) -> list[tuple[Sample, float]]:
    """Walk-forward holdout rows (sample, predicted P(YES)) in time order."""
    hold: list[tuple[Sample, float]] = []
    for train, test in fold_splits(samples, folds):
        model = fit_model(train, kind, design, l2)
        hold.extend((s, model_predict(model, s.x, s.mid)) for s in test)
    return hold


def recency_metrics(hold: list[tuple[Sample, float]], fraction: float = 0.25) -> dict[str, float]:
    """Scores on the most recent [fraction] of the holdout only.

    Crypto vol regimes drift over months; a model can beat the market on the
    full holdout and still be stale on the most recent data. These numbers
    gate nothing by themselves (the full-holdout gates do), but the manifest
    publishes them so a stale fit is visible before promotion.
    """
    if not hold:
        return {"recent_model_brier": 1.0, "recent_market_brier": 0.0, "recent_n": 0.0}
    cut = int(len(hold) * (1.0 - fraction))
    recent = hold[cut:]
    ph = [p for _, p in recent]
    yh = [s.y for s, _ in recent]
    mh = [s.mid for s, _ in recent]
    return {
        "recent_model_brier": brier(ph, yh),
        "recent_market_brier": brier(mh, yh),
        "recent_n": float(len(recent)),
    }


def sweep_l2(
    samples: list[Sample],
    folds: int = 4,
    kind: str = KIND_OFFSET,
    design: list[str] | None = None,
    candidates: tuple[float, ...] = (0.5, 0.05, 0.005),
) -> tuple[float, dict[str, dict[str, float]]]:
    """Pick the L2 that minimizes holdout log-loss on the same folds.

    Rows are clustered by market, so a smaller L2 can look better by
    memorizing; log-loss punishes overconfidence harder than Brier, which
    makes it the right selection score. Returns (best_l2, per-candidate
    metrics) — nested selection stays inside the walk-forward folds, so
    the final gate numbers are never chosen on their own holdout.
    """
    per: dict[str, dict[str, float]] = {}
    best_l2, best_ll = None, float("inf")
    for cand in candidates:
        hold = walk_forward_hold(samples, folds, kind, design, cand)
        if not hold:
            continue
        ll = logloss([p for _, p in hold], [s.y for s, _ in hold])
        mb = brier([p for _, p in hold], [s.y for s, _ in hold])
        mm = brier([s.mid for s, _ in hold], [s.y for s, _ in hold])
        per[str(cand)] = {"logloss": ll, "brier": mb, "market_brier": mm}
        if ll < best_ll:
            best_ll, best_l2 = ll, cand
    if best_l2 is None:
        best_l2 = DEFAULT_L2
    return best_l2, per


def margin_curve(hold: list[tuple[Sample, float]], margins: tuple[float, ...] = (0.0, 0.01, 0.02, 0.03, 0.05, 0.08, 0.12)) -> dict[str, dict[str, float]]:
    """EV-at-ask P&L per margin threshold: how picky should the bet rule be?

    The app fires at EV_MARGIN = 3¢. If a higher margin shows a materially
    better P&L per bet with enough trades, raising the app's threshold is
    the cheapest possible improvement: fewer, better bets. Diagnostic only —
    the bootstrap CI at the shipped margin stays the promotion gate.
    """
    out: dict[str, dict[str, float]] = {}
    for m in margins:
        stats = ev_pnl(hold, m)
        out[f"{m:.2f}"] = {
            "n": stats["n"],
            "pnl": stats["pnl"],
            "pnl_per_bet": stats["pnl_per_bet"],
            "hit_rate": stats["hit_rate"],
        }
    return out


def walk_forward(
    samples: list[Sample],
    folds: int = 4,
    kind: str = KIND_OFFSET,
    design: list[str] | None = None,
    l2: float = DEFAULT_L2,
    ev_margin: float = EV_MARGIN,
) -> dict[str, Any]:
    """Time-ordered walk-forward over [fold_splits]."""
    hold = walk_forward_hold(samples, folds, kind, design, l2)
    return walk_forward_metrics(hold, samples, ev_margin, folds=folds, l2=l2)


def walk_forward_metrics(
    hold: list[tuple[Sample, float]],
    samples: list[Sample],
    ev_margin: float,
    folds: int,
    l2: float,
) -> dict[str, Any]:
    """Holdout metrics for precomputed walk-forward rows."""
    if not hold:
        return {"n_samples": float(len(samples)), "n_holdout": 0.0}
    ph = [p for _, p in hold]
    yh = [s.y for s, _ in hold]
    mh = [s.mid for s, _ in hold]
    sim = ev_pnl(hold, ev_margin)
    market_sim = ev_pnl([(s, s.mid) for s, _ in hold], ev_margin)
    boot = bootstrap_pnl_ci(hold, ev_margin)
    model_brier = brier(ph, yh)
    market_brier = brier(mh, yh)
    return {
        "n_samples": float(len(samples)),
        "n_markets": float(len({s.ticker for s in samples})),
        "n_holdout": float(len(hold)),
        "n_holdout_markets": float(len({s.ticker for s, _ in hold})),
        "folds": float(folds),
        "l2": float(l2),
        "model_brier": model_brier,
        "market_brier": market_brier,
        "brier_skill_vs_market": (1.0 - model_brier / market_brier) if market_brier > 0 else 0.0,
        "model_logloss": logloss(ph, yh),
        "market_logloss": logloss(mh, yh),
        "ev_margin": float(ev_margin),
        "sim_trades": sim["n"],
        "sim_pnl": sim["pnl"],
        "sim_hit_rate": sim["hit_rate"],
        "sim_avg_ask": sim["avg_ask"],
        "sim_pnl_per_bet": sim["pnl_per_bet"],
        # Sanity: the market as its own model can never clear ask + fee.
        "market_sim_trades": market_sim["n"],
        "model_calibration_error": calibration_error(ph, yh),
        "market_calibration_error": calibration_error(mh, yh),
        "boot_ci_low": boot["ci_low"],
        "boot_ci_high": boot["ci_high"],
        "boot_p_of_loss": boot["p_low"],
        "boot_markets": boot["n_boot_markets"],
        "margin_curve": margin_curve(hold),  # type: ignore[arg-type]
    }


def fixture_dataset(n: int = 240) -> list[Sample]:
    out: list[Sample] = []
    t0 = 1_700_000_000
    for i in range(n):
        mid = 0.35 + 0.3 * ((i % 40) / 40.0)
        dist = (mid - 0.5) * 2
        fair = min(0.95, max(0.05, mid + 0.08 * math.sin(i / 7.0)))
        row = [dist, 0.5, mid, 0.0, 0.02, 0.01, 0.04, 0.0, (i % 24) / 24.0, fair, 0.0, 0.0, mid * mid]
        label = 1 if fair + 0.02 * math.sin(i) > 0.5 else 0
        ts = t0 + i * 900
        out.append(
            Sample(
                x=row,
                y=label,
                mid=mid,
                ts=ts,
                close_ts=ts + 300,
                ticker=f"FIXTURE-{i}",
                yes_ask=round(mid + 0.01, 3),
                no_ask=round(1.0 - (mid - 0.01), 3),
            )
        )
    return out


# --- Export -------------------------------------------------------------------


def write_manifest(metrics: dict[str, Any], path: Path, trained_at: str | None = None, synthetic: bool = False, tag: str = "mis-bitcoin-edge-model", version: str = "2") -> None:
    n = int(metrics.get("n_holdout", metrics.get("n_samples", 0)) or 0)
    model_brier = float(metrics.get("model_brier", 1.0))
    market_brier = float(metrics.get("market_brier", 1.0))
    model_ll = float(metrics.get("model_logloss", 1.0))
    market_ll = float(metrics.get("market_logloss", 1.0))
    payload = {
        "version": version,
        "trained_at": trained_at or datetime.now(timezone.utc).isoformat(),
        "n_samples": n,
        "n_holdout": n,
        "model_brier": model_brier,
        "market_brier": market_brier,
        "model_logloss": model_ll,
        "market_logloss": market_ll,
        "sim_trades": metrics.get("sim_trades"),
        "sim_pnl": metrics.get("sim_pnl"),
        "sim_hit_rate": metrics.get("sim_hit_rate"),
        "model_asset": "edge_model.json",
        "tag": tag,
        "synthetic": synthetic,
        "model_calibration_error": metrics.get("model_calibration_error"),
        "market_calibration_error": metrics.get("market_calibration_error"),
        "boot_ci_low": metrics.get("boot_ci_low"),
        "boot_ci_high": metrics.get("boot_ci_high"),
        "boot_p_of_loss": metrics.get("boot_p_of_loss"),
        "sim_trades": metrics.get("sim_trades"),
        # Synthetic (fixture) data never activates on a phone.
        "beats_market": (not synthetic) and n > 0 and model_brier < market_brier and model_ll < market_ll,
        # Promotion needs BOTH: the model beats the market mid out of sample
        # (proper scores) AND the market-block bootstrap P&L CI at the ask
        # excludes zero. Either failing keeps the model advisory-only.
        "promotion_eligible": (
            (not synthetic)
            and n > 0
            and model_brier < market_brier
            and model_ll < market_ll
            and float(metrics.get("sim_trades", 0) or 0) >= 200
            and float(metrics.get("boot_ci_low", 0.0) or 0.0) > 0.0
        ),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"wrote {path}", flush=True)


def export(model: dict[str, Any], metrics: dict[str, Any], path: Path) -> None:
    offset_kind = model.get("kind") == KIND_OFFSET
    payload: dict[str, Any] = {
        "version": 2 if offset_kind else 1,
        "kind": model.get("kind", KIND_LOGISTIC),
        "feature_names": FEATURE_NAMES,
        "weights": model["weights"],
        "bias": model["bias"],
        "mean": model["mean"],
        "std": model["std"],
        "platt_a": model["platt_a"],
        "platt_b": model["platt_b"],
        # Offset models are already anchored to the market: the app uses
        # their output as the fair and does not re-blend with the mid.
        "blend_weight": 1.0 if offset_kind else 0.35,
        "fee_margin": FEE_RATE,
        "confidence_margin": CONF_MARGIN,
        "ev_margin": EV_MARGIN,
        "design_features": model.get("design", []),
        "l2": model.get("l2", DEFAULT_L2),
        "metrics": {k: float(v) for k, v in metrics.items() if isinstance(v, (int, float)) and math.isfinite(v)},
    }
    if offset_kind:
        payload["mid_clip"] = model.get("mid_clip", MID_CLIP)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"wrote {path}", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--max-markets", type=int, default=0, help="0 = every settled market in the window")
    ap.add_argument("--kind", choices=[KIND_OFFSET, KIND_LOGISTIC], default=KIND_OFFSET)
    ap.add_argument("--features", default=",".join(OFFSET_FEATURES), help="offset model design columns")
    ap.add_argument("--l2", type=float, default=DEFAULT_L2)
    ap.add_argument("--sweep-l2", action="store_true", help="pick L2 by holdout log-loss over a fixed grid")
    ap.add_argument("--folds", type=int, default=4)
    ap.add_argument("--ev-margin", type=float, default=EV_MARGIN)
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--cache", default=None, help="train from a tools/backtest cache dir instead of the network")
    ap.add_argument("--samples-cache", default=None, help="dir to cache/reuse the collected live sample rows")
    ap.add_argument("--tag", default="mis-bitcoin-edge-model", help="release tag written into the manifest")
    ap.add_argument("--out", default=str(ML_DIR / "edge_model.json"))
    ap.add_argument("--manifest", default=str(ML_DIR / "edge_model_manifest.json"))
    ap.add_argument("--fixture", action="store_true")
    ap.add_argument("--require-live", action="store_true", help="exit 1 instead of falling back to the fixture")
    args = ap.parse_args()
    design = [f.strip() for f in args.features.split(",") if f.strip()]
    unknown = [f for f in design if f not in FEATURE_NAMES]
    if unknown:
        ap.error(f"unknown features {unknown}")

    synthetic = bool(args.fixture)
    if args.fixture:
        samples = fixture_dataset()
    else:
        try:
            if args.samples_cache:
                scache = Path(args.samples_cache)
                cached = load_samples_cache(scache, args.days)
                if cached is not None:
                    samples = cached
                else:
                    samples = collect(args.days, args.max_markets, args.workers)
                    save_samples_cache(scache, args.days, samples)
            else:
                samples = load_backtest_cache(Path(args.cache), args.days) if args.cache else collect(args.days, args.max_markets, args.workers)
        except Exception as e:
            if args.require_live:
                print(f"live collect failed: {e}", flush=True)
                return 1
            print(f"live collect failed ({e}); falling back to fixture", flush=True)
            samples, synthetic = fixture_dataset(), True
        if len(samples) < 30:
            if args.require_live:
                print(f"only {len(samples)} rows", flush=True)
                return 1
            print(f"only {len(samples)} rows — using fixture so the export still exists", flush=True)
            samples, synthetic = fixture_dataset(), True
    n_yes = sum(s.y for s in samples)
    print(f"samples {len(samples)} markets {len({s.ticker for s in samples})} yes={n_yes} no={len(samples) - n_yes}", flush=True)
    if args.sweep_l2 and not synthetic:
        best_l2, per = sweep_l2(samples, args.folds, args.kind, design)
        print("l2 sweep:", json.dumps(per), flush=True)
        print(f"selected l2={best_l2}", flush=True)
        args.l2 = best_l2
    hold = walk_forward_hold(samples, args.folds, args.kind, design, args.l2)
    metrics = walk_forward_metrics(hold, samples, args.ev_margin, folds=args.folds, l2=args.l2)
    if not synthetic:
        metrics.update(recency_metrics(hold))
    if synthetic:
        metrics["synthetic"] = 1.0
    print(json.dumps(metrics, indent=2), flush=True)
    model = fit_model(samples, args.kind, design, args.l2)
    print("weights " + ", ".join(f"{n}={w:+.4f}" for n, w in zip(FEATURE_NAMES, model["weights"]) if w) + f", bias={model['bias']:+.4f}", flush=True)
    export(model, metrics, Path(args.out))
    write_manifest(metrics, Path(args.manifest), synthetic=synthetic, tag=args.tag, version="2" if args.kind == KIND_OFFSET else "1")
    if not synthetic:
        log_path = Path(args.out).resolve().parent / "bet_log.json"
        log_path.write_text(json.dumps(bet_log(hold, args.ev_margin), indent=2), encoding="utf-8")
        print(f"wrote {log_path}", flush=True)
    # Do not overwrite the hand-checked Android/Python parity fixture.
    # Write a sample next to the exported model for debugging only.
    if not args.fixture:
        s = samples[0]
        sample = {"x": s.x, "mid": s.mid, "p": model_predict(model, s.x, s.mid)}
        out_dir = Path(args.out).resolve().parent
        (out_dir / "parity_sample.last.json").write_text(json.dumps(sample, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
