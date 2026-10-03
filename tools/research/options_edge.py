#!/usr/bin/env python3
"""Kalshi daily/hourly BTC & ETH "price above K" markets vs Deribit options.

Deribit's option smile implies a probability that BTC (ETH) ends above K at
any time. Kalshi's KXBTCD / KXETHD markets ask the same question. Where the
Kalshi ask is below the options-implied probability by more than the fee
plus a margin, that side is (by the options market's view) underpriced.

This is a model edge, NOT arbitrage: the options market can be wrong, and
Kalshi settles on CF Benchmarks' 60 s average, Deribit on its own index.

Probability of finishing above K at Kalshi's close T (r = 0, forward F):
  * implied vol at K: linear in strike on each Deribit expiry's mark_iv smile
    (OTM side: calls above F, puts below), then total variance iv^2*t linear
    in time between the expiries bracketing T (flat iv before the first one);
  * digital = N(d2) - vega * dsigma/dK  (Breeden-Litzenberger with skew),
    vega = F*phi(d1)*sqrt(t), slope from the bracketing smile.

Each snapshot logs every side whose ask clears fair - fee - MARGIN to a JSONL
log (one entry per ticker/side, first sighting). --score settles the log
against Kalshi results and reports realized $/contract with a bootstrap CI.

Stdlib only.
  python3 options_edge.py --log options_log.jsonl --minutes 330 --every 600
  python3 options_edge.py --log options_log.jsonl --score
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "backtest"))

from arb_scan import KALSHI, HttpClient, fee_rate_per_contract  # noqa: E402
from pipeline import SECONDS_PER_YEAR, norm_cdf  # noqa: E402

DERIBIT = "https://www.deribit.com/api/v2/public"
COINS = {"BTC": "KXBTCD", "ETH": "KXETHD"}
MARGIN = 0.03
MIN_ASK, MAX_ASK = 0.05, 0.95
MAX_DAYS = 7
MONTHS = {m: i + 1 for i, m in enumerate("JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split())}


def norm_pdf(x: float) -> float:
    return math.exp(-0.5 * x * x) / math.sqrt(2 * math.pi)


def parse_instrument(name: str) -> tuple[int, float, str] | None:
    """BTC-4OCT26-60000-C -> (expiry_ms at 08:00 UTC, strike, 'C')."""
    try:
        _coin, exp, k, cp = name.split("-")
        day, mon, yr = int(exp[:-5]), MONTHS[exp[-5:-2]], 2000 + int(exp[-2:])
        ms = int(datetime(yr, mon, day, 8, 0, tzinfo=timezone.utc).timestamp() * 1000)
        return ms, float(k), cp
    except (ValueError, KeyError):
        return None


def build_smiles(summaries: list, now_ms: int) -> dict:
    """{expiry_ms: {"t": years, "F": forward, "pts": [(K, iv)] sorted}} from OTM marks."""
    raw: dict = {}
    for s in summaries:
        p = parse_instrument(s.get("instrument_name") or "")
        iv, fwd = s.get("mark_iv"), s.get("underlying_price")
        if p is None or not iv or not fwd or p[0] <= now_ms:
            continue
        exp, k, cp = p
        e = raw.setdefault(exp, {"F": [], "pts": {}})
        e["F"].append(float(fwd))
        otm = (cp == "C" and k >= fwd) or (cp == "P" and k < fwd)
        if otm:
            e["pts"][k] = float(iv) / 100.0
    out = {}
    for exp, e in raw.items():
        pts = sorted(e["pts"].items())
        if len(pts) < 4:
            continue
        out[exp] = {"t": (exp - now_ms) / 1000 / SECONDS_PER_YEAR, "F": sorted(e["F"])[len(e["F"]) // 2], "pts": pts}
    return out


def iv_at(pts: list, k: float) -> tuple[float, float]:
    """(iv, d iv / dK) by linear interpolation; flat beyond the wings."""
    if k <= pts[0][0]:
        return pts[0][1], 0.0
    if k >= pts[-1][0]:
        return pts[-1][1], 0.0
    for (k0, v0), (k1, v1) in zip(pts, pts[1:]):
        if k0 <= k <= k1:
            slope = (v1 - v0) / (k1 - k0)
            return v0 + slope * (k - k0), slope
    return pts[-1][1], 0.0


def p_above(smiles: dict, strike: float, close_ms: int, now_ms: int) -> float | None:
    if not smiles or strike <= 0:
        return None
    t = (close_ms - now_ms) / 1000 / SECONDS_PER_YEAR
    if t <= 0:
        return None
    exps = sorted(smiles)
    after = [e for e in exps if e >= close_ms]
    before = [e for e in exps if e < close_ms]
    if not after:
        return None
    hi = smiles[after[0]]
    iv_hi, sl_hi = iv_at(hi["pts"], strike)
    if before:
        lo = smiles[before[-1]]
        iv_lo, sl_lo = iv_at(lo["pts"], strike)
        w = (t - lo["t"]) / (hi["t"] - lo["t"]) if hi["t"] > lo["t"] else 1.0
        var = (1 - w) * iv_lo ** 2 * lo["t"] + w * iv_hi ** 2 * hi["t"]
        sigma = math.sqrt(max(var, 1e-12) / t)
        slope = (1 - w) * sl_lo + w * sl_hi
        fwd = (1 - w) * lo["F"] + w * hi["F"]
    else:
        sigma, slope, fwd = iv_hi, sl_hi, hi["F"]
    sq = sigma * math.sqrt(t)
    d1 = (math.log(fwd / strike) + 0.5 * sq * sq) / sq
    d2 = d1 - sq
    vega = fwd * norm_pdf(d1) * math.sqrt(t)
    p = norm_cdf(d2) - vega * slope
    return min(1.0, max(0.0, p))


def kalshi_price(m: dict, key: str) -> float | None:
    v = m.get(f"{key}_dollars")
    try:
        if v not in (None, ""):
            return float(v)
        return float(m[key]) / 100.0 if m.get(key) is not None else None
    except (TypeError, ValueError):
        return None


def iso_ms(s: str | None) -> int | None:
    try:
        return int(datetime.fromisoformat((s or "").replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


def candidates(markets: list, smiles: dict, now_ms: int) -> list:
    out = []
    for m in markets:
        st = (m.get("strike_type") or "").lower()
        close_ms = iso_ms(m.get("close_time"))
        if not close_ms or close_ms <= now_ms or close_ms - now_ms > MAX_DAYS * 86_400_000:
            continue
        if st in ("greater", "greater_or_equal") and m.get("floor_strike") is not None:
            k = float(m["floor_strike"])
            p_yes = p_above(smiles, k, close_ms, now_ms)
        elif st in ("less", "less_or_equal") and m.get("cap_strike") is not None:
            k = float(m["cap_strike"])
            pa = p_above(smiles, k, close_ms, now_ms)
            p_yes = None if pa is None else 1.0 - pa
        else:
            continue
        if p_yes is None:
            continue
        for side, ask, fair in (("YES", kalshi_price(m, "yes_ask"), p_yes),
                                ("NO", kalshi_price(m, "no_ask"), 1.0 - p_yes)):
            if ask is None or not (MIN_ASK <= ask <= MAX_ASK):
                continue
            ev = fair - ask - fee_rate_per_contract(ask)
            out.append(dict(ticker=m.get("ticker"), side=side, ask=ask, fair=round(fair, 4), ev=round(ev, 4),
                            strike=k, close_ms=close_ms, ts_ms=now_ms))
    return out


def snapshot(http: HttpClient) -> tuple[list, dict]:
    now_ms = int(time.time() * 1000)
    found, info = [], {}
    for coin, series in COINS.items():
        body = http.get(f"{DERIBIT}/get_book_summary_by_currency?currency={coin}&kind=option") or {}
        smiles = build_smiles(body.get("result") or [], now_ms)
        markets, cursor = [], None
        for _ in range(10):
            q = {"series_ticker": series, "status": "open", "limit": "1000"}
            if cursor:
                q["cursor"] = cursor
            page = http.get(f"{KALSHI}/markets?{urllib.parse.urlencode(q)}") or {}
            markets += page.get("markets") or []
            cursor = page.get("cursor") or None
            if not cursor:
                break
        c = candidates(markets, smiles, now_ms)
        info[coin] = dict(expiries=len(smiles), markets=len(markets), priced=len(c),
                          flagged=sum(1 for x in c if x["ev"] >= MARGIN))
        found += c
    return found, info


def record(log: Path, minutes: float, every: float) -> None:
    http = HttpClient(rps=8.0, retries=3)
    seen = set()
    if log.exists():
        for line in log.read_text().splitlines():
            try:
                e = json.loads(line)
                seen.add((e["ticker"], e["side"]))
            except (ValueError, KeyError):
                pass
    t_end = time.time() + minutes * 60
    while True:
        try:
            cands, info = snapshot(http)
        except Exception as e:  # noqa: BLE001
            print("snapshot failed:", e)
            cands, info = [], {}
        new = [c for c in cands if c["ev"] >= MARGIN and (c["ticker"], c["side"]) not in seen]
        with open(log, "a") as f:
            for c in new:
                seen.add((c["ticker"], c["side"]))
                f.write(json.dumps(c) + "\n")
        evs = sorted((c["ev"] for c in cands), reverse=True)
        print(datetime.now(timezone.utc).strftime("%H:%M"), json.dumps(info), "new flags", len(new),
              "top EVs", [round(x, 3) for x in evs[:5]], flush=True)
        if time.time() + every > t_end:
            break
        time.sleep(every)


def score(log: Path) -> str:
    http = HttpClient(rps=8.0, retries=3)
    entries = [json.loads(x) for x in log.read_text().splitlines() if x.strip()] if log.exists() else []
    now_ms = int(time.time() * 1000)
    results: dict = {}
    for e in entries:
        tk = e["ticker"]
        if tk in results or e["close_ms"] > now_ms - 120_000:
            continue
        body = http.get(f"{KALSHI}/markets/{urllib.parse.quote(tk)}") or {}
        res = ((body.get("market") or {}).get("result") or "").lower()
        if res in ("yes", "no"):
            results[tk] = res
    done = []
    for e in entries:
        res = results.get(e["ticker"])
        if res is None:
            continue
        won = (res == "yes") == (e["side"] == "YES")
        done.append(dict(e, won=won, pnl=(1.0 if won else 0.0) - e["ask"] - fee_rate_per_contract(e["ask"]),
                         day=datetime.fromtimestamp(e["close_ms"] / 1000, tz=timezone.utc).strftime("%Y-%m-%d")))
    L = ["# Kalshi daily vs Deribit options: flagged sides, settled", "",
         f"Logged flags: {len(entries)} · settled: {len(done)} · rule: ask {MIN_ASK:.2f}–{MAX_ASK:.2f}, "
         f"options fair − ask − taker fee ≥ {MARGIN:.2f}, first sighting per ticker/side, 1 contract.", ""]
    if not done:
        return "\n".join(L + ["Nothing settled yet."])
    n = len(done)
    tot = sum(d["pnl"] for d in done)
    by_day: dict = {}
    for d in done:
        by_day.setdefault(d["day"], []).append(d["pnl"])
    days = list(by_day)
    rng = random.Random(7)
    means = []
    for _ in range(4000):
        xs = [x for _ in days for x in by_day[days[rng.randrange(len(days))]]]
        means.append(sum(xs) / len(xs))
    means.sort()
    L += [f"| bets | win | avg ask | avg predicted EV | realized $/contract | 95% CI (day bootstrap) | days |",
          "|---|---|---|---|---|---|---|",
          f"| {n} | {sum(d['won'] for d in done) / n * 100:.1f}% | {sum(d['ask'] for d in done) / n * 100:.1f}¢ | "
          f"{sum(d['ev'] for d in done) / n * 100:+.1f}¢ | {tot / n * 100:+.2f}¢ | "
          f"[{means[100] * 100:+.2f}¢, {means[3899] * 100:+.2f}¢] | {len(days)} |"]
    for lo, hi in ((0.03, 0.06), (0.06, 0.10), (0.10, 1.0)):
        sub = [d for d in done if lo <= d["ev"] < hi]
        if sub:
            L.append(f"\nPredicted EV {lo * 100:.0f}–{hi * 100:.0f}¢: {len(sub)} bets, realized "
                     f"{sum(d['pnl'] for d in sub) / len(sub) * 100:+.2f}¢/contract")
    return "\n".join(L)


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--log", required=True)
    ap.add_argument("--minutes", type=float, default=330)
    ap.add_argument("--every", type=float, default=600)
    ap.add_argument("--score", action="store_true")
    a = ap.parse_args(argv)
    if a.score:
        print(score(Path(a.log)))
    else:
        record(Path(a.log), a.minutes, a.every)
    return 0


if __name__ == "__main__":
    sys.exit(main())
