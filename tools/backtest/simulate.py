"""Walk-forward simulation. One bet max per market; first qualifying minute only."""

from __future__ import annotations

import hashlib
import json
import math
import random
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from fetch import COIN, PRODUCT, load_jsonl
from pipeline import (
    DIRK_MIN_PROFIT,
    STAKE_USD,
    Decision,
    DecisionEngine,
    dirk_ok,
    ev_side,
    net_profit_if_win,
    size_all_in,
    usable,
)
from pipeline import (
    ENTRY_MIN_ELAPSED_MIN,
    ENTRY_MIN_STRIKE_BP,
    ENTRY_NEAR_STRIKE_OVERRIDE_PP,
    entry_filter,
)


@dataclass
class Bet:
    strategy: str
    ticker: str
    series: str
    coin: str
    day: str
    now_ms: int
    elapsed_min: int
    tte_sec: int
    tte_bucket: str
    side: str
    ask: float
    mid: float
    contracts: int
    cost: float
    fee: float
    won: bool
    pnl: float
    result: str
    fair_yes: float
    mlp_yes: float
    digital_fair: float | None
    delta_pp: float
    net_edge_pp: float
    dist_bps: float | None
    would_alert: bool
    split: str  # is / oos


STRATEGIES = (
    "app_shipped",
    "app_dirk",
    "fair_dirk",
    "cheap_side",
    "always_favorite",
    "random_side",
    # App ticket side since the EV change: fair vs ask + fee, 3¢ margin
    # (EvSide / TicketBuilder.resolveSide when the engine has a fair).
    "ev_side",
)
STRESS_STRATEGIES = ("app_shipped_stress",)
# OOS only; not in the IS tune grid; do not treat as a claimed edge.
EXPLORATORY_STRATEGIES = ("app_32_50", "app_tte_11_9")
ALL_STRATEGIES = STRATEGIES + STRESS_STRATEGIES + EXPLORATORY_STRATEGIES


def _day(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def _spot_index(bars: list[list]) -> dict[int, float]:
    """Map unix-minute → close."""
    out = {}
    for row in bars:
        t = int(row[0])
        out[t] = float(row[4])
    return out


def _spot_at(idx: dict[int, float], ts_sec: int) -> float | None:
    """Close of the Coinbase 1m bar that *ended* at ts_sec (no look-ahead).

    Coinbase `time` is the bar start. Kalshi `end_period_ts` is the inclusive end.
    The contemporaneous bar therefore has start = end_ts - 60.
    """
    end = ts_sec - (ts_sec % 60)
    start = end - 60
    if start in idx:
        return idx[start]
    if end in idx:
        # only if start missing; still the bar that started at `end` is the *next*
        # minute — do not use it.
        pass
    for dt in (120, 180, 240):
        s = end - dt
        if s in idx:
            return idx[s]
    return None


def _returns(idx: dict[int, float], ts_sec: int) -> tuple[float | None, float | None, float | None, list[float]]:
    m = ts_sec - (ts_sec % 60)
    now = _spot_at(idx, m)
    p1 = _spot_at(idx, m - 60)
    p5 = _spot_at(idx, m - 300)
    r1 = (now / p1 - 1.0) if now and p1 and p1 > 0 else None
    r5 = (now / p5 - 1.0) if now and p5 and p5 > 0 else None
    closes = []
    for i in range(16, -1, -1):
        px = _spot_at(idx, m - i * 60)
        if px:
            closes.append(px)
    rvol = None
    if len(closes) >= 5:
        from pipeline import realized_vol_bar_std

        rvol = realized_vol_bar_std(closes)
    return r1, r5, rvol, closes


def load_cache(cache: Path) -> tuple[list[dict], dict[str, list], dict[str, dict[int, float]]]:
    markets = [m for m in load_jsonl(cache / "markets.jsonl") if m.get("ticker")]
    candles = {}
    cdir = cache / "candles"
    for m in markets:
        p = cdir / f"{m['ticker']}.json"
        if p.is_file():
            candles[m["ticker"]] = json.loads(p.read_text())
    spots = {}
    for series, product in PRODUCT.items():
        p = cache / f"spot_{product}.json"
        if p.is_file():
            spots[COIN[series]] = _spot_index(json.loads(p.read_text()))
    return markets, candles, spots


def _related_mids(markets_by_close: dict, candles: dict, series: str, close_ms: int, now_sec: int) -> float | None:
    others = []
    for s, by_close in markets_by_close.items():
        if s == series:
            continue
        # same 15m close, else nearest within 60s
        hit = by_close.get(close_ms) or by_close.get(close_ms - 1000) or by_close.get(close_ms + 1000)
        if not hit:
            continue
        rows = candles.get(hit["ticker"]) or []
        mid = None
        for r in rows:
            if r["end_ts"] <= now_sec and r.get("mid") is not None:
                mid = r["mid"]
        if mid is not None:
            others.append(mid)
    if not others:
        return None
    return sum(others) / len(others)


def decisions_for_market(m: dict, rows: list[dict], spots: dict[str, dict[int, float]], markets_by_close: dict, candles: dict, engine: DecisionEngine) -> list[Decision]:
    if not rows or not m.get("open_ms") or not m.get("close_ms"):
        return []
    open_ms = int(m["open_ms"])
    close_ms = int(m["close_ms"])
    coin = m.get("coin") or COIN.get(m["series"], "BTC")
    spot_idx = spots.get(coin, {})
    # chronological, only candles inside the window
    bars = sorted([r for r in rows if r.get("end_ts")], key=lambda r: r["end_ts"])
    out = []
    mids: list[float] = []
    times: list[int] = []
    vols: list[float] = []
    cum_vol = 0.0
    last_oi = m.get("open_interest_fp") or 0.0
    for r in bars:
        end_ts = int(r["end_ts"])
        now_ms = end_ts * 1000 if end_ts < 10_000_000_000 else end_ts
        now_sec = now_ms // 1000
        elapsed = int(round((now_ms - open_ms) / 60_000.0))
        if elapsed < 1 or elapsed > 13:
            # still push history if within window so minute 1 has context from elapsed 0
            mid0 = r.get("mid")
            if mid0 is not None and 0 <= elapsed <= 14 and now_ms <= close_ms:
                yb = (r.get("yes_bid") or {}).get("close")
                ya = (r.get("yes_ask") or {}).get("close")
                if yb is not None and ya is not None:
                    mid0 = 0.5 * (yb + ya)
                if usable(mid0) is not None or (mid0 is not None and 0.0 <= mid0 <= 1.0):
                    cum_vol += float(r.get("volume") or 0.0)
                    if r.get("oi") is not None:
                        last_oi = r["oi"]
                    mids.append(float(mid0))
                    times.append(now_ms)
                    vols.append(cum_vol)
            continue
        if now_ms > close_ms:
            break
        yb = (r.get("yes_bid") or {}).get("close")
        ya = (r.get("yes_ask") or {}).get("close")
        ya_hi = (r.get("yes_ask") or {}).get("high")
        yb_lo = (r.get("yes_bid") or {}).get("low")
        mid = r.get("mid")
        if yb is not None and ya is not None:
            mid = 0.5 * (yb + ya)
        if mid is None:
            continue
        mid = min(0.999, max(0.001, float(mid)))
        cum_vol += float(r.get("volume") or 0.0)
        if r.get("oi") is not None:
            last_oi = r["oi"]
        mids.append(mid)
        times.append(now_ms)
        vols.append(cum_vol)
        r1, r5, rvol, _ = _returns(spot_idx, now_sec)
        spot = _spot_at(spot_idx, now_sec)
        related = _related_mids(markets_by_close, candles, m["series"], close_ms, now_sec)
        d = engine.score_minute(
            ticker=m["ticker"],
            series=m["series"],
            coin=coin,
            result=m["result"],
            strike=m.get("floor_strike"),
            now_ms=now_ms,
            close_ms=close_ms,
            elapsed_min=elapsed,
            yes_bid=usable(yb) if yb is not None else None,
            yes_ask=usable(ya) if ya is not None else None,
            yes_ask_high=ya_hi,
            yes_bid_low=yb_lo,
            mid=mid,
            volume=cum_vol,
            oi=float(last_oi or 0.0),
            mids=list(mids),
            times=list(times),
            volumes=list(vols),
            spot=spot,
            ret_1m=r1,
            ret_5m=r5,
            rvol15=rvol,
            related_mid=related,
        )
        out.append(d)
    return out


def _fill_for(d: Decision, side: str, stress: bool = False) -> float | None:
    if stress:
        return d.fill_yes_stress if side == "YES" else d.fill_no_stress
    return d.fill_yes if side == "YES" else d.fill_no


def _place(d: Decision, strategy: str, side: str, split: str, stress: bool = False) -> Bet | None:
    ask = _fill_for(d, side, stress=stress)
    if ask is None:
        return None
    c, cost, fee = size_all_in(ask, STAKE_USD)
    if c <= 0:
        return None
    won = (d.result == "yes" and side == "YES") or (d.result == "no" and side == "NO")
    pnl = (c * 1.0 - cost) if won else -cost
    return Bet(
        strategy=strategy,
        ticker=d.ticker,
        series=d.series,
        coin=d.coin,
        day=_day(d.now_ms),
        now_ms=d.now_ms,
        elapsed_min=d.elapsed_min,
        tte_sec=d.tte_sec,
        tte_bucket=_tte_bucket(d.tte_sec),
        side=side,
        ask=ask,
        mid=d.mid,
        contracts=c,
        cost=cost,
        fee=fee,
        won=won,
        pnl=pnl,
        result=d.result,
        fair_yes=d.fair_yes,
        mlp_yes=d.mlp_yes,
        digital_fair=d.digital_fair,
        delta_pp=d.delta_pp,
        net_edge_pp=d.net_edge_pp,
        dist_bps=d.dist_bps,
        would_alert=d.would_alert,
        split=split,
    )


def _tte_bucket(tte_sec: int) -> str:
    # time-left at decision
    m = tte_sec / 60.0
    if m >= 12:
        return "14-12m"
    if m >= 9:
        return "11-9m"
    if m >= 6:
        return "8-6m"
    if m >= 3:
        return "5-3m"
    return "2-1m"


def _ask_bucket(ask: float) -> str:
    c = ask * 100.0
    if c <= 10:
        return "1-10c"
    if c <= 20:
        return "11-20c"
    if c <= 31:
        return "21-31c"
    if c <= 50:
        return "32-50c"
    return "51-99c"


def _dist_bucket(bps: float | None) -> str:
    if bps is None:
        return "unknown"
    if bps < 5:
        return "<5bp"
    if bps < 20:
        return "5-20bp"
    if bps < 50:
        return "20-50bp"
    return ">50bp"


def _rand_side(ticker: str) -> str:
    h = hashlib.sha256(ticker.encode()).hexdigest()
    return "YES" if int(h[:8], 16) % 2 == 0 else "NO"


def first_bet(decisions: list[Decision], strategy: str, split: str) -> Bet | None:
    for d in decisions:
        if strategy == "app_shipped":
            # shipped ticket side (hero/primary first). Qualifying = can fill $5 ticket.
            if not d.would_alert:
                continue
            return _place(d, strategy, d.app_side, split)
        if strategy == "app_shipped_stress":
            if not d.would_alert:
                continue
            return _place(d, strategy, d.app_side, split, stress=True)
        if strategy == "app_dirk":
            ask = _fill_for(d, d.app_side)
            if ask is None or not dirk_ok(ask):
                continue
            return _place(d, strategy, d.app_side, split)
        if strategy == "fair_dirk":
            ask = _fill_for(d, d.fv_side)
            if ask is None or not dirk_ok(ask):
                continue
            return _place(d, strategy, d.fv_side, split)
        if strategy == "cheap_side":
            y = d.fill_yes
            n = d.fill_no
            cands = []
            if y is not None and dirk_ok(y):
                cands.append(("YES", y))
            if n is not None and dirk_ok(n):
                cands.append(("NO", n))
            if not cands:
                continue
            side, _ = min(cands, key=lambda x: x[1])
            return _place(d, strategy, side, split)
        if strategy == "always_favorite":
            # favorite = higher mid (YES if mid>=0.5)
            side = "YES" if d.mid >= 0.5 else "NO"
            if _fill_for(d, side) is None:
                continue
            return _place(d, strategy, side, split)
        if strategy == "random_side":
            # Both close asks must be usable so skip-bias cannot inflate the average.
            if d.fill_yes is None or d.fill_no is None:
                continue
            side = _rand_side(d.ticker)
            return _place(d, strategy, side, split)
        if strategy == "ev_side":
            # Blended fair vs the candle-close asks (YES ask, 1 − YES bid).
            side, _, _ = ev_side(d.fair_yes, d.fill_yes, d.fill_no)
            if side is None:
                continue
            return _place(d, strategy, side, split)
        if strategy == "app_32_50":
            if not d.would_alert:
                continue
            ask = _fill_for(d, d.app_side)
            if ask is None or ask < 0.32 - 1e-12 or ask > 0.50 + 1e-12:
                continue
            return _place(d, strategy, d.app_side, split)
        if strategy == "app_tte_11_9":
            if not d.would_alert:
                continue
            if _tte_bucket(d.tte_sec) != "11-9m":
                continue
            return _place(d, strategy, d.app_side, split)
    return None


# --- Entry filter (EntryFilter.kt; docs/ml-review-2026-09-27.md #7) ----------
# app_entry = shipped pick (alert gate) + entry filter at the app defaults.
# entry_tuned = same, with (min elapsed, min bp) picked on IS days only by IS
# P&L (like tune_rule), then applied OOS. Override pp stays at the default.
ENTRY_STRATEGIES = ("app_entry", "entry_tuned")
ENTRY_SWEEP_ELAPSED = (0, 2, 3, 5)
ENTRY_SWEEP_BP = (0.0, 3.0, 5.0, 10.0)
ENTRY_MIN_IS_BETS = 20


def entry_ok(d: Decision, min_elapsed_min: int = ENTRY_MIN_ELAPSED_MIN, min_bp: float = ENTRY_MIN_STRIKE_BP, override_pp: float = ENTRY_NEAR_STRIKE_OVERRIDE_PP) -> bool:
    ok, _ = entry_filter(
        d.tte_sec,
        d.spot,
        d.strike,
        d.net_edge_pp,
        min_elapsed_min=min_elapsed_min,
        min_strike_bp=min_bp,
        override_pp=override_pp,
    )
    return ok


def first_bet_entry(
    decisions: list[Decision],
    split: str,
    min_elapsed_min: int = ENTRY_MIN_ELAPSED_MIN,
    min_bp: float = ENTRY_MIN_STRIKE_BP,
    override_pp: float = ENTRY_NEAR_STRIKE_OVERRIDE_PP,
    strategy: str = "app_entry",
) -> Bet | None:
    """App pick as shipped (alert gate) + entry filter; first qualifying minute only.

    Blocked minutes are skipped, so the bet moves to the first alert minute the
    filter allows — the same thing the app's alert gate does live.
    """
    for d in decisions:
        if not d.would_alert:
            continue
        if not entry_ok(d, min_elapsed_min, min_bp, override_pp):
            continue
        return _place(d, strategy, d.app_side, split)
    return None


def _light_summary(bets: list[Bet]) -> dict:
    """summarize() without the bootstrap (the sweep has 16 cells × 2 splits)."""
    if not bets:
        return dict(n=0, wins=0, win_rate=None, avg_ask=None, pnl=0.0, pnl_per_bet=None, roi=None)
    pnl = sum(b.pnl for b in bets)
    staked = sum(b.cost for b in bets)
    wins = sum(1 for b in bets if b.won)
    return dict(
        n=len(bets),
        wins=wins,
        win_rate=wins / len(bets),
        avg_ask=sum(b.ask for b in bets) / len(bets),
        pnl=pnl,
        pnl_per_bet=pnl / len(bets),
        roi=(pnl / staked) if staked else None,
    )


def run_entry_filter(market_decs: list[tuple[str, list[Decision]]]) -> dict:
    """app_entry at the app defaults, the (min elapsed × min bp) sweep, and entry_tuned.

    `market_decs` is (split, decisions) per market in chronological order — the
    same split assignment and order as the app_shipped bets, so rows compare 1:1.
    """

    def bets_for(split: str, me: int, bp: float, strategy: str) -> list[Bet]:
        out = []
        for spl, decs in market_decs:
            if spl != split:
                continue
            b = first_bet_entry(decs, spl, me, bp, strategy=strategy)
            if b:
                out.append(b)
        return out

    def both(me: int, bp: float, strategy: str) -> list[Bet]:
        return bets_for("is", me, bp, strategy) + bets_for("oos", me, bp, strategy)

    app_entry = both(ENTRY_MIN_ELAPSED_MIN, ENTRY_MIN_STRIKE_BP, "app_entry")
    grid = []
    best = None
    for me in ENTRY_SWEEP_ELAPSED:
        for bp in ENTRY_SWEEP_BP:
            is_s = _light_summary(bets_for("is", me, bp, "entry_sweep"))
            oos_s = _light_summary(bets_for("oos", me, bp, "entry_sweep"))
            cell = dict(min_elapsed_min=me, min_bp=bp, is_=is_s, oos=oos_s)
            grid.append(cell)
            if is_s["n"] < ENTRY_MIN_IS_BETS:
                continue
            if best is None or is_s["pnl"] > best["is_"]["pnl"]:
                best = cell
    rule = {}
    tuned: list[Bet] = []
    if best is not None:
        rule = dict(
            min_elapsed_min=best["min_elapsed_min"],
            min_bp=best["min_bp"],
            override_pp=ENTRY_NEAR_STRIKE_OVERRIDE_PP,
            is_n=best["is_"]["n"],
            is_pnl=best["is_"]["pnl"],
            is_pnl_per_bet=best["is_"]["pnl_per_bet"],
        )
        tuned = both(rule["min_elapsed_min"], rule["min_bp"], "entry_tuned")
    return dict(
        bets={"app_entry": app_entry, "entry_tuned": tuned},
        rule=rule,
        grid=grid,
        defaults=dict(
            min_elapsed_min=ENTRY_MIN_ELAPSED_MIN,
            min_bp=ENTRY_MIN_STRIKE_BP,
            override_pp=ENTRY_NEAR_STRIKE_OVERRIDE_PP,
        ),
    )


def split_days(days: list[str]) -> tuple[set[str], set[str]]:
    days = sorted(days)
    if not days:
        return set(), set()
    cut = max(1, int(round(len(days) * 2 / 3)))
    # keep last third OOS
    if cut >= len(days):
        cut = len(days) - 1
    return set(days[:cut]), set(days[cut:])


def bootstrap_ci(pnls: list[float], n: int = 8000, seed: int = 7) -> tuple[float, float, float]:
    if not pnls:
        return float("nan"), float("nan"), float("nan")
    rng = random.Random(seed)
    means = []
    k = len(pnls)
    for _ in range(n):
        s = 0.0
        for _i in range(k):
            s += pnls[rng.randrange(k)]
        means.append(s / k)
    means.sort()
    lo = means[int(0.025 * (n - 1))]
    hi = means[int(0.975 * (n - 1))]
    return sum(pnls) / k, lo, hi


def max_dd(pnls: list[float]) -> float:
    peak = 0.0
    eq = 0.0
    dd = 0.0
    for x in pnls:
        eq += x
        peak = max(peak, eq)
        dd = min(dd, eq - peak)
    return dd


def summarize(bets: list[Bet]) -> dict:
    if not bets:
        return dict(n=0, wins=0, win_rate=None, avg_ask=None, pnl=0.0, pnl_per_bet=None, roi=None, max_dd=0.0, ci95=(None, None, None), staked=0.0)
    pnls = [b.pnl for b in bets]
    staked = sum(b.cost for b in bets)
    wins = sum(1 for b in bets if b.won)
    mu, lo, hi = bootstrap_ci(pnls)
    return dict(
        n=len(bets),
        wins=wins,
        win_rate=wins / len(bets),
        avg_ask=sum(b.ask for b in bets) / len(bets),
        pnl=sum(pnls),
        pnl_per_bet=mu,
        roi=(sum(pnls) / staked) if staked else None,
        max_dd=max_dd(pnls),
        ci95=(mu, lo, hi),
        staked=staked,
    )


def breakdown(bets: list[Bet], keyfn) -> dict[str, dict]:
    g = defaultdict(list)
    for b in bets:
        g[keyfn(b)].append(b)
    return {k: summarize(v) for k, v in sorted(g.items())}


def brier_logloss(pairs: list[tuple[float, int]]) -> dict:
    if not pairs:
        return dict(n=0, brier=None, logloss=None)
    b = 0.0
    ll = 0.0
    for p, y in pairs:
        p = min(1.0 - 1e-6, max(1e-6, p))
        b += (p - y) ** 2
        ll += -(y * math.log(p) + (1 - y) * math.log(1 - p))
    return dict(n=len(pairs), brier=b / len(pairs), logloss=ll / len(pairs))


def reliability(pairs: list[tuple[float, int]], bins: int = 10) -> list[dict]:
    edges = [i / bins for i in range(bins + 1)]
    rows = []
    for i in range(bins):
        lo, hi = edges[i], edges[i + 1]
        grp = [(p, y) for p, y in pairs if (p >= lo and (p < hi or (i == bins - 1 and p <= hi)))]
        if not grp:
            rows.append(dict(bin=f"{lo:.1f}-{hi:.1f}", n=0, pred=None, obs=None))
            continue
        pred = sum(p for p, _ in grp) / len(grp)
        obs = sum(y for _, y in grp) / len(grp)
        rows.append(dict(bin=f"{lo:.1f}-{hi:.1f}", n=len(grp), pred=pred, obs=obs))
    return rows


def tune_rule(is_decisions: dict[str, list[Decision]]) -> dict:
    """Tune (coin, tte window, max ask, min |edge|) on IS only. First-qualifying, no look-ahead."""
    coins = (None, "BTC", "ETH", "SOL")
    tte_windows = (None, "14-12m", "11-9m", "8-6m", "5-3m", "2-1m")
    max_asks = (0.20, 0.31, 0.40, 0.50)
    min_edges = (0.0, 3.0, 5.0, 8.0)
    best = None
    for coin in coins:
        for tte in tte_windows:
            for max_ask in max_asks:
                for min_edge in min_edges:
                    bets = []
                    for decs in is_decisions.values():
                        for d in decs:
                            if coin and d.coin != coin:
                                continue
                            if tte and _tte_bucket(d.tte_sec) != tte:
                                continue
                            if abs(d.net_edge_pp) < min_edge:
                                continue
                            ask = _fill_for(d, d.app_side)
                            if ask is None or ask > max_ask + 1e-9:
                                continue
                            if not dirk_ok(ask):
                                continue
                            b = _place(d, "tuned", d.app_side, "is")
                            if b:
                                bets.append(b)
                                break
                    if len(bets) < 20:
                        continue
                    s = summarize(bets)
                    score = s["pnl"]
                    rec = dict(coin=coin, tte=tte, max_ask=max_ask, min_edge=min_edge, **s)
                    if best is None or score > best["pnl"]:
                        best = rec
    return best or {}


def _percentile(xs: list[float], q: float) -> float | None:
    if not xs:
        return None
    ys = sorted(xs)
    if len(ys) == 1:
        return ys[0]
    idx = q * (len(ys) - 1)
    lo = int(idx)
    hi = min(lo + 1, len(ys) - 1)
    frac = idx - lo
    return ys[lo] * (1.0 - frac) + ys[hi] * frac


def _price_bucket_10c(ask: float) -> str:
    c = max(0, min(99, int(math.floor(ask * 100.0))))
    lo = (c // 10) * 10
    return f"{lo}-{lo + 10}c"


def _wr_vs_price(rows: list[tuple[float, bool]]) -> list[dict]:
    g: dict[str, list[tuple[float, bool]]] = defaultdict(list)
    for ask, won in rows:
        g[_price_bucket_10c(ask)].append((ask, won))
    out = []
    for k in sorted(g):
        items = g[k]
        n = len(items)
        wr = sum(1 for _, w in items if w) / n
        avg = sum(a for a, _ in items) / n
        out.append(dict(bucket=k, n=n, win_rate=wr, avg_ask=avg, wr_minus_ask=wr - avg))
    return out


def _tracking_mae(rows: list[dict], min_n: int = 30) -> tuple[float | None, bool]:
    weighted = [(r["n"], abs(r["wr_minus_ask"])) for r in rows if r["n"] >= min_n and r.get("wr_minus_ask") is not None]
    if not weighted:
        return None, False
    tot = sum(n for n, _ in weighted)
    mae = sum(n * a for n, a in weighted) / tot
    return mae, mae <= 0.12


def sanity_checks(all_decisions: list[Decision], bets: dict[str, list[Bet]]) -> dict:
    sums: list[float] = []
    yes_asks: list[float] = []
    no_asks: list[float] = []
    rand_asks: list[float] = []
    fav_pairs: list[tuple[float, bool]] = []
    cheap_pairs: list[tuple[float, bool]] = []
    for d in all_decisions:
        y, n = d.fill_yes, d.fill_no
        if y is None or n is None:
            continue
        sums.append(y + n)
        yes_asks.append(y)
        no_asks.append(n)
        side = _rand_side(d.ticker)
        rand_asks.append(y if side == "YES" else n)
        fav = "YES" if d.mid >= 0.5 else "NO"
        fav_ask = y if fav == "YES" else n
        fav_won = (d.result == "yes" and fav == "YES") or (d.result == "no" and fav == "NO")
        fav_pairs.append((fav_ask, fav_won))
        cheap = "YES" if y <= n else "NO"
        cheap_ask = y if cheap == "YES" else n
        cheap_won = (d.result == "yes" and cheap == "YES") or (d.result == "no" and cheap == "NO")
        cheap_pairs.append((cheap_ask, cheap_won))

    fav_rows = _wr_vs_price([(b.ask, b.won) for b in (bets.get("always_favorite") or [])])
    cheap_rows = _wr_vs_price([(b.ask, b.won) for b in (bets.get("cheap_side") or [])])
    fav_all = _wr_vs_price(fav_pairs)
    cheap_all = _wr_vs_price(cheap_pairs)
    fav_mae, fav_ok = _tracking_mae(fav_rows)
    cheap_mae, cheap_ok = _tracking_mae(cheap_rows)
    fav_all_mae, fav_all_ok = _tracking_mae(fav_all)
    cheap_all_mae, cheap_all_ok = _tracking_mae(cheap_all)

    median_sum = _percentile(sums, 0.5)
    p95_sum = _percentile(sums, 0.95)
    rand_avg = (sum(rand_asks) / len(rand_asks)) if rand_asks else None
    rand_bets = bets.get("random_side") or []
    rand_bet_avg = (sum(b.ask for b in rand_bets) / len(rand_bets)) if rand_bets else None

    checks = [
        dict(
            name="yes_plus_no_median",
            value=median_sum,
            expect="0.99–1.12 (99–112¢)",
            passed=median_sum is not None and 0.99 <= median_sum <= 1.12,
        ),
        dict(
            name="yes_plus_no_p95",
            value=p95_sum,
            expect="≤ 1.20",
            passed=p95_sum is not None and p95_sum <= 1.20,
        ),
        dict(
            name="random_side_avg_ask_all_minutes",
            value=rand_avg,
            expect="0.50–0.53",
            passed=rand_avg is not None and 0.50 <= rand_avg <= 0.53,
        ),
        dict(
            name="random_side_strategy_avg_ask",
            value=rand_bet_avg,
            expect="0.50–0.53",
            passed=rand_bet_avg is not None and 0.50 <= rand_bet_avg <= 0.53,
        ),
        dict(
            name="favorite_wr_tracks_price",
            value=fav_mae,
            expect="mean |win% − avg ask| ≤ 12pp on n≥30 buckets",
            passed=fav_ok,
        ),
        dict(
            name="cheap_wr_tracks_price",
            value=cheap_mae,
            expect="mean |win% − avg ask| ≤ 12pp on n≥30 buckets",
            passed=cheap_ok,
        ),
        dict(
            name="favorite_all_minutes_wr_tracks_price",
            value=fav_all_mae,
            expect="mean |win% − avg ask| ≤ 12pp on n≥30 buckets",
            passed=fav_all_ok,
        ),
        dict(
            name="cheap_all_minutes_wr_tracks_price",
            value=cheap_all_mae,
            expect="mean |win% − avg ask| ≤ 12pp on n≥30 buckets",
            passed=cheap_all_ok,
        ),
    ]
    return dict(
        n_minutes_both_asks=len(sums),
        yes_plus_no=dict(
            n=len(sums),
            median=median_sum,
            p95=p95_sum,
            mean=(sum(sums) / len(sums)) if sums else None,
        ),
        yes_ask=dict(median=_percentile(yes_asks, 0.5), p95=_percentile(yes_asks, 0.95)),
        no_ask=dict(median=_percentile(no_asks, 0.5), p95=_percentile(no_asks, 0.95)),
        random_side_avg_ask=rand_avg,
        random_side_strategy_avg_ask=rand_bet_avg,
        checks=checks,
        passed=all(c["passed"] for c in checks),
        favorite_by_10c=fav_rows,
        cheap_by_10c=cheap_rows,
        favorite_all_minutes_by_10c=fav_all,
        cheap_all_minutes_by_10c=cheap_all,
    )


def settlement_audit(markets: list[dict], spots: dict[str, dict[int, float]], n: int = 20, seed: int = 25) -> dict:
    rng = random.Random(seed)
    eligible = [m for m in markets if m.get("floor_strike") and m.get("close_ms") and m.get("result") in ("yes", "no")]
    sample = rng.sample(eligible, min(n, len(eligible))) if eligible else []
    rows = []
    agree = disagree = missing = 0
    for m in sample:
        coin = m.get("coin") or COIN.get(m["series"], "BTC")
        idx = spots.get(coin, {})
        close_sec = int(m["close_ms"] / 1000)
        spot = _spot_at(idx, close_sec)
        strike = float(m["floor_strike"])
        result = m["result"]
        up = None if spot is None else (spot > strike)
        yes_is_up = None
        if up is None:
            missing += 1
        else:
            yes_is_up = (result == "yes") == up
            if yes_is_up:
                agree += 1
            else:
                disagree += 1
        rows.append(
            dict(
                ticker=m["ticker"],
                strike=strike,
                result=result,
                coinbase_close=spot,
                close_time=datetime.fromtimestamp(close_sec, tz=timezone.utc).isoformat(),
                coinbase_gt_strike=up,
                result_yes_means_up=yes_is_up,
            )
        )
    overall_agree = overall_n = 0
    for m in eligible:
        coin = m.get("coin") or COIN.get(m["series"], "BTC")
        spot = _spot_at(spots.get(coin, {}), int(m["close_ms"] / 1000))
        if spot is None:
            continue
        overall_n += 1
        if (m["result"] == "yes") == (spot > float(m["floor_strike"])):
            overall_agree += 1
    rate = (agree / (agree + disagree)) if (agree + disagree) else None
    overall_rate = (overall_agree / overall_n) if overall_n else None
    return dict(
        sample=rows,
        sample_agree=agree,
        sample_disagree=disagree,
        sample_missing=missing,
        sample_agree_rate=rate,
        overall_n=overall_n,
        overall_agree=overall_agree,
        overall_agree_rate=overall_rate,
        note=(
            "Coinbase 1m close is a proxy for Kalshi's settlement index; a few mismatches are expected. "
            "result=yes must mean UP (close > strike)."
        ),
        passed=(rate is not None and rate >= 0.85) and (overall_rate is not None and overall_rate >= 0.85),
    )


def apply_rule(decisions_by_ticker: dict[str, list[Decision]], rule: dict, split: str) -> list[Bet]:
    if not rule:
        return []
    bets = []
    for decs in decisions_by_ticker.values():
        for d in decs:
            if rule.get("coin") and d.coin != rule["coin"]:
                continue
            if rule.get("tte") and _tte_bucket(d.tte_sec) != rule["tte"]:
                continue
            if abs(d.net_edge_pp) < (rule.get("min_edge") or 0):
                continue
            ask = _fill_for(d, d.app_side)
            if ask is None or ask > (rule.get("max_ask") or 1) + 1e-9:
                continue
            if not dirk_ok(ask):
                continue
            b = _place(d, "tuned_oos", d.app_side, split)
            if b:
                bets.append(b)
                break
    return bets


def run_sim(cache: Path) -> dict:
    markets, candles, spots = load_cache(cache)
    markets = [m for m in markets if m.get("result") in ("yes", "no") and m["ticker"] in candles]
    days = sorted({_day(m["close_ms"]) for m in markets if m.get("close_ms")})
    is_days, oos_days = split_days(days)
    markets_by_close: dict[str, dict[int, dict]] = defaultdict(dict)
    for m in markets:
        markets_by_close[m["series"]][int(m["close_ms"])] = m

    engine = DecisionEngine()
    # process in time order so related mids exist
    markets_sorted = sorted(markets, key=lambda m: (m.get("open_ms") or 0, m["ticker"]))
    decisions_by: dict[str, list[Decision]] = {}
    all_decisions: list[Decision] = []
    for i, m in enumerate(markets_sorted):
        decs = decisions_for_market(m, candles.get(m["ticker"]) or [], spots, markets_by_close, candles, engine)
        decisions_by[m["ticker"]] = decs
        all_decisions.extend(decs)
        if (i + 1) % 200 == 0:
            print(f"[sim] scored {i+1}/{len(markets_sorted)} markets")

    def split_of(m) -> str:
        return "is" if _day(m["close_ms"]) in is_days else "oos"

    bets: dict[str, list[Bet]] = {s: [] for s in ALL_STRATEGIES}
    for m in markets_sorted:
        decs = decisions_by.get(m["ticker"]) or []
        if not decs:
            continue
        spl = split_of(m)
        for s in STRATEGIES + STRESS_STRATEGIES:
            b = first_bet(decs, s, spl)
            if b:
                bets[s].append(b)
        if spl == "oos":
            for s in EXPLORATORY_STRATEGIES:
                b = first_bet(decs, s, spl)
                if b:
                    bets[s].append(b)

    is_decs = {t: d for t, d in decisions_by.items() if d and _day(d[0].now_ms) in is_days}
    oos_decs = {t: d for t, d in decisions_by.items() if d and _day(d[0].now_ms) in oos_days}
    rule = tune_rule(is_decs)
    tuned_is = apply_rule(is_decs, rule, "is")
    tuned_oos = apply_rule(oos_decs, rule, "oos")
    bets["tuned"] = tuned_is + tuned_oos

    # Entry filter (#7): same markets, split and order as app_shipped.
    entry = run_entry_filter([(split_of(m), decisions_by.get(m["ticker"]) or []) for m in markets_sorted])
    bets.update(entry["bets"])

    # calibration on every decision minute (no selection) — model vs market mid
    def pairs(getp):
        out = []
        for d in all_decisions:
            y = 1 if d.result == "yes" else 0
            out.append((getp(d), y))
        return out

    cal_model = brier_logloss(pairs(lambda d: d.fair_yes))
    cal_mlp = brier_logloss(pairs(lambda d: d.mlp_yes))
    cal_mkt = brier_logloss(pairs(lambda d: d.mid))
    cal_dig = brier_logloss([(d.digital_fair / 100.0, 1 if d.result == "yes" else 0) for d in all_decisions if d.digital_fair is not None])
    rel_model = reliability(pairs(lambda d: d.fair_yes))
    rel_mkt = reliability(pairs(lambda d: d.mid))

    # scorecard diagnosis numbers on first alert per market (mirrors log throttle-ish)
    scored = []
    for decs in decisions_by.values():
        hit = next((d for d in decs if d.would_alert), None)
        if not hit:
            continue
        pred_yes_side = hit.app_side == "YES"
        actual_yes = hit.result == "yes"
        scored.append(
            dict(
                side_hit=pred_yes_side == actual_yes,
                brier_yes=(hit.fair_yes - (1.0 if actual_yes else 0.0)) ** 2,
                brier_side=((hit.fair_yes if pred_yes_side else 1.0 - hit.fair_yes) - (1.0 if pred_yes_side == actual_yes else 0.0)) ** 2,
            )
        )

    def pack(strategy: str) -> dict:
        allb = bets[strategy]
        oos = [b for b in allb if b.split == "oos"]
        is_ = [b for b in allb if b.split == "is"]
        return dict(
            all=summarize(allb),
            is_=summarize(is_),
            oos=summarize(oos),
            oos_by_coin=breakdown(oos, lambda b: b.coin),
            oos_by_tte=breakdown(oos, lambda b: b.tte_bucket),
            oos_by_ask=breakdown(oos, lambda b: _ask_bucket(b.ask)),
            oos_by_dist=breakdown(oos, lambda b: _dist_bucket(b.dist_bps)),
            oos_equity=[b.pnl for b in oos],
        )

    sanity = sanity_checks(all_decisions, bets)
    settle = settlement_audit(markets, spots)
    print(
        f"[sanity] passed={sanity['passed']} yes+no median={sanity['yes_plus_no'].get('median')} "
        f"p95={sanity['yes_plus_no'].get('p95')} random_ask={sanity.get('random_side_avg_ask')}"
    )
    print(
        f"[settlement] passed={settle['passed']} sample {settle['sample_agree']}/{settle['sample_agree']+settle['sample_disagree']} "
        f"overall {settle['overall_agree']}/{settle['overall_n']}"
    )

    result = dict(
        n_markets=len(markets),
        n_with_candles=sum(1 for m in markets if candles.get(m["ticker"])),
        n_decisions=len(all_decisions),
        days=days,
        is_days=sorted(is_days),
        oos_days=sorted(oos_days),
        by_coin={c: sum(1 for m in markets if m.get("coin") == c) for c in ("BTC", "ETH", "SOL")},
        span=(
            datetime.fromtimestamp(min(m["open_ms"] for m in markets) / 1000, tz=timezone.utc).isoformat() if markets else None,
            datetime.fromtimestamp(max(m["close_ms"] for m in markets) / 1000, tz=timezone.utc).isoformat() if markets else None,
        ),
        strategies={s: pack(s) for s in list(ALL_STRATEGIES) + ["tuned"]},
        tuned_rule=rule,
        sanity=sanity,
        settlement=settle,
        calibration=dict(model=cal_model, mlp=cal_mlp, market=cal_mkt, digital=cal_dig, reliability_model=rel_model, reliability_market=rel_mkt),
        scorecard_shadow=dict(
            n=len(scored),
            side_hits=sum(1 for s in scored if s["side_hit"]),
            mean_brier_yes=sum(s["brier_yes"] for s in scored) / len(scored) if scored else None,
            mean_brier_side=sum(s["brier_side"] for s in scored) / len(scored) if scored else None,
        ),
    )
    result["strategies"].update({s: pack(s) for s in ENTRY_STRATEGIES})
    result["entry_filter"] = dict(defaults=entry["defaults"], rule=entry["rule"], grid=entry["grid"])
    (cache / "sim_result.json").write_text(json.dumps(result, default=str))
    return result


if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser()
    p.add_argument("--cache", default=str(Path(__file__).parent / "cache"))
    args = p.parse_args()
    r = run_sim(Path(args.cache))
    print("markets", r["n_markets"], "decisions", r["n_decisions"], "days", r["days"][:3], "...", r["days"][-3:])
    for s, pack in r["strategies"].items():
        o = pack["oos"]
        print(s, "oos n", o["n"], "pnl", o["pnl"], "wr", o["win_rate"], "ci", o["ci95"])
