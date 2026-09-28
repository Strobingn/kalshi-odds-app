#!/usr/bin/env python3
"""Empirical study: how do KXBTC15M / KXETH15M / KXSOL15M settle, and is the
final minute mispriced relative to that?

Inputs (tools/backtest/cache, see tools/backtest/fetch.py and
tools/research/settlement_fetch.py):
  markets.jsonl, candles/*.json, spot_{P}.json         (backtest cache)
  settlement.jsonl, settlement_series.json,
  settlement_spot_{P}.json, settlement_trades/*.json   (settlement_fetch.py)

Sections of the Markdown report:
  A  Settlement value vs Coinbase. If Kalshi returns a numeric
     `expiration_value`, compare it with Coinbase references around close:
     last trade, first trade after close, per-second TWAP and VWAP over the
     last 15/30/60/120 s (trade sample), and 1m-candle proxies (all markets).
     Which window fits best (median |diff| bp, sign agreement vs strike), and
     the residual σ = the noise floor a spot model should carry.
  B  Final-minute calibration from candles only: P(yes) by spot-vs-strike
     distance (z = ln(S/K)/(σ_1m·√(tte/60))) at 3/2/1 min before close vs the
     Kalshi mid, then walk-forward (expanding, by UTC day) betting tests:
     one bet per market, $5 all-in with the exact Kalshi fee
     (tools/backtest/pipeline.py), asks 10–97¢, day-block bootstrap 99% CI.
  C  Rules / settlement-source text Kalshi returns, verbatim.

Everything here is inferred from data; nothing asserts Kalshi's rules.
Stdlib only. Exit code 0 regardless of results.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import re
import statistics
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "backtest"))

from pipeline import (  # noqa: E402
    EV_MARGIN,
    STAKE_USD,
    fee_per_contract,
    norm_cdf,
    realized_vol_bar_std,
    size_all_in,
    usable,
)

PRODUCT = {"BTC": "BTC-USD", "ETH": "ETH-USD", "SOL": "SOL-USD"}
TRADE_WINDOWS = (15, 30, 60, 120)
TTES = (180, 120, 60)
Z_EDGES = (-3.0, -2.0, -1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0, 3.0)
MIN_ASK = 0.10
MAX_ASK = 0.97
MIN_TRAIN_DAYS = 5
PRIOR_STRENGTH = 10.0
VOL_BARS = 30


# --- loading ------------------------------------------------------------------

def load_jsonl(p: Path) -> list[dict]:
    if not p.is_file():
        return []
    out = []
    for line in p.read_text().splitlines():
        line = line.strip()
        if line:
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return out


def load_bars(cache: Path, product: str) -> dict[int, tuple[float, float, float, float]]:
    """start_sec → (low, high, open, close) merged from spot_ and settlement_spot_ files."""
    out: dict[int, tuple[float, float, float, float]] = {}
    for name in (f"settlement_spot_{product}.json", f"spot_{product}.json"):
        p = cache / name
        if not p.is_file():
            continue
        for r in json.loads(p.read_text()):
            if isinstance(r, list) and len(r) >= 5:
                out[int(r[0])] = (float(r[1]), float(r[2]), float(r[3]), float(r[4]))
    return out


def num(x) -> float | None:
    if x is None or x == "":
        return None
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


def day_of(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


# --- A: reference prices ----------------------------------------------------------

def trade_refs(trades: list[list], close_s: int) -> dict[str, float]:
    """Coinbase trade-based references for a market closing at close_s (unix s).

    trades: [[t_ms, price, size], ...] (any order).
    twap_W: mean over the W one-second marks close-W+1 … close of the last
    trade price at or before each mark. vwap_W: size-weighted mean of trades in
    (close-W, close].
    """
    tr = sorted((int(t), float(p), float(s)) for t, p, s in trades)
    out: dict[str, float] = {}
    c_ms = close_s * 1000
    before = [x for x in tr if x[0] <= c_ms]
    after = [x for x in tr if x[0] > c_ms]
    if before:
        out["last_trade"] = before[-1][1]
    if after:
        out["first_after"] = after[0][1]
    for w in TRADE_WINDOWS:
        lo_ms = c_ms - w * 1000
        prior = [x for x in tr if x[0] <= lo_ms]
        # need a trade at/before the first mark (or inside the window early on)
        j = 0
        last = prior[-1][1] if prior else None
        inside = [x for x in tr if lo_ms < x[0] <= c_ms]
        vals = []
        for k in range(1, w + 1):
            mark = lo_ms + k * 1000
            while j < len(inside) and inside[j][0] <= mark:
                last = inside[j][1]
                j += 1
            if last is not None:
                vals.append(last)
        if len(vals) >= max(1, int(0.8 * w)):
            out[f"twap_{w}s"] = sum(vals) / len(vals)
        vol = sum(x[2] for x in inside)
        if inside and vol > 0:
            out[f"vwap_{w}s"] = sum(x[1] * x[2] for x in inside) / vol
    return out


def candle_refs(bars: dict, close_s: int) -> dict[str, float]:
    """1m-candle references (Coinbase `time` = bar start)."""
    out: dict[str, float] = {}
    last = bars.get(close_s - 60)
    prev = bars.get(close_s - 120)
    nxt = bars.get(close_s)
    if last:
        lo, hi, op, cl = last
        out["cb_1m_close"] = cl
        out["cb_ohlc4_last1m"] = (lo + hi + op + cl) / 4.0
        if prev:
            out["cb_ohlc4_last2m"] = 0.5 * (out["cb_ohlc4_last1m"] + sum(prev) / 4.0)
    if nxt:
        out["cb_open_next1m"] = nxt[2]
    return out


def diff_bp(a: float, ref: float) -> float:
    return (a / ref - 1.0) * 1e4


def robust_sigma(xs: list[float]) -> float:
    if not xs:
        return float("nan")
    med = statistics.median(xs)
    return 1.4826 * statistics.median([abs(x - med) for x in xs])


def fit_stats(pairs: list[tuple[float, float, float, int]]) -> dict:
    """pairs: (candidate, settle_value, strike, y). Returns fit metrics.

    diff = candidate vs settle value in bp; sign agreement = (candidate > strike) == y.
    """
    if not pairs:
        return {"n": 0}
    d = [diff_bp(c, s) for c, s, _, _ in pairs]
    ad = sorted(abs(x) for x in d)
    agree = [((c > k) == (y == 1)) for c, _, k, y in pairs if k]
    close = [((c > k) == (y == 1)) for c, s, k, y in pairs if k and abs(diff_bp(s, k)) < 5.0]
    return {
        "n": len(d),
        "med_abs_bp": statistics.median(ad),
        "p90_abs_bp": ad[min(len(ad) - 1, int(0.9 * (len(ad) - 1) + 0.5))],
        "bias_bp": statistics.mean(d),
        "med_bp": statistics.median(d),
        "rsig_bp": robust_sigma(d),
        "sd_bp": statistics.pstdev(d) if len(d) > 1 else 0.0,
        "sign_agree": sum(agree) / len(agree) if agree else float("nan"),
        "sign_n": len(agree),
        "near_agree": sum(close) / len(close) if close else float("nan"),
        "near_n": len(close),
    }


def settle_value(raw: dict) -> float | None:
    for k in ("expiration_value", "settlement_value_price", "final_value"):
        v = num(raw.get(k))
        if v is not None and v > 0:
            return v
    return None


# --- B: calibration rows -------------------------------------------------------------

@dataclass
class CalRow:
    ticker: str
    coin: str
    day: str
    tte: int
    y: int
    z: float
    dist_bp: float
    sig_bar: float
    mid: float | None
    ya: float | None
    na: float | None
    ya_hi: float | None
    na_hi: float | None
    ohlc4_prev: tuple = ()  # OHLC/4 ÷ spot of the 1m bars ending at the decision time, newest first (≤ 2)


def z_bucket(z: float) -> int:
    for i, e in enumerate(Z_EDGES):
        if z < e:
            return i
    return len(Z_EDGES)


def bucket_label(i: int) -> str:
    lo = "-∞" if i == 0 else f"{Z_EDGES[i - 1]:+g}"
    hi = "+∞" if i == len(Z_EDGES) else f"{Z_EDGES[i]:+g}"
    return f"[{lo}, {hi})"


def cal_rows_for_market(m: dict, candles: list[dict], bars: dict) -> list[CalRow]:
    strike = num(m.get("floor_strike"))
    if not strike or m.get("result") not in ("yes", "no") or not m.get("close_ms"):
        return []
    close_s = int(m["close_ms"]) // 1000
    by_end = {int(r["end_ts"]): r for r in candles if r.get("end_ts")}
    out = []
    for tte in TTES:
        end = close_s - tte
        bar = bars.get(end - 60)
        if not bar:
            continue
        spot = bar[3]
        closes = [bars[end - 60 * i][3] for i in range(VOL_BARS, 0, -1) if (end - 60 * i) in bars]
        sig = realized_vol_bar_std(closes) if len(closes) >= 10 else None
        if not sig or spot <= 0:
            continue
        dist = math.log(spot / strike)
        z = dist / (sig * math.sqrt(tte / 60.0))
        r = by_end.get(end) or {}
        yb = (r.get("yes_bid") or {}).get("close")
        ya = (r.get("yes_ask") or {}).get("close")
        mid = 0.5 * (yb + ya) if yb is not None and ya is not None else r.get("mid")
        ya_hi = (r.get("yes_ask") or {}).get("high")
        yb_lo = (r.get("yes_bid") or {}).get("low")
        prev4 = tuple(sum(bars[b]) / 4.0 / spot for b in (end - 60, end - 120) if b in bars)
        out.append(CalRow(
            ohlc4_prev=prev4,
            ticker=m["ticker"], coin=m.get("coin") or "BTC", day=day_of(int(m["close_ms"])), tte=tte,
            y=1 if m["result"] == "yes" else 0, z=z, dist_bp=dist * 1e4, sig_bar=sig,
            mid=min(0.999, max(0.001, float(mid))) if mid is not None else None,
            ya=usable(ya) if ya is not None else None,
            na=usable(1.0 - yb) if yb is not None else None,
            ya_hi=usable(max(ya, ya_hi)) if ya is not None and ya_hi is not None else None,
            na_hi=usable(max(1.0 - yb, 1.0 - yb_lo)) if yb is not None and yb_lo is not None else None,
        ))
    return out


def calibration_table(rows: list[CalRow]) -> dict[tuple[int, int], dict]:
    """(tte, bucket) → n, emp P(yes), mean mid, gap, binomial SE, best-side EV at mean asks."""
    acc: dict[tuple[int, int], list[CalRow]] = defaultdict(list)
    for r in rows:
        acc[(r.tte, z_bucket(r.z))].append(r)
    out = {}
    for k, rs in acc.items():
        n = len(rs)
        p = sum(r.y for r in rs) / n
        mids = [r.mid for r in rs if r.mid is not None]
        mm = sum(mids) / len(mids) if mids else float("nan")
        yas = [r.ya for r in rs if r.ya is not None]
        nas = [r.na for r in rs if r.na is not None]
        ev_y = (p - statistics.mean(yas) - fee_per_contract(statistics.mean(yas))) if yas else float("nan")
        ev_n = ((1 - p) - statistics.mean(nas) - fee_per_contract(statistics.mean(nas))) if nas else float("nan")
        out[k] = {
            "n": n, "p": p, "mid": mm, "gap": p - mm if mids else float("nan"),
            # Agresti–Coull-style SE so 0/n and n/n buckets are not "certain"
            "se": math.sqrt(((sum(r.y for r in rs) + 1) / (n + 2)) * (1 - (sum(r.y for r in rs) + 1) / (n + 2)) / (n + 2)), "ev_yes": ev_y, "ev_no": ev_n,
        }
    return out


# --- models -------------------------------------------------------------------------

def p_digital(r: CalRow, avg_window_s: float = 0.0, eta_bp: float = 0.0) -> float:
    """P(settle > strike) for Brownian log-spot with 1m σ = r.sig_bar.

    avg_window_s = W: settlement is an average of spot over the last W seconds
    (W=0 → spot at close). Remaining variance for τ = tte:
      τ ≥ W: σ²(τ − 2W/3);   τ < W: σ² τ³ / (3W²)
    When τ < W the already-elapsed (W − τ) seconds are known: their average is
    taken from the 1m bars' OHLC/4 (only whole minutes; else current spot), and
    the expected settle is ((W − τ)·A + τ·S)/W.
    eta_bp: independent basis noise between Coinbase and the settlement value.
    """
    s2 = (r.sig_bar ** 2) / 60.0  # per second
    tau, w = float(r.tte), float(avg_window_s)
    center = r.dist_bp / 1e4  # ln(S/K)
    if w <= 0:
        var = s2 * tau
    elif tau >= w:
        var = s2 * (tau - 2.0 * w / 3.0)
    else:
        var = s2 * tau ** 3 / (3.0 * w * w)
        k = int(round((w - tau) / 60.0))
        if k >= 1 and abs((w - tau) - 60.0 * k) < 1e-6 and len(r.ohlc4_prev) >= k:
            spot_over_k = math.exp(center)
            a_over_s = sum(r.ohlc4_prev[:k]) / k
            exp_over_k = spot_over_k * (((w - tau) * a_over_s + tau) / w)
            center = math.log(exp_over_k) if exp_over_k > 0 else center
    var += (eta_bp / 1e4) ** 2
    if var <= 0:
        return 1.0 if center > 0 else 0.0
    return norm_cdf(center / math.sqrt(var))


class BucketModel:
    """Empirical P(yes | tte, z-bucket), shrunk toward the bucket's mean mid."""

    def __init__(self, train: list[CalRow], strength: float = PRIOR_STRENGTH):
        self.t = calibration_table(train)
        self.strength = strength

    def p(self, r: CalRow) -> float | None:
        e = self.t.get((r.tte, z_bucket(r.z)))
        if not e:
            return r.mid
        prior = e["mid"] if math.isfinite(e["mid"]) else 0.5
        return (e["p"] * e["n"] + prior * self.strength) / (e["n"] + self.strength)


# --- betting ----------------------------------------------------------------------------

@dataclass
class Bet:
    ticker: str
    day: str
    tte: int
    side: str
    ask: float
    won: bool
    pnl: float
    pnl_stress: float | None


def bet_pnl(ask: float | None, won: bool, stake: float = STAKE_USD) -> float | None:
    """$ P&L of a `stake` all-in taker buy at `ask` (exact Kalshi fee)."""
    if ask is None:
        return None
    c, cost, _ = size_all_in(ask, stake)
    if c <= 0:
        return None
    return (c - cost) if won else -cost


def ev_side(p: float, r: CalRow, margin: float) -> str | None:
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


def place(r: CalRow, side: str) -> Bet | None:
    ask = r.ya if side == "YES" else r.na
    if ask is None or not (MIN_ASK <= ask <= MAX_ASK):
        return None
    won = (r.y == 1) == (side == "YES")
    pnl = bet_pnl(ask, won)
    if pnl is None:
        return None
    return Bet(r.ticker, r.day, r.tte, side, ask, won, pnl, bet_pnl(r.ya_hi if side == "YES" else r.na_hi, won))


def first_bets(rows: list[CalRow], rule) -> list[Bet]:
    """One bet per market at the earliest qualifying tte (180 → 120 → 60)."""
    order = sorted(rows, key=lambda r: (r.ticker, -r.tte))
    done: set[str] = set()
    out = []
    for r in order:
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


def walk_forward(rows: list[CalRow], make_rule, min_train_days: int = MIN_TRAIN_DAYS) -> list[Bet]:
    """make_rule(train_rows) -> rule(row). Expanding window by UTC day."""
    days = sorted({r.day for r in rows})
    by_day: dict[str, list[CalRow]] = defaultdict(list)
    for r in rows:
        by_day[r.day].append(r)
    out: list[Bet] = []
    for i, d in enumerate(days):
        if i < min_train_days:
            continue
        train = [r for dd in days[:i] for r in by_day[dd]]
        out += first_bets(by_day[d], make_rule(train))
    return out


def summary(bets: list[Bet]) -> dict:
    n = len(bets)
    if not n:
        return {"n": 0}
    stress = [b.pnl_stress for b in bets if b.pnl_stress is not None]
    return {
        "n": n, "days": len({b.day for b in bets}), "win": sum(b.won for b in bets) / n,
        "ask": sum(b.ask for b in bets) / n, "pnl": sum(b.pnl for b in bets),
        "per": sum(b.pnl for b in bets) / n, "stress": sum(stress) if stress else None,
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


def brier(ps: list[float], ys: list[int]) -> float:
    return sum((p - y) ** 2 for p, y in zip(ps, ys)) / max(1, len(ys))


def logloss(ps: list[float], ys: list[int]) -> float:
    s = 0.0
    for p, y in zip(ps, ys):
        p = min(1 - 1e-6, max(1e-6, p))
        s -= y * math.log(p) + (1 - y) * math.log(1 - p)
    return s / max(1, len(ys))


# --- report -----------------------------------------------------------------------------

def f(x, spec: str = ".2f", dash: str = "—") -> str:
    if x is None or (isinstance(x, float) and not math.isfinite(x)):
        return dash
    return format(x, spec)


def bet_line(name: str, bets: list[Bet]) -> str:
    s = summary(bets)
    if not s["n"]:
        return f"| {name} | 0 | | | | | | | | |"
    lo95, hi95 = day_block_ci(bets, 0.95)
    lo99, hi99 = day_block_ci(bets, 0.99)
    ok = "**yes**" if lo99 > 0 else ("**negative**" if hi99 < 0 else "no")
    return (
        f"| {name} | {s['n']} | {s['days']} | {s['win']:.1%} | {s['ask'] * 100:.1f}¢ | {s['pnl']:+.2f} | "
        f"{s['per']:+.3f} | [{f(lo95, '+.3f')}, {f(hi95, '+.3f')}] | [{f(lo99, '+.3f')}, {f(hi99, '+.3f')}] {ok} | "
        f"{f(s['stress'], '+.2f')} |"
    )


def section_a(markets, settle_by, bars_by_coin, trades_by, lines) -> dict:
    """Returns {'best': name, 'window_s': W, 'eta_bp': ..., 'coin_sig': {...}}."""
    lines += ["## A. Settlement value vs Coinbase (empirical)", ""]
    census: dict[str, int] = defaultdict(int)
    for r in settle_by.values():
        for k, v in r.items():
            if not k.startswith("_") and v not in (None, "", [], {}):
                census[k] += 1
    if settle_by:
        interesting = [k for k in sorted(census) if re.search(r"expir|settl|result|rule|close|value|strike|source", k)]
        lines += [
            f"Kalshi market objects stored: {len(settle_by)}. Settlement-related fields returned (non-null count):",
            "",
            ", ".join(f"`{k}` ({census[k]})" for k in interesting) or "(none)",
            "",
            "<details><summary>All fields</summary>",
            "",
            ", ".join(f"`{k}` ({census[k]})" for k in sorted(census)),
            "",
            "</details>",
            "",
        ]
    else:
        lines += ["No settlement.jsonl rows (settlement_fetch.py did not run or returned nothing).", ""]

    by_ticker = {m["ticker"]: m for m in markets}
    # sanity: does the returned value reproduce the result vs floor_strike?
    rows = []
    for t, raw in settle_by.items():
        m = by_ticker.get(t)
        sv = settle_value(raw)
        k = num((m or {}).get("floor_strike")) or num(raw.get("floor_strike"))
        res = ((m or {}).get("result") or raw.get("result") or "").lower()
        if sv is None or not k or res not in ("yes", "no") or not m:
            continue
        rows.append((m, sv, k, 1 if res == "yes" else 0))
    out = {"best": None, "window_s": 0.0, "eta_bp": 0.0, "n": len(rows)}
    if not rows:
        lines += ["No numeric `expiration_value` found → window fit skipped; B uses W=0, η=0.", ""]
        return out
    gt = sum((sv > k) == (y == 1) for _, sv, k, y in rows)
    ge = sum((sv >= k) == (y == 1) for _, sv, k, y in rows)
    ties = [(m["ticker"], sv, k, y) for m, sv, k, y in rows if sv == k]
    lines += [
        f"Markets with numeric `expiration_value`, `floor_strike` and result: **{len(rows)}**.",
        f"- result == (expiration_value > floor_strike): **{gt / len(rows):.2%}**; "
        f"result == (expiration_value ≥ floor_strike): {ge / len(rows):.2%}; exact ties: {len(ties)}"
        + (f" (e.g. {ties[0][0]} → {'yes' if ties[0][3] else 'no'})" if ties else "") + ".",
    ]
    # strike == previous window's expiration_value?
    by_series_close = {(m.get("series"), int(m["close_ms"])): sv for m, sv, _, _ in rows}
    chk = [(abs(diff_bp(k, by_series_close[(m.get("series"), int(m["open_ms"]))])))
           for m, sv, k, _ in rows if m.get("open_ms") and (m.get("series"), int(m["open_ms"])) in by_series_close]
    if chk:
        lines.append(
            f"- floor_strike vs the previous window's expiration_value ({len(chk)} pairs): exact match "
            f"{sum(c < 1e-6 for c in chk) / len(chk):.1%}, median |diff| {statistics.median(chk):.2f} bp."
        )
    lines.append("")

    cands: dict[str, list] = defaultdict(list)
    cands_t: dict[str, list] = defaultdict(list)  # restricted to trade sample, for like-for-like
    per_coin: dict[tuple[str, str], list] = defaultdict(list)
    for m, sv, k, y in rows:
        close_s = int(m["close_ms"]) // 1000
        refs = candle_refs(bars_by_coin.get(m.get("coin"), {}), close_s)
        tr = trades_by.get(m["ticker"])
        trefs = trade_refs(tr, close_s) if tr else {}
        allr = {**refs, **trefs}
        for name, v in allr.items():
            cands[name].append((v, sv, k, y))
            per_coin[(name, m.get("coin"))].append((v, sv, k, y))
            if trefs:
                cands_t[name].append((v, sv, k, y))
    # strike vs Coinbase at open (same index, if strike = value at open)
    open_c: dict[str, list] = defaultdict(list)
    for m, sv, k, y in rows:
        if m.get("open_ms"):
            for name, v in candle_refs(bars_by_coin.get(m.get("coin"), {}), int(m["open_ms"]) // 1000).items():
                open_c[name].append((v, k, k, y))

    hdr = ["| Reference | n | median \\|diff\\| bp | p90 \\|diff\\| bp | bias bp | robust σ bp | sd bp | sign agree vs strike | near-strike (<5bp) agree (n) |",
           "|---|---:|---:|---:|---:|---:|---:|---:|---|"]

    def table(cd: dict[str, list]) -> list[tuple[str, dict]]:
        st = [(name, fit_stats(v)) for name, v in cd.items()]
        st.sort(key=lambda x: x[1].get("med_abs_bp", 1e9))
        return st

    def rowfmt(name, s):
        return (f"| `{name}` | {s['n']} | {s['med_abs_bp']:.2f} | {s['p90_abs_bp']:.2f} | {s['bias_bp']:+.2f} | "
                f"{s['rsig_bp']:.2f} | {s['sd_bp']:.2f} | {f(s['sign_agree'], '.2%')} | {f(s['near_agree'], '.1%')} ({s['near_n']}) |")

    all_st = table(cands)
    lines += ["### Close: all markets with the reference available", "", *hdr]
    lines += [rowfmt(n, s) for n, s in all_st]
    lines.append("")
    t_st = table(cands_t) if cands_t else []
    if t_st:
        n_t = sum(1 for m, *_ in rows if m["ticker"] in trades_by)
        lines += [f"### Close: Coinbase trade sample only ({n_t} markets, like-for-like)", "", *hdr]
        lines += [rowfmt(n, s) for n, s in t_st]
        lines.append("")
    else:
        lines += ["No Coinbase trade sample available → only 1m-candle proxies were compared.", ""]
    if open_c:
        lines += ["### Open: floor_strike vs Coinbase 1m references at open_time", "",
                  "(sign-agreement columns are not meaningful here: the reference is compared with the strike itself.)", "", *hdr]
        lines += [rowfmt(n, s) for n, s in table(open_c)]
        lines.append("")

    best_name, best = (t_st or all_st)[0]
    mw = re.match(r"(?:twap|vwap)_(\d+)s", best_name)
    w = float(mw.group(1)) if mw else (60.0 if "ohlc4_last1m" in best_name else 120.0 if "ohlc4_last2m" in best_name else 0.0)
    out.update(best=best_name, window_s=w, eta_bp=best["rsig_bp"])
    lines += [
        f"**Best-fitting reference (lowest median |diff|): `{best_name}`** "
        f"(median |diff| {best['med_abs_bp']:.2f} bp, robust σ {best['rsig_bp']:.2f} bp, "
        f"sign agreement {f(best['sign_agree'], '.2%')}). Implied averaging window W = {w:.0f} s (empirical, not a rule).",
        "",
        "Residual σ (robust) per coin — this is the noise floor a model using that Coinbase reference should carry:",
        "",
        "| Reference | Coin | n | robust σ bp | sd bp | latest spot | robust σ in $ |",
        "|---|---|---:|---:|---:|---:|---:|",
    ]
    for name in dict.fromkeys([best_name, "last_trade", "cb_1m_close"]):
        for coin in PRODUCT:
            v = per_coin.get((name, coin))
            if not v:
                continue
            s = fit_stats(v)
            bars = bars_by_coin.get(coin) or {}
            px = bars[max(bars)][3] if bars else float("nan")
            lines.append(f"| `{name}` | {coin} | {s['n']} | {s['rsig_bp']:.2f} | {s['sd_bp']:.2f} | {px:,.2f} | "
                         f"${px * s['rsig_bp'] / 1e4:,.2f} |")
    lines += [
        "",
        "(Compare: the other team's model used a fixed η = 1 bp floor. robust σ = 1.4826·MAD of the bp residual.)",
        "",
    ]
    return out


def section_b(rows: list[CalRow], fit: dict, lines: list[str]) -> None:
    lines += ["## B. Final minutes: outcome vs spot–strike distance, and the Kalshi mid", ""]
    if not rows:
        lines += ["No calibration rows (need candles + Coinbase 1m spot).", ""]
        return
    days = sorted({r.day for r in rows})
    lines += [
        f"Rows: {len(rows)} (market × tte) from {len({r.ticker for r in rows})} markets, days {days[0]} → {days[-1]}. "
        f"z = ln(S/K)/(σ_1m·√(tte/60)), S = Coinbase 1m close ending at the Kalshi candle end, "
        f"σ_1m from the previous {VOL_BARS} one-minute returns. gap = empirical P(yes) − mean mid (±2·SE binomial). "
        "EV columns: in-sample best-side EV per contract at the bucket's mean ask after fee (descriptive only).",
        "",
    ]
    tab = calibration_table(rows)
    for tte in TTES:
        lines += [f"### tte = {tte} s", "",
                  "| z bucket | n | P(yes) | mean mid | gap ±2SE | EV YES | EV NO |", "|---|---:|---:|---:|---|---:|---:|"]
        for b in range(len(Z_EDGES) + 1):
            e = tab.get((tte, b))
            if not e:
                continue
            flag = " ⚑" if math.isfinite(e["gap"]) and abs(e["gap"]) > 2 * e["se"] else ""
            lines.append(
                f"| {bucket_label(b)} | {e['n']} | {e['p']:.3f} | {f(e['mid'], '.3f')} | "
                f"{f(e['gap'], '+.3f')} ±{2 * e['se']:.3f}{flag} | {f(e['ev_yes'], '+.3f')} | {f(e['ev_no'], '+.3f')} |"
            )
        lines.append("")

    w, eta = fit.get("window_s") or 0.0, fit.get("eta_bp") or 0.0
    lines += ["### Probability quality (all rows with a mid)", "",
              "| Model | tte | n | Brier | log-loss |", "|---|---:|---:|---:|---:|"]
    for tte in TTES:
        rs = [r for r in rows if r.tte == tte and r.mid is not None]
        if not rs:
            continue
        ys = [r.y for r in rs]
        for name, ps in (
            ("Kalshi mid", [r.mid for r in rs]),
            ("digital, spot at close (W=0, η=0)", [p_digital(r) for r in rs]),
            ("digital, η=1 bp (other team's floor)", [p_digital(r, 0.0, 1.0) for r in rs]),
            (f"digital, W={w:.0f}s, η={eta:.2f} bp (from A)", [p_digital(r, w, eta) for r in rs]),
        ):
            lines.append(f"| {name} | {tte} | {len(rs)} | {brier(ps, ys):.5f} | {logloss(ps, ys):.5f} |")
    lines.append("")

    lines += [
        "### Betting tests (walk-forward by UTC day)",
        "",
        f"Expanding window: each day is bet with a model fit only on earlier days (first {MIN_TRAIN_DAYS} days = "
        f"training only). One bet per market at the earliest qualifying tte in (180, 120, 60) s. Bet when "
        f"p − ask − fee > {EV_MARGIN:.2f} (pipeline.EV_MARGIN, fixed in advance). ${STAKE_USD:.0f} all-in, exact fee, "
        f"asks {MIN_ASK * 100:.0f}–{MAX_ASK * 100:.0f}¢, fill = candle-close ask; stress = worse of close/high ask. "
        "CIs: day-block bootstrap of $/bet. 99% CI is the bar (several models tested).",
        "",
        "| Model | bets | days | win% | avg ask | P&L $ | $/bet | 95% CI $/bet | 99% CI $/bet (edge?) | stress P&L $ |",
        "|---|---:|---:|---:|---:|---:|---:|---|---|---:|",
    ]

    def bucket_rule(train):
        bm = BucketModel(train)
        return lambda r: ev_side(p, r, EV_MARGIN) if (p := bm.p(r)) is not None else None

    def spot_side_60(_train):
        def rule(r):
            if r.tte != 60 or r.dist_bp == 0:
                return None
            return "YES" if r.dist_bp > 0 else "NO"
        return rule

    models = [
        ("empirical z-bucket calibration (OOS)", bucket_rule),
        ("digital W=0 η=0 (OOS days)", lambda _t: (lambda r: ev_side(p_digital(r), r, EV_MARGIN))),
        ("digital η=1bp (OOS days)", lambda _t: (lambda r: ev_side(p_digital(r, 0, 1.0), r, EV_MARGIN))),
        (f"digital W={w:.0f}s η={eta:.2f}bp (OOS days; W, η from A on all data)",
         lambda _t: (lambda r: ev_side(p_digital(r, w, eta), r, EV_MARGIN))),
        ("ref: spot side at tte=60 (OOS days)", spot_side_60),
    ]
    for name, mk in models:
        lines.append(bet_line(name, walk_forward(rows, mk)))
    # in-sample reference: bucket model fit and bet on the same days (optimistic)
    lines.append(bet_line("ref: bucket calibration in-sample (optimistic, not a test)",
                          first_bets(rows, bucket_rule(rows))))
    lines += [
        "",
        "Reading: an edge counts only if the OOS 99% CI excludes 0. `stress` assumes the worse of the "
        "candle's close/high ask. No depth data: a $5 fill at the candle-close ask is not guaranteed.",
        "",
    ]


def section_c(settle_by: dict, series_meta: dict, lines: list[str]) -> None:
    lines += ["## C. Rules / settlement text returned by Kalshi (verbatim)", "",
              "Numbers (strikes, times) are replaced by `#` to group identical templates; one verbatim example per template.",
              ""]
    rule_keys = sorted({k for r in settle_by.values() for k in r if re.search(r"rule|settlement_source|settle", k)
                        and isinstance(r.get(k), str)})
    any_text = False
    for key in rule_keys:
        groups: dict[tuple[str, str], list[str]] = defaultdict(list)
        for t, r in settle_by.items():
            v = r.get(key)
            if isinstance(v, str) and v.strip():
                groups[(t.split("-")[0], re.sub(r"\d[\d,\.]*", "#", v))].append(t)
        shown: dict[str, int] = defaultdict(int)
        n_tpl: dict[str, int] = defaultdict(int)
        for (series, _tpl) in groups:
            n_tpl[series] += 1
        for (series, _tpl), ts in sorted(groups.items(), key=lambda kv: (kv[0][0], -len(kv[1]))):
            any_text = True
            if shown[series] >= 3:
                continue
            shown[series] += 1
            ex = settle_by[ts[0]][key]
            lines += [f"**{series} `{key}`** (template {shown[series]}/{n_tpl[series]}, {len(ts)} markets; "
                      f"example {ts[0]}):", "", "```", ex, "```", ""]
    for s, meta in (series_meta or {}).items():
        ser = (meta or {}).get("series") if isinstance(meta, dict) else None
        if not isinstance(ser, dict):
            continue
        keep = {k: v for k, v in ser.items() if re.search(r"settle|source|contract|rule|frequency|title|tags", k)}
        if keep:
            any_text = True
            lines += [f"**Series {s}** (GET /series):", "", "```json", json.dumps(keep, indent=1)[:4000], "```", ""]
    if not any_text:
        lines += ["(No rules/settlement text found in the stored responses.)", ""]


def load_all(cache: Path):
    markets = [m for m in load_jsonl(cache / "markets.jsonl") if m.get("ticker")]
    settle_by = {r["_ticker"]: r for r in load_jsonl(cache / "settlement.jsonl") if r.get("_ticker")}
    bars_by_coin = {c: load_bars(cache, p) for c, p in PRODUCT.items()}
    candles = {}
    cdir = cache / "candles"
    for m in markets:
        p = cdir / f"{m['ticker']}.json"
        if p.is_file():
            try:
                candles[m["ticker"]] = json.loads(p.read_text())
            except json.JSONDecodeError:
                pass
    trades_by = {}
    tdir = cache / "settlement_trades"
    if tdir.is_dir():
        for p in tdir.glob("*.json"):
            try:
                v = json.loads(p.read_text())
            except json.JSONDecodeError:
                continue
            if v:
                trades_by[p.stem] = v
    series_meta = {}
    sp = cache / "settlement_series.json"
    if sp.is_file():
        try:
            series_meta = json.loads(sp.read_text())
        except json.JSONDecodeError:
            pass
    return markets, settle_by, bars_by_coin, candles, trades_by, series_meta


def build_report(cache: Path) -> str:
    markets, settle_by, bars_by_coin, candles, trades_by, series_meta = load_all(cache)
    lines = [
        "# Settlement study: KXBTC15M / KXETH15M / KXSOL15M",
        "",
        f"Markets in cache: {len(markets)} · Kalshi market objects: {len(settle_by)} · "
        f"Coinbase trade windows: {len(trades_by)} · 1m bars: "
        + ", ".join(f"{c} {len(b)}" for c, b in bars_by_coin.items()),
        "",
        "All findings are empirical inferences from public data, not statements of Kalshi's rules; "
        "see section C for the rules text itself.",
        "",
    ]
    fit = section_a(markets, settle_by, bars_by_coin, trades_by, lines)
    rows: list[CalRow] = []
    for m in markets:
        rows += cal_rows_for_market(m, candles.get(m["ticker"], []), bars_by_coin.get(m.get("coin"), {}))
    section_b(rows, fit, lines)
    section_c(settle_by, series_meta, lines)
    # coverage note (spot_*.json is fetched once by fetch.py and never extended)
    have = sum(1 for m in markets if (int(m.get("close_ms") or 0) // 1000 - 60) in bars_by_coin.get(m.get("coin"), {}))
    lines += [f"Coverage: {have}/{len(markets)} markets have a Coinbase 1m bar ending at close.", ""]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(HERE.parent / "backtest" / "cache"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    text = build_report(Path(args.cache))
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
