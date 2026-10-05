#!/usr/bin/env python3
"""Historical calibration of Kalshi daily high-temperature markets.

Question: are settled Kalshi "highest temperature in <city>" bucket prices
calibrated (does a 10¢ bucket win ~10% of the time?), is there a
favorite-longshot bias across buckets, and does a free, no-look-ahead forecast
(Open-Meteo previous-runs archive) beat the market after the taker fee?
Read-only research; never places orders.

PRE-REGISTERED DESIGN (fixed before any real-data result was seen):
  Data       Settled markets of every discovered high-temperature series
             (GET /series?category=Climate and Weather, seed fallback), last --days
             (default 45) days; hourly candlesticks (period_interval=60).
  Snapshots  EVE     = 18:00 local clock time the day before the market's date
             MORNING = 10:00 local clock time on the market's date
             Quote = last hourly candle ending at or before the snapshot (≤ 6 h stale),
             yes_bid/yes_ask closes; mid = (bid + ask) / 2; rows need both sides.
  Calibration  mid-price bins 0-5-10-20-35-50-65-80-90-95-100¢: realised YES rate − mean mid.
  Rules (taker, all-in cost per contract for 10 contracts via
         tools/backtest/pipeline.kalshi_total_cost, i.e. exact fee rounding):
    R1 LONGSHOT_NO   YES bid in [0.05, 0.20]                       -> buy NO at 1 − bid
    R2 FAVORITE_YES  highest-mid bucket of each event, ask ≤ 0.90   -> buy YES at ask
    R3 FORECAST      (only on the chronologically LATER half of dates)
                     forecast max f = max of Open-Meteo hourly temperature_2m over the climate
                     day, from the run issued ≥1 day (MORNING: previous_day1) or ≥2 days (EVE:
                     previous_day2) before the valid time; μ = f + bias, σ = sd of residuals, both
                     fitted on the EARLIER half (pooled cities, σ ≥ 1.5 °F); p(bucket) =
                     Φ((hi+0.5−μ)/σ) − Φ((lo−0.5−μ)/σ); buy YES if p − cost_yes ≥ 0.05,
                     else buy NO if (1−p) − cost_no ≥ 0.05.
  Statistics  95% CIs by day-block bootstrap (2000 resamples of calendar dates, all
              cities/buckets of a date move together), seed 7; no CI with < 5 dates. Six rule×snapshot tests
              are run: treat any single CI excluding 0 with multiple-testing caution.
  Outcome temperature (R3 fitting only): market expiration_value when numeric, else the
              midpoint of the YES bucket (tails: edge ± 1) — flagged as approximate.

Climate day = local STANDARD time midnight..midnight (NWS CLI convention).
Usage:
  python3 tools/research/weather_study.py --days 45
  python3 tools/research/weather_study.py --fixture tools/research/fixtures/weather --end 2025-10-04
Markdown report on stdout (and --out). Exit code 0 even on API errors (listed in report).
"""
from __future__ import annotations

import argparse
import math
import random
import statistics
import sys
import time
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import weather_common as wc  # noqa: E402

BINS = (0.0, 0.05, 0.10, 0.20, 0.35, 0.50, 0.65, 0.80, 0.90, 0.95, 1.0001)
SNAPS = (("EVE", -1, 18, "temperature_2m_previous_day2"), ("MORNING", 0, 10, "temperature_2m_previous_day1"))
SIZE = 10
R1_LO, R1_HI = 0.05, 0.20
R2_MAX_ASK = 0.90
R3_EDGE = 0.05
SIGMA_MIN = 1.5
BOOT = 2000
SEED = 7
MIN_CI_DAYS = 5


def phi(x: float) -> float:
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def bucket_prob(lo, hi, mu: float, sd: float) -> float:
    up = 1.0 if hi is None else phi((hi + 0.5 - mu) / sd)
    dn = 0.0 if lo is None else phi((lo - 0.5 - mu) / sd)
    return max(0.0, min(1.0, up - dn))


def outcome_temp(m: dict, lo, hi) -> tuple:
    v = wc.fnum(m.get("expiration_value"))
    if v is not None:
        return v, False
    if (m.get("result") or "").lower() != "yes":
        return None, True
    if lo is not None and hi is not None:
        return (lo + hi) / 2.0, True
    if lo is not None:
        return lo + 1.0, True
    if hi is not None:
        return hi - 1.0, True
    return None, True


def boot_ci(rows: list, stat, n: int = BOOT, seed: int = SEED, key=lambda r: r["date"]) -> tuple:
    """Day-block bootstrap percentile 95% CI of stat(rows)."""
    blocks = defaultdict(list)
    for r in rows:
        blocks[key(r)].append(r)
    keys = sorted(blocks)
    if len(keys) < MIN_CI_DAYS:
        return (None, None)
    rng = random.Random(seed)
    vals = []
    for _ in range(n):
        sample = []
        for _k in keys:
            sample.extend(blocks[keys[rng.randrange(len(keys))]])
        v = stat(sample)
        if v is not None:
            vals.append(v)
    if len(vals) < n // 2:
        return (None, None)
    vals.sort()
    return (vals[int(0.025 * (len(vals) - 1))], vals[int(0.975 * (len(vals) - 1))])


def mean_or_none(xs):
    xs = list(xs)
    return sum(xs) / len(xs) if xs else None


# --------------------------------------------------------------------------- data


def load(client, days: int, end: datetime, log, use_forecast: bool = True, budget_s: float = 1800.0) -> dict:
    errors, notes = [], []
    t0 = time.monotonic()
    series, dnotes = wc.discover_series(client, log)
    notes += dnotes
    end_s = int(end.timestamp())
    start_s = end_s - days * 86400
    cut = wc.parse_ts((client.cutoff() or {}).get("market_settled_ts"))
    cut_s = int(cut.timestamp()) if cut else 0
    mapping, stations, markets = [], {}, []
    for s in series:
        t = s["ticker"]
        try:
            ms = client.settled_markets(t, max(start_s, cut_s), end_s)
            if start_s < cut_s:
                ms += client.settled_markets(t, start_s, cut_s, historical=True)
        except Exception as e:  # noqa: BLE001
            errors.append(f"{t}: settled markets: {e}")
            ms = []
        if not s.get("settlement_sources"):
            try:
                full = client.series(t)
                if full:
                    s = {**s, **full}
            except Exception:  # noqa: BLE001
                pass
        mp = wc.station_for(s, ms[0] if ms else None)
        mapping.append(dict(ticker=t, title=s.get("title") or "", n=len(ms), **mp,
                            rules=wc.excerpt((ms[0] if ms else {}).get("rules_primary") or "", 240)))
        if not mp["station"]:
            continue
        if mp["station"] not in stations:
            stations[mp["station"]] = wc.station_info(client, mp["station"])
        seen = set()
        for m in ms:
            tk = m.get("ticker")
            res = (m.get("result") or "").lower()
            d = wc.event_date(m.get("event_ticker") or tk)
            if not tk or tk in seen or res not in ("yes", "no") or d is None:
                continue
            ws, _ = wc.climate_window(d, stations[mp["station"]].tz)
            if not (start_s - 86400 <= ws.timestamp() <= end_s):
                continue
            seen.add(tk)
            lo, hi, basis = wc.bucket_interval(m)
            if lo is None and hi is None:
                notes.append(f"{tk}: bucket unparsed ({basis})")
                continue
            markets.append(dict(series=t, ticker=tk, event=m.get("event_ticker") or tk.rsplit("-", 1)[0],
                                date=d, station=mp["station"], lo=lo, hi=hi, y=1 if res == "yes" else 0,
                                temp=outcome_temp(m, lo, hi), raw=m))
    log(f"settled markets: {len(markets)}")
    # newest dates first, so a time-budget cut keeps every series' recent days
    markets.sort(key=lambda m: (m["date"], m["series"], m["ticker"]), reverse=True)

    rows = []
    for i, m in enumerate(markets):
        if time.monotonic() - t0 > budget_s:
            notes.append(f"time budget {budget_s:.0f}s hit: candles fetched for {i}/{len(markets)} markets "
                         f"(newest first); older markets have no quotes")
            break
        info = stations[m["station"]]
        times = {name: wc.local_clock(m["date"] + timedelta(days=off), hr, info.tz) for name, off, hr, _ in SNAPS}
        lo_s = int(min(times.values()).timestamp()) - 7 * 3600
        hi_s = int(max(times.values()).timestamp()) + 60
        try:
            cs = client.candles(m["series"], m["ticker"], lo_s, hi_s, 60, historical=False)
            if not cs and cut_s and int(wc.climate_window(m["date"], info.tz)[1].timestamp()) < cut_s:
                cs = client.candles(m["series"], m["ticker"], lo_s, hi_s, 60, historical=True)
        except Exception as e:  # noqa: BLE001
            errors.append(f"{m['ticker']}: candles: {e}")
            continue
        for name, *_ in SNAPS:
            yb, ya = wc.quote_at(cs, int(times[name].timestamp()))
            if yb is None or ya is None or ya < yb:
                continue
            rows.append(dict(snap=name, date=m["date"], series=m["series"], event=m["event"], ticker=m["ticker"],
                             station=m["station"], lo=m["lo"], hi=m["hi"], y=m["y"], bid=yb, ask=ya,
                             mid=(yb + ya) / 2.0, temp=m["temp"][0], temp_approx=m["temp"][1]))
        if (i + 1) % 200 == 0:
            log(f"candles {i + 1}/{len(markets)}")

    fc = {}
    if use_forecast and markets:
        dates = sorted({m["date"] for m in markets})
        for sid, info in stations.items():
            if info.lat is None or info.lon is None:
                errors.append(f"{sid}: no coordinates for forecast")
                continue
            try:
                body = client.previous_runs(info.lat, info.lon, (dates[0] - timedelta(days=1)).isoformat(),
                                            (dates[-1] + timedelta(days=1)).isoformat())
            except Exception as e:  # noqa: BLE001
                errors.append(f"{sid}: Open-Meteo: {e}")
                continue
            hourly = (body or {}).get("hourly") or {}
            times = hourly.get("time") or []
            for _, _, _, var in SNAPS:
                vals = hourly.get(var) or []
                series_pts = []
                for ts, v in zip(times, vals):
                    t = wc.parse_ts(ts + ("Z" if len(ts) <= 16 else ""))
                    if t is not None and v is not None:
                        series_pts.append((t, float(v)))
                for d in dates:
                    ws, we = wc.climate_window(d, info.tz)
                    pts = [v for t, v in series_pts if ws <= t < we]
                    if len(pts) >= 20:
                        fc[(sid, d, var)] = max(pts)
    return dict(mapping=mapping, stations=stations, markets=markets, rows=rows, fc=fc,
                errors=errors, notes=notes, start=start_s, end=end_s)


# --------------------------------------------------------------------------- analyses


def calibration(rows: list) -> list:
    out = []
    for a, b in zip(BINS[:-1], BINS[1:]):
        rs = [r for r in rows if a <= r["mid"] < b]
        if not rs:
            out.append(dict(bin=(a, b), n=0))
            continue
        stat = lambda s, a=a, b=b: (mean_or_none(r["y"] for r in s) - mean_or_none(r["mid"] for r in s)) if s else None  # noqa: E731
        lo, hi = boot_ci(rs, stat)
        out.append(dict(bin=(a, b), n=len(rs), days=len({r["date"] for r in rs}),
                        mid=mean_or_none(r["mid"] for r in rs), rate=mean_or_none(r["y"] for r in rs),
                        diff=stat(rs), lo=lo, hi=hi))
    return out


def rule_trades(rows: list, rule: str, model=None) -> list:
    trades = []
    if rule == "R1":
        for r in rows:
            if R1_LO <= r["bid"] <= R1_HI:
                px = round(1.0 - r["bid"], 4)
                trades.append(dict(r, side="NO", px=px, pnl=(1 - r["y"]) - wc.per_contract_cost(px, SIZE)))
    elif rule == "R2":
        by = defaultdict(list)
        for r in rows:
            by[r["event"]].append(r)
        for evs in by.values():
            fav = max(evs, key=lambda r: r["mid"])
            if fav["ask"] <= R2_MAX_ASK:
                trades.append(dict(fav, side="YES", px=fav["ask"], pnl=fav["y"] - wc.per_contract_cost(fav["ask"], SIZE)))
    elif rule == "R3" and model:
        for r in rows:
            p = model(r)
            if p is None:
                continue
            cy = wc.per_contract_cost(r["ask"], SIZE)
            cn = wc.per_contract_cost(round(1.0 - r["bid"], 4), SIZE)
            if p - cy >= R3_EDGE:
                trades.append(dict(r, side="YES", px=r["ask"], p=p, pnl=r["y"] - cy))
            elif (1 - p) - cn >= R3_EDGE:
                trades.append(dict(r, side="NO", px=round(1.0 - r["bid"], 4), p=p, pnl=(1 - r["y"]) - cn))
    return trades


def summarize(trades: list) -> dict:
    if not trades:
        return dict(n=0)
    mean = lambda s: mean_or_none(t["pnl"] for t in s)  # noqa: E731
    lo, hi = boot_ci(trades, mean)
    wins = sum(1 for t in trades if t["pnl"] > 0)
    return dict(n=len(trades), days=len({t["date"] for t in trades}), win=wins / len(trades),
                px=mean_or_none(t["px"] for t in trades), pnl=mean(trades), lo=lo, hi=hi,
                total=sum(t["pnl"] for t in trades) * SIZE)


def fit_forecast(data: dict, snap: str, var: str) -> dict:
    rows = [r for r in data["rows"] if r["snap"] == snap]
    dates = sorted({r["date"] for r in rows})
    if len(dates) < 4:
        return dict(ok=False, why=f"only {len(dates)} dates")
    split = dates[len(dates) // 2]
    resid = {}
    for m in data["markets"]:
        f = data["fc"].get((m["station"], m["date"], var))
        t = m["temp"][0]
        if f is None or t is None or m["date"] >= split:
            continue
        resid[(m["station"], m["date"])] = t - f
    if len(resid) < 5:
        return dict(ok=False, why=f"only {len(resid)} training station-days with forecast + outcome")
    vals = list(resid.values())
    bias = statistics.fmean(vals)
    sd = max(SIGMA_MIN, statistics.pstdev(vals))
    approx = sum(1 for m in data["markets"] if m["temp"][1]) / max(1, len(data["markets"]))

    def model(r):
        if r["date"] < split:
            return None
        f = data["fc"].get((r["station"], r["date"], var))
        return None if f is None else bucket_prob(r["lo"], r["hi"], f + bias, sd)

    test = [r for r in rows if model(r) is not None]
    brier_mkt = mean_or_none((r["mid"] - r["y"]) ** 2 for r in test)
    brier_mod = mean_or_none((model(r) - r["y"]) ** 2 for r in test)
    return dict(ok=True, split=split, bias=bias, sd=sd, n_train=len(vals), model=model, test=len(test),
                brier_mkt=brier_mkt, brier_mod=brier_mod, approx=approx,
                test_days=len({r["date"] for r in test}))


# --------------------------------------------------------------------------- report


def _f(x, nd=3, pct=False):
    if x is None:
        return "–"
    return f"{x * 100:.1f}%" if pct else f"{x:.{nd}f}"


def _ci(lo, hi, nd=3, pct=False):
    if lo is None:
        return "–"
    flag = " **excl. 0**" if (lo > 0 or hi < 0) else ""
    return f"[{_f(lo, nd, pct)}, {_f(hi, nd, pct)}]{flag}"


def render(data: dict, args) -> str:
    L = ["# Weather study: calibration of Kalshi daily high-temperature markets", ""]
    s0 = datetime.fromtimestamp(data["start"], timezone.utc).date()
    s1 = datetime.fromtimestamp(data["end"], timezone.utc).date()
    L.append(f"Window {s0} → {s1} ({args.days} days). Settled markets {len(data['markets'])}, "
             f"snapshot quotes {len(data['rows'])}, dates {len({m['date'] for m in data['markets']})}. "
             "Rules and thresholds were fixed in the module docstring before results. Read-only.")
    L += ["", "## Series → station mapping", "",
          "| series | title | settled mkts | station | basis | tz | rules excerpt |", "|---|---|---:|---|---|---|---|"]
    for m in data["mapping"]:
        info = data["stations"].get(m["station"]) if m["station"] else None
        L.append(f"| {m['ticker']} | {m['title']} | {m['n']} | {m['station'] or '**none**'} | {m['basis']} | "
                 f"{info.tz if info else '–'} | {(m['rules'] or m['source'] or '').replace('|', '/')} |")
    for n in data["notes"][:30]:
        L.append(f"- {n}")

    for name, _, hr, var in SNAPS:
        rows = [r for r in data["rows"] if r["snap"] == name]
        L += ["", f"## {name} snapshot ({hr:02d}:00 local{' day before' if name == 'EVE' else ''})", ""]
        if not rows:
            L.append("_no quotes_")
            continue
        ev = defaultdict(list)
        for r in rows:
            ev[r["event"]].append(r)
        full = [sum(x["mid"] for x in v) for v in ev.values()]
        asks = [sum(x["ask"] for x in v) for v in ev.values()]
        L.append(f"Quotes {len(rows)} across {len(ev)} events / {len({r['date'] for r in rows})} dates. "
                 f"Mean Σmid per event {_f(mean_or_none(full))}, mean Σask {_f(mean_or_none(asks))} "
                 "(1.000 = no overround; events may be missing buckets without quotes).")
        L += ["", "### Calibration (realised YES rate − mean mid; + means the market under-prices YES)", "",
              "| mid bin | n | days | mean mid | YES rate | diff | 95% CI (day-block) |", "|---|---:|---:|---:|---:|---:|---|"]
        for c in calibration(rows):
            a, b = c["bin"]
            lab = f"{a * 100:.0f}–{min(b, 1) * 100:.0f}¢"
            if not c["n"]:
                L.append(f"| {lab} | 0 | | | | | |")
                continue
            L.append(f"| {lab} | {c['n']} | {c['days']} | {_f(c['mid'])} | {_f(c['rate'])} | {_f(c['diff'], 3)} | "
                     f"{_ci(c['lo'], c['hi'])} |")
        L += ["", "### Pre-registered rules (taker at the snapshot quote, 10 contracts, exact fee)", "",
              "| rule | trades | days | win% | avg price | mean P&L $/ct | 95% CI $/ct | total $ (10-ct trades) |",
              "|---|---:|---:|---:|---:|---:|---|---:|"]
        fit = fit_forecast(data, name, var) if not args.no_forecast else dict(ok=False, why="--no-forecast")
        rules = [("R1 longshot NO (bid 5–20¢)", rule_trades(rows, "R1")),
                 ("R2 favorite YES (ask ≤ 90¢)", rule_trades(rows, "R2"))]
        if fit["ok"]:
            rules.append((f"R3 forecast edge ≥ {R3_EDGE:.2f} (test half ≥ {fit['split']})",
                          rule_trades(rows, "R3", fit["model"])))
        for lab, tr in rules:
            s = summarize(tr)
            if not s["n"]:
                L.append(f"| {lab} | 0 | | | | | | |")
                continue
            L.append(f"| {lab} | {s['n']} | {s['days']} | {_f(s['win'], pct=True)} | {_f(s['px'])} | "
                     f"{_f(s['pnl'], 4)} | {_ci(s['lo'], s['hi'], 4)} | {s['total']:.2f} |")
        if fit["ok"]:
            L.append("")
            L.append(f"Forecast ({var}, Open-Meteo previous-runs): bias {fit['bias']:+.2f}°F, σ {fit['sd']:.2f}°F from "
                     f"{fit['n_train']} training station-days (dates < {fit['split']}); test quotes {fit['test']} over "
                     f"{fit['test_days']} dates. Brier market mid {_f(fit['brier_mkt'], 4)} vs model "
                     f"{_f(fit['brier_mod'], 4)} (lower is better). Outcome temps approximated from the YES bucket for "
                     f"{fit['approx'] * 100:.0f}% of markets.")
        else:
            L += ["", f"Forecast comparison skipped: {fit['why']}."]

    L += ["", "## API errors", ""] + ([f"- {e}" for e in data["errors"][:60]] or ["_none_"])
    if len(data["errors"]) > 60:
        L.append(f"- … {len(data['errors']) - 60} more")
    L += ["", "## Caveats", "",
          "- Candle closes are the last quote in each hour, not a guaranteed fill; depth is not modelled. "
          "Hours with no quote on one side are dropped (biases toward liquid buckets).",
          "- Day-block bootstrap resamples calendar dates; cities on the same date are correlated (shared weather "
          "regimes), and buckets of one event are mutually exclusive, so per-trade counts overstate information.",
          "- Six rule×snapshot tests plus 20 calibration bins: expect ~1 spurious 'excl. 0' at 95%.",
          "- Climate day is local standard time (01:00–01:00 clock time under DST); the forecast max is taken over "
          "that window. Hourly model temps miss the peak and model 2 m temps differ from the ASOS sensor; the fitted "
          "bias absorbs only the average gap.",
          "- Open-Meteo previous_dayN = value from the model run ~N×24 h before each valid hour; exact issue/availability "
          "latency is not modelled, so the 'no look-ahead' claim is approximate at the hour level.",
          "- Settlement is the final NWS CLI; later corrections are whatever Kalshi used (market result is ground truth here).",
          "- No profit is claimed. A CI excluding 0 on this window is a lead to re-test on new dates, not an edge."]
    return "\n".join(L) + "\n"


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--days", type=int, default=45)
    ap.add_argument("--end", help="end date/time (ISO, UTC); default now")
    ap.add_argument("--no-forecast", action="store_true")
    ap.add_argument("--rps", type=float, default=8.0)
    ap.add_argument("--budget", type=float, default=1800.0, help="seconds allowed for candle fetching")
    ap.add_argument("--fixture", type=Path)
    ap.add_argument("--out", type=Path)
    a = ap.parse_args(argv)
    log = lambda s: print(s, file=sys.stderr, flush=True)  # noqa: E731
    end = wc.parse_ts(a.end) if a.end else datetime.now(timezone.utc)
    if end is None:
        end = datetime.now(timezone.utc)
    client = wc.FixtureClient(a.fixture) if a.fixture else wc.LiveClient(rps=a.rps)
    try:
        data = load(client, a.days, end, log, not a.no_forecast, a.budget)
    except Exception as e:  # noqa: BLE001
        data = dict(mapping=[], stations={}, markets=[], rows=[], fc={}, errors=[f"load failed: {e}"], notes=[],
                    start=int(end.timestamp()) - a.days * 86400, end=int(end.timestamp()))
    report = render(data, a)
    if a.out:
        a.out.write_text(report)
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
