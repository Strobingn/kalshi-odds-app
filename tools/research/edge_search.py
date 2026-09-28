#!/usr/bin/env python3
"""Pre-registered strategy search on settled Kalshi 15m crypto markets.

Runs on the backtest cache (tools/backtest/cache, same format as
tools/backtest/fetch.py). Five hypothesis families, each with a small grid:

  H1 gbm_ev       LightGBM residual model: init_score = logit(mid), learns
                  corrections from spot / book features; bet the side whose
                  p − ask − fee clears a margin.
  H2 late_fav     Late favorite: buy the side spot is on when little time is
                  left, spot is several σ from the strike and the ask is in a
                  band.
  H3 spot_lag     Spot moved ≥ k σ in the last minute but the Kalshi mid lags
                  the option-implied fair by ≥ g: buy the spot side.
  H4 fade_jump    Kalshi mid jumped ≥ j in a minute with no matching spot
                  move: buy the other side.
  H5 digital_ev   Φ(d2) option fair with σ scaled by s; EV-at-ask rule.

Protocol (fixed before looking at results):
  * Split by UTC day: the last OOS_DAYS days are out of sample (OOS).
  * Each family's parameters are picked on in-sample (IS) days only, by IS
    P&L with at least MIN_IS_BETS bets. H1 picks its margin on the last
    30% of IS days after training on the first 70%, then retrains on all IS.
  * One bet per market: the first minute that qualifies. $5 all-in with the
    exact Kalshi taker fee (tools/backtest/pipeline.py).
  * Primary fill = candle-close ask; stress fill = worse of close/high.
  * Asks < MIN_ASK are excluded: on recorded order books, sub-10¢ asks were
    rarely fillable and lost (see docs/ml-review-2026-09-27.md).
  * OOS CIs are a day-block bootstrap. With 5 families, only a 99% CI that
    excludes 0 counts (≈ Bonferroni on 5 tests at 5%).

Prints a Markdown report; exit code 0 regardless of results.
"""
from __future__ import annotations

import argparse
import math
import random
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "backtest"))

from pipeline import (  # noqa: E402
    STAKE_USD,
    fee_per_contract,
    p_finish_above,
    realized_vol_bar_std,
    sigma_annual_from_bar_std,
    size_all_in,
    usable,
)
from simulate import _spot_at, load_cache  # noqa: E402

OOS_DAYS = 10
MIN_IS_BETS = 50
MIN_ASK = 0.10
MAX_ASK = 0.97
COIN_ID = {"BTC": 0, "ETH": 1, "SOL": 2}


@dataclass
class Row:
    ticker: str
    coin: str
    day: str
    close_ms: int
    elapsed: int
    tte: float
    y: int
    mid: float
    ya: float | None
    na: float | None
    ya_stress: float | None
    na_stress: float | None
    spot: float | None
    strike: float | None
    z: float | None          # ln(S/K) / (σ_bar √(tte/60))
    sig_bar: float | None    # 1-minute log-return std
    r1: float | None
    r5: float | None
    r15: float | None
    mid_d1: float | None
    mid_d3: float | None
    spread: float | None
    vol_min: float
    hour: int
    dow: int
    feats: list[float] = field(default_factory=list)


def _day(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def _ret(idx: dict[int, float], now_sec: int, back: int) -> float | None:
    m = now_sec - (now_sec % 60)
    a = _spot_at(idx, m)
    b = _spot_at(idx, m - back)
    return (a / b - 1.0) if a and b and b > 0 else None


def build_rows(cache: Path) -> list[Row]:
    markets, candles, spots = load_cache(cache)
    rows: list[Row] = []
    for m in markets:
        if m.get("result") not in ("yes", "no") or not m.get("open_ms") or not m.get("close_ms"):
            continue
        bars = sorted([r for r in candles.get(m["ticker"], []) if r.get("end_ts")], key=lambda r: r["end_ts"])
        if not bars:
            continue
        coin = m.get("coin") or "BTC"
        idx = spots.get(coin, {})
        open_ms, close_ms = int(m["open_ms"]), int(m["close_ms"])
        strike = m.get("floor_strike")
        mids: list[float] = []
        for r in bars:
            now_ms = int(r["end_ts"]) * 1000
            if now_ms > close_ms:
                break
            yb = (r.get("yes_bid") or {}).get("close")
            ya = (r.get("yes_ask") or {}).get("close")
            mid = 0.5 * (yb + ya) if yb is not None and ya is not None else r.get("mid")
            if mid is None:
                continue
            mid = min(0.999, max(0.001, float(mid)))
            mids.append(mid)
            elapsed = int(round((now_ms - open_ms) / 60_000.0))
            if elapsed < 1 or elapsed > 13:
                continue
            now_sec = now_ms // 1000
            tte = max(1.0, (close_ms - now_ms) / 1000.0)
            ya_u = usable(ya) if ya is not None else None
            na_u = usable(1.0 - yb) if yb is not None else None
            ya_hi = (r.get("yes_ask") or {}).get("high")
            yb_lo = (r.get("yes_bid") or {}).get("low")
            ya_s = usable(max(ya, ya_hi)) if ya is not None and ya_hi is not None else ya_u
            na_s = usable(max(1.0 - yb, 1.0 - yb_lo)) if yb is not None and yb_lo is not None else na_u
            spot = _spot_at(idx, now_sec)
            closes = []
            base = now_sec - (now_sec % 60)
            for i in range(30, -1, -1):
                px = _spot_at(idx, base - i * 60)
                if px:
                    closes.append(px)
            sig = realized_vol_bar_std(closes) if len(closes) >= 10 else None
            z = None
            if spot and strike and sig and sig > 0:
                z = math.log(spot / strike) / (sig * math.sqrt(tte / 60.0))
            dt = datetime.fromtimestamp(now_sec, tz=timezone.utc)
            rows.append(
                Row(
                    ticker=m["ticker"],
                    coin=coin,
                    day=_day(now_ms),
                    close_ms=close_ms,
                    elapsed=elapsed,
                    tte=tte,
                    y=1 if m["result"] == "yes" else 0,
                    mid=mid,
                    ya=ya_u,
                    na=na_u,
                    ya_stress=ya_s,
                    na_stress=na_s,
                    spot=spot,
                    strike=strike,
                    z=z,
                    sig_bar=sig,
                    r1=_ret(idx, now_sec, 60),
                    r5=_ret(idx, now_sec, 300),
                    r15=_ret(idx, now_sec, 900),
                    mid_d1=(mids[-1] - mids[-2]) if len(mids) >= 2 else None,
                    mid_d3=(mids[-1] - mids[-4]) if len(mids) >= 4 else None,
                    spread=(ya - yb) if ya is not None and yb is not None else None,
                    vol_min=float(r.get("volume") or 0.0),
                    hour=dt.hour,
                    dow=dt.weekday(),
                )
            )
    rows.sort(key=lambda r: (r.close_ms, r.elapsed))
    return rows


# --- betting ------------------------------------------------------------------

@dataclass
class Bet:
    ticker: str
    day: str
    side: str
    ask: float
    won: bool
    pnl: float
    pnl_stress: float | None


def _pnl(ask: float | None, won: bool) -> float | None:
    if ask is None:
        return None
    c, cost, _ = size_all_in(ask, STAKE_USD)
    if c <= 0:
        return None
    return (c - cost) if won else -cost


def place(r: Row, side: str) -> Bet | None:
    ask = r.ya if side == "YES" else r.na
    if ask is None or not (MIN_ASK <= ask <= MAX_ASK):
        return None
    won = (r.y == 1) == (side == "YES")
    pnl = _pnl(ask, won)
    if pnl is None:
        return None
    stress = _pnl(r.ya_stress if side == "YES" else r.na_stress, won)
    return Bet(r.ticker, r.day, side, ask, won, pnl, stress)


def first_bets(rows: list[Row], rule) -> list[Bet]:
    """rule(row) -> 'YES' | 'NO' | None. One bet per market, first qualifying minute."""
    done: set[str] = set()
    out = []
    for r in rows:
        if r.ticker in done:
            continue
        side = rule(r)
        if side is None:
            continue
        b = place(r, side)
        if b is None:
            continue
        done.add(r.ticker)
        out.append(b)
    return out


def ev_rule(p: float, r: Row, margin: float) -> str | None:
    best, side = margin, None
    if r.ya is not None and MIN_ASK <= r.ya <= MAX_ASK:
        ev = p - r.ya - fee_per_contract(r.ya)
        if ev > best:
            best, side = ev, "YES"
    if r.na is not None and MIN_ASK <= r.na <= MAX_ASK:
        ev = (1.0 - p) - r.na - fee_per_contract(r.na)
        if ev > best:
            best, side = ev, "NO"
    return side


def spot_side(r: Row) -> str | None:
    if r.spot is None or r.strike is None or r.spot == r.strike:
        return None
    return "YES" if r.spot > r.strike else "NO"


# --- stats ----------------------------------------------------------------------

def summary(bets: list[Bet]) -> dict:
    n = len(bets)
    if n == 0:
        return {"n": 0}
    pnl = sum(b.pnl for b in bets)
    stress = [b.pnl_stress for b in bets if b.pnl_stress is not None]
    return {
        "n": n,
        "win": sum(b.won for b in bets) / n,
        "ask": sum(b.ask for b in bets) / n,
        "pnl": pnl,
        "per": pnl / n,
        "stress_pnl": sum(stress) if stress else None,
    }


def day_block_ci(bets: list[Bet], level: float, iters: int = 4000, seed: int = 11) -> tuple[float, float]:
    by_day: dict[str, list[float]] = defaultdict(list)
    for b in bets:
        by_day[b.day].append(b.pnl)
    days = list(by_day)
    if len(days) < 2:
        return float("nan"), float("nan")
    rng = random.Random(seed)
    means = []
    for _ in range(iters):
        tot, n = 0.0, 0
        for _d in days:
            xs = by_day[days[rng.randrange(len(days))]]
            tot += sum(xs)
            n += len(xs)
        if n:
            means.append(tot / n)
    means.sort()
    a = (1.0 - level) / 2.0
    return means[int(a * (len(means) - 1))], means[int((1.0 - a) * (len(means) - 1))]


# --- hypotheses -----------------------------------------------------------------

def digital_p(r: Row, scale: float = 1.0) -> float | None:
    if r.spot is None or r.strike is None or r.sig_bar is None or r.sig_bar <= 0:
        return None
    return p_finish_above(r.spot, r.strike, r.tte, sigma_annual_from_bar_std(r.sig_bar) * scale)


def grid_pick(is_rows: list[Row], grid: list[dict], make_rule) -> tuple[dict | None, dict]:
    best, best_s = None, {"n": 0}
    for params in grid:
        s = summary(first_bets(is_rows, make_rule(**params)))
        if s["n"] < MIN_IS_BETS:
            continue
        if best is None or s["pnl"] > best_s["pnl"]:
            best, best_s = params, s
    return best, best_s


def h2_rule(tmax: float, lo: float, hi: float, zmin: float):
    def rule(r: Row) -> str | None:
        if r.tte > tmax or r.z is None or abs(r.z) < zmin:
            return None
        side = "YES" if r.z > 0 else "NO"
        ask = r.ya if side == "YES" else r.na
        return side if ask is not None and lo <= ask <= hi else None
    return rule


def h3_rule(k: float, g: float):
    def rule(r: Row) -> str | None:
        if r.r1 is None or r.sig_bar is None or r.sig_bar <= 0:
            return None
        move = r.r1 / r.sig_bar
        p = digital_p(r)
        if p is None or abs(move) < k:
            return None
        if move > 0 and p - r.mid >= g:
            return "YES"
        if move < 0 and r.mid - p >= g:
            return "NO"
        return None
    return rule


def h4_rule(j: float):
    def rule(r: Row) -> str | None:
        if r.mid_d1 is None or r.r1 is None or r.sig_bar is None or r.sig_bar <= 0:
            return None
        if abs(r.r1 / r.sig_bar) >= 0.5:
            return None
        if r.mid_d1 >= j:
            return "NO"
        if r.mid_d1 <= -j:
            return "YES"
        return None
    return rule


def h5_rule(scale: float, margin: float):
    def rule(r: Row) -> str | None:
        p = digital_p(r, scale)
        return ev_rule(p, r, margin) if p is not None else None
    return rule


FEATURES = [
    "z", "digital_minus_mid", "r1_sig", "r5_sig", "r15_sig", "mid_d1", "mid_d3",
    "spread", "log_vol_min", "tte_frac", "hour", "dow", "coin", "sig_bar_bp", "mid",
]


def feats(r: Row) -> list[float]:
    nan = float("nan")
    s = r.sig_bar if r.sig_bar and r.sig_bar > 0 else None
    dp = digital_p(r)
    return [
        r.z if r.z is not None else nan,
        (dp - r.mid) if dp is not None else nan,
        (r.r1 / s) if r.r1 is not None and s else nan,
        (r.r5 / s) if r.r5 is not None and s else nan,
        (r.r15 / s) if r.r15 is not None and s else nan,
        r.mid_d1 if r.mid_d1 is not None else nan,
        r.mid_d3 if r.mid_d3 is not None else nan,
        r.spread if r.spread is not None else nan,
        math.log1p(max(0.0, r.vol_min)),
        r.tte / 900.0,
        float(r.hour),
        float(r.dow),
        float(COIN_ID.get(r.coin, 3)),
        (s * 1e4) if s else nan,
        r.mid,
    ]


def logit(p: float) -> float:
    p = min(1 - 1e-3, max(1e-3, p))
    return math.log(p / (1 - p))


def h1_fit_predict(train: list[Row], test: list[Row]):
    import lightgbm as lgb  # noqa: WPS433
    import numpy as np

    Xtr = np.array([feats(r) for r in train])
    ytr = np.array([r.y for r in train])
    otr = np.array([logit(r.mid) for r in train])
    Xte = np.array([feats(r) for r in test])
    ote = np.array([logit(r.mid) for r in test])
    ds = lgb.Dataset(Xtr, ytr, init_score=otr, free_raw_data=False)
    params = dict(
        objective="binary", learning_rate=0.03, num_leaves=15, min_data_in_leaf=400,
        feature_fraction=0.8, bagging_fraction=0.8, bagging_freq=1, lambda_l2=10.0, verbose=-1, seed=7,
    )
    booster = lgb.train(params, ds, num_boost_round=300)
    raw = booster.predict(Xte, raw_score=True) + ote
    return 1.0 / (1.0 + np.exp(-raw))


def brier(ps, ys) -> float:
    return sum((p - y) ** 2 for p, y in zip(ps, ys)) / max(1, len(ys))


def logloss(ps, ys) -> float:
    s = 0.0
    for p, y in zip(ps, ys):
        p = min(1 - 1e-9, max(1e-9, p))
        s -= y * math.log(p) + (1 - y) * math.log(1 - p)
    return s / max(1, len(ys))


# --- report ---------------------------------------------------------------------

def fmt_row(name: str, params, is_s: dict, oos: list[Bet]) -> str:
    s = summary(oos)
    if s["n"] == 0:
        return f"| {name} | {params} | {is_s.get('n', 0)} | {is_s.get('pnl', 0):+.2f} | 0 | — | — | — | — | — | — | — |"
    lo95, hi95 = day_block_ci(oos, 0.95)
    lo99, hi99 = day_block_ci(oos, 0.99)
    ok = "**yes**" if lo99 > 0 else "no"
    stress = f"{s['stress_pnl']:+.2f}" if s["stress_pnl"] is not None else "—"
    return (
        f"| {name} | {params} | {is_s['n']} | {is_s['pnl']:+.2f} | {s['n']} | {s['win']:.1%} | "
        f"{s['ask'] * 100:.1f}¢ | {s['pnl']:+.2f} | {s['per']:+.3f} | [{lo95:+.3f}, {hi95:+.3f}] | "
        f"[{lo99:+.3f}, {hi99:+.3f}] {ok} | {stress} |"
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(HERE.parent / "backtest" / "cache"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    rows = build_rows(Path(args.cache))
    days = sorted({r.day for r in rows})
    if len(days) <= OOS_DAYS + 3:
        print(f"not enough days ({len(days)})")
        return 0
    oos_days = set(days[-OOS_DAYS:])
    is_rows = [r for r in rows if r.day not in oos_days]
    oos_rows = [r for r in rows if r.day in oos_days]
    lines = [
        "# Edge search (pre-registered, walk-forward)",
        "",
        f"Rows (decision minutes): {len(rows)} · markets: {len({r.ticker for r in rows})} · "
        f"IS days {days[0]} → {days[-OOS_DAYS - 1]} · OOS days {days[-OOS_DAYS]} → {days[-1]}",
        "",
        f"$5 all-in taker bets, exact Kalshi fee, one bet per market, asks {MIN_ASK * 100:.0f}–{MAX_ASK * 100:.0f}¢, "
        "candle-close fill. CIs are day-block bootstrap of OOS $/bet. **Counts only if the 99% CI excludes 0** "
        "(5 families tested).",
        "",
        "| Family | IS-picked params | IS n | IS P&L | OOS n | OOS win% | OOS avg ask | OOS P&L | OOS $/bet | 95% CI $/bet | 99% CI $/bet (counts?) | OOS stress-fill P&L |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|---:|",
    ]

    # Reference rows (no tuning)
    for name, rule in (
        ("ref: spot side, any minute", lambda r: spot_side(r)),
        ("ref: random side", lambda r: "YES" if hash(r.ticker) % 2 else "NO"),
    ):
        lines.append(fmt_row(name, "—", summary(first_bets(is_rows, rule)), first_bets(oos_rows, rule)))

    # H2
    grid = [dict(tmax=t, lo=lo, hi=hi, zmin=zm)
            for t in (120, 180, 300) for lo in (0.80, 0.85, 0.90) for hi in (0.95, 0.97) for zm in (1.0, 1.5, 2.0, 3.0)]
    p, s = grid_pick(is_rows, grid, h2_rule)
    lines.append(fmt_row("H2 late favorite", p, s, first_bets(oos_rows, h2_rule(**p)) if p else []))

    # H3
    grid = [dict(k=k, g=g) for k in (1.0, 1.5, 2.0, 3.0) for g in (0.03, 0.05, 0.08, 0.12)]
    p, s = grid_pick(is_rows, grid, h3_rule)
    lines.append(fmt_row("H3 spot lag", p, s, first_bets(oos_rows, h3_rule(**p)) if p else []))

    # H4
    grid = [dict(j=j) for j in (0.08, 0.12, 0.18, 0.25)]
    p, s = grid_pick(is_rows, grid, h4_rule)
    lines.append(fmt_row("H4 fade jump", p, s, first_bets(oos_rows, h4_rule(**p)) if p else []))

    # H5
    grid = [dict(scale=sc, margin=mg) for sc in (0.8, 1.0, 1.25, 1.5) for mg in (0.03, 0.05, 0.08)]
    p, s = grid_pick(is_rows, grid, h5_rule)
    lines.append(fmt_row("H5 digital EV", p, s, first_bets(oos_rows, h5_rule(**p)) if p else []))

    # H1
    calib = []
    try:
        is_days = sorted({r.day for r in is_rows})
        cut = is_days[int(len(is_days) * 0.7)]
        fit_rows = [r for r in is_rows if r.day < cut]
        val_rows = [r for r in is_rows if r.day >= cut]
        pv = h1_fit_predict(fit_rows, val_rows)
        pv_map = {id(r): float(p) for r, p in zip(val_rows, pv)}
        best_m, best_s = None, {"n": 0}
        for mg in (0.03, 0.05, 0.08, 0.12):
            s = summary(first_bets(val_rows, lambda r, mg=mg: ev_rule(pv_map[id(r)], r, mg)))
            if s["n"] >= MIN_IS_BETS // 2 and (best_m is None or s["pnl"] > best_s["pnl"]):
                best_m, best_s = mg, s
        po = h1_fit_predict(is_rows, oos_rows)
        po_map = {id(r): float(p) for r, p in zip(oos_rows, po)}
        ys = [r.y for r in oos_rows]
        mids = [r.mid for r in oos_rows]
        calib = [
            f"H1 OOS calibration on {len(ys)} minutes: model Brier {brier(po, ys):.5f} vs mid {brier(mids, ys):.5f}; "
            f"log-loss {logloss(po, ys):.5f} vs mid {logloss(mids, ys):.5f}."
        ]
        oos_bets = first_bets(oos_rows, lambda r: ev_rule(po_map[id(r)], r, best_m)) if best_m else []
        lines.append(fmt_row("H1 GBM residual EV", {"margin": best_m} if best_m else None, best_s, oos_bets))
    except ImportError:
        lines.append("| H1 GBM residual EV | lightgbm not installed | | | | | | | | | | |")

    lines += [""] + calib + [
        "",
        "Caveats: 1-minute candles hide the seconds-scale lag; candle-close asks are not guaranteed "
        "fillable at $5 size (no depth data); settlement index ≠ Coinbase last trade.",
    ]
    text = "\n".join(lines)
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
