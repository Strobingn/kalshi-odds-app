#!/usr/bin/env python3
"""Live scanner: Kalshi daily high-temperature buckets that are (nearly) decided but still open.

Idea. A "highest temperature in <city> today" market settles on the NWS Daily
Climate Report (CLI) for one station. The CLI maximum can only be >= what the
station has already reported today, so once the observed maximum has passed a
bucket's cap, that bucket's YES is (nearly) impossible; a ">= X" bucket whose X
has already been reached is (nearly) certain. If the losing side is still bid /
the winning side still offered below $1 after the exact taker fee, that is a
near-riskless trade. This tool only measures whether such prices exist. It is
read-only: it never places orders.

PRE-REGISTERED RULE (fixed before looking at any live result):
  Climate day       00:00-24:00 local STANDARD time at the station (NWS CLI convention;
                    = 01:00-01:00 local clock time during daylight saving).
  CLI floor F       smallest whole degF consistent with the highest reading inside the
                    climate day: METAR T-group (tenths degC, +-0.05), else API value
                    (+-0.1 decimal, +-0.5 whole degC), plus 6-hour max groups (1sTTT)
                    whose 6 h window lies inside the day. QC flags X/Q/B dropped.
  Margin m          MARGIN_F = 1 degF safety margin (sensor/QC/CLI-correction risk).
  NO locked         bucket [lo, hi] with F >= hi + 1 + m      -> buy NO is the "decided" side
  YES locked        open-top bucket [lo, inf) with F >= lo + m -> buy YES
  CLI-final         if today's final CLI is already published (overnight report on the
                    completed day) its value decides every bucket exactly (m = 0).
  Edge              1 - all-in cost per contract at the ask for 10 contracts using
                    tools/backtest/pipeline.kalshi_total_cost (fee = ceil-to-cent of
                    0.07*C*P*(1-P)); flagged when >= MIN_EDGE = $0.01/contract.
  Lean NO (NOT risk-free, reported separately): after 17:00 local standard time with the
                    latest reading >= 4 degF below the day's max, or the climate day is over:
                    buckets with lo >= est_hi + 2 (est_hi = highest whole degF consistent
                    with any reading). Can lose if a later/unsampled peak is higher.
  Depth             for flagged rows, the order book is walked level by level (each level
                    costed separately, rounded up), counting contracts whose all-in cost
                    still leaves >= MIN_EDGE.

Station mapping: read from the series' settlement_sources URL (issuedby=XXX on the
NWS CLI product link -> K+XXX) or, failing that, from the market's rules text; the
basis is printed in the report. Series are discovered via
GET /series?category=Climate and Weather with a seed-list fallback.

Usage:
  python3 tools/research/weather_scan.py                         # one snapshot, live
  python3 tools/research/weather_scan.py --repeat 6 --interval 300
  python3 tools/research/weather_scan.py --fixture tools/research/fixtures/weather --now 2025-10-05T22:30:00Z
Markdown report on stdout (and --out); progress on stderr. Exit code 0 even on API errors
(they are listed in the report).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import weather_common as wc  # noqa: E402
from arb_scan import fills_cost, parse_orderbook, top_quote  # noqa: E402

MARGIN_F = 1
MIN_EDGE = 0.01
SIZE = 10
LEAN_HOUR_LST = 17
LEAN_DROP_F = 4.0
LEAN_GAP_F = 2
LIVE = ("active", "open", "")


def side_ask(side: str, yb, ya):
    if side == "YES":
        return ya
    return round(1.0 - yb, 4) if yb is not None else None


def edge_at(ask, size: int = SIZE, rate: float = wc.FEE_RATE):
    if ask is None:
        return None
    return round(1.0 - wc.per_contract_cost(ask, size, rate), 4)


def classify(lo, hi, floor, margin: int = MARGIN_F, exact: int | None = None) -> str:
    """OPEN / NO_LOCKED / YES_LOCKED / CLI_YES / CLI_NO for integer bucket [lo, hi]."""
    if lo is None and hi is None:
        return "UNPARSED"
    if exact is not None:
        inside = (lo is None or exact >= lo) and (hi is None or exact <= hi)
        return "CLI_YES" if inside else "CLI_NO"
    if floor is None:
        return "OPEN"
    if hi is not None and floor >= hi + 1 + margin:
        return "NO_LOCKED"
    if hi is None and lo is not None and floor >= lo + margin:
        return "YES_LOCKED"
    return "OPEN"


def locked_side(state: str) -> str | None:
    return {"NO_LOCKED": "NO", "CLI_NO": "NO", "YES_LOCKED": "YES", "CLI_YES": "YES", "LEAN_NO": "NO"}.get(state)


def book_depth(client, ticker: str, side: str, min_edge: float, rate: float) -> dict:
    """Contracts purchasable on `side` with all-in edge >= min_edge, level by level."""
    book = parse_orderbook(client.orderbook(ticker))
    if book is None:
        return dict(depth=None, profit=None, note="no book")
    asks = book.yes_asks() if side == "YES" else book.no_asks()
    n, cost = 0, 0.0
    for p, q in asks:
        c = fills_cost([(p, q)], rate)
        if 1.0 - c / q < min_edge:
            break
        n += q
        cost += c
    return dict(depth=n, profit=round(n - cost, 2), note="")


def lst_hour(now: datetime, tz: str) -> float:
    off = wc.std_offset(tz, now.date())
    t = now + off
    return t.hour + t.minute / 60.0


def snapshot(client, ctx: dict, now: datetime, margin: int = MARGIN_F, min_edge: float = MIN_EDGE,
             rate: float = wc.FEE_RATE, use_cli: bool = True, max_books: int = 60, log=lambda s: None) -> dict:
    rows, events_out, errors = [], [], []
    obs_cache, cli_cache = {}, {}
    books = 0
    for sm in ctx["series"]:
        st = sm["station"]
        if not st:
            continue
        info = ctx["stations"][st]
        try:
            events = client.events(sm["ticker"], "open")
        except Exception as e:  # noqa: BLE001
            errors.append(f"{sm['ticker']}: events: {e}")
            continue
        for ev in events:
            et = ev.get("event_ticker") or ""
            d = wc.event_date(et)
            markets = [m for m in (ev.get("markets") or []) if (m.get("status") or "") in LIVE]
            if d is None or not markets:
                continue
            ws, we = wc.climate_window(d, info.tz)
            if now < ws:
                events_out.append(dict(event=et, series=sm["ticker"], station=st, date=str(d), status="not started"))
                continue
            key = (st, d)
            if key not in obs_cache:
                try:
                    feats = client.observations(st, ws - timedelta(minutes=10), min(we, now) + timedelta(minutes=10))
                    obs_cache[key] = wc.obs_summary(feats, ws, we)
                except Exception as e:  # noqa: BLE001
                    errors.append(f"{st}: observations: {e}")
                    obs_cache[key] = wc.obs_summary([], ws, we)
            o = obs_cache[key]
            floor, exact, cli_note = o["cli_floor"], None, ""
            if use_cli and info.cli:
                if info.cli not in cli_cache:
                    try:
                        cli_cache[info.cli] = [p for p in (wc.parse_cli(x.get("productText") or "")
                                                           for x in client.cli_products(info.cli)) if p]
                    except Exception as e:  # noqa: BLE001
                        errors.append(f"CLI {info.cli}: {e}")
                        cli_cache[info.cli] = []
                for c in cli_cache[info.cli]:
                    if c["date"] != d or c["max_f"] is None:
                        continue
                    if c["final"]:
                        exact = c["max_f"]
                        cli_note = f"final CLI {c['max_f']}°F"
                        break
                    floor = c["max_f"] if floor is None else max(floor, c["max_f"])
                    cli_note = f"prelim CLI ≥{c['max_f']}°F"
            day_over = now >= we
            hour = lst_hour(now, info.tz)
            lean_ok = day_over or (hour >= LEAN_HOUR_LST and o["latest_f"] is not None and o["max_f"] is not None
                                   and o["latest_f"] <= o["max_f"] - LEAN_DROP_F)
            n_locked = 0
            for m in markets:
                lo, hi, basis = wc.bucket_interval(m)
                state = classify(lo, hi, floor, margin, exact)
                if state == "UNPARSED":
                    errors.append(f"{m.get('ticker')}: bucket skipped ({basis})")
                    continue
                if state == "OPEN" and lean_ok and o["est_hi"] is not None and lo is not None \
                        and lo >= o["est_hi"] + LEAN_GAP_F:
                    state = "LEAN_NO"
                side = locked_side(state)
                if not side:
                    continue
                n_locked += state != "LEAN_NO"
                yb, ya = top_quote(m)
                ask = side_ask(side, yb, ya)
                row = dict(ts=wc.iso(now), series=sm["ticker"], event=et, ticker=m.get("ticker"), station=st,
                           bucket=wc.fmt_interval(lo, hi), bucket_basis=basis, state=state, side=side,
                           yes_bid=yb, yes_ask=ya, ask=ask, edge1=edge_at(ask, 1, rate), edge10=edge_at(ask, SIZE, rate),
                           floor=floor, exact=exact, est_hi=o["est_hi"], depth=None, profit=None)
                if row["edge10"] is not None and row["edge10"] >= min_edge and books < max_books:
                    try:
                        row.update(book_depth(client, m["ticker"], side, min_edge, rate))
                        books += 1
                    except Exception as e:  # noqa: BLE001
                        errors.append(f"{m.get('ticker')}: orderbook: {e}")
                rows.append(row)
            events_out.append(dict(event=et, series=sm["ticker"], station=st, date=str(d),
                                   status="day over" if day_over else f"{hour:.1f}h LST",
                                   n_obs=o["n"], max_f=o["max_f"], max_time=o["max_time"], floor=floor,
                                   est_hi=o["est_hi"], latest_f=o["latest_f"], latest_time=o["latest_time"],
                                   six=o["six_hour_groups"], support=o["support"], cli=cli_note,
                                   markets=len(markets), locked=n_locked, lean=lean_ok))
    return dict(ts=wc.iso(now), rows=rows, events=events_out, errors=errors, books=books)


def setup(client, log) -> dict:
    series, notes = wc.discover_series(client, log)
    out, stations = [], {}
    for s in series:
        t = s.get("ticker")
        sample = None
        try:
            evs = client.events(t, "open")
            for ev in evs:
                for m in ev.get("markets") or []:
                    sample = m
                    break
                if sample:
                    break
        except Exception as e:  # noqa: BLE001
            notes.append(f"{t}: events lookup failed: {e}")
        if not s.get("settlement_sources"):
            try:
                full = client.series(t)
                if full:
                    s = {**s, **full}
            except Exception:  # noqa: BLE001
                pass
        mp = wc.station_for(s, sample)
        out.append(dict(ticker=t, title=s.get("title") or "", station=mp["station"], cli=mp["cli"],
                        basis=mp["basis"], source=mp["source"],
                        rules=wc.excerpt((sample or {}).get("rules_primary") or "", 260),
                        fee=f"{s.get('fee_type') or '?'} x{s.get('fee_multiplier') if s.get('fee_multiplier') is not None else '?'}"))
        if mp["station"] and mp["station"] not in stations:
            info = wc.station_info(client, mp["station"])
            info.cli = mp["cli"]
            stations[mp["station"]] = info
    return dict(series=out, stations=stations, notes=notes)


def _p(x, nd=2):
    return "–" if x is None else (f"{x:.{nd}f}" if isinstance(x, float) else str(x))


def render(ctx: dict, snaps: list, args) -> str:
    L = ["# Weather scan: decided-but-still-open Kalshi high-temperature buckets", ""]
    L.append(f"Generated {wc.iso(datetime.now(timezone.utc))}. Snapshots: {len(snaps)}"
             + (f" ({snaps[0]['ts']} → {snaps[-1]['ts']})" if snaps else "") + ". Read-only; no orders placed.")
    L.append("")
    L.append(f"Pre-registered: margin {args.margin}°F, edge flag ≥ ${args.min_edge:.2f}/contract at {SIZE} contracts "
             f"after the exact taker fee (rate {args.fee_rate}), lean-NO after {LEAN_HOUR_LST}:00 LST with "
             f"≥{LEAN_DROP_F:.0f}°F drop and lo ≥ est_hi+{LEAN_GAP_F}. See the module docstring.")
    L += ["", "## Series → station mapping (from settlement source / rules text)", "",
          "| series | title | station | basis | tz (source) | fee | rules excerpt |", "|---|---|---|---|---|---|---|"]
    for s in ctx["series"]:
        info = ctx["stations"].get(s["station"]) if s["station"] else None
        tz = f"{info.tz} ({info.basis})" if info else "–"
        L.append(f"| {s['ticker']} | {s['title']} | {s['station'] or '**none**'} | {s['basis']} | {tz} | {s['fee']} | "
                 f"{(s['rules'] or s['source']).replace('|', '/')} |")
    for n in ctx["notes"]:
        L.append(f"- {n}")
    L += ["", "## Snapshots", "", "| ts | events in progress | locked buckets | locked with edge ≥ flag | lean-NO | books | errors |",
          "|---|---:|---:|---:|---:|---:|---:|"]
    for s in snaps:
        lk = [r for r in s["rows"] if r["state"] != "LEAN_NO"]
        L.append(f"| {s['ts']} | {sum(1 for e in s['events'] if e['status'] != 'not started')} | {len(lk)} | "
                 f"{sum(1 for r in lk if (r['edge10'] or -1) >= args.min_edge)} | "
                 f"{sum(1 for r in s['rows'] if r['state'] == 'LEAN_NO')} | {s['books']} | {len(s['errors'])} |")

    agg = {}
    for s in snaps:
        for r in s["rows"]:
            k = (r["ticker"], r["side"], r["state"])
            a = agg.setdefault(k, dict(r, first=r["ts"], last=r["ts"], seen=0, best_edge=None, best_depth=None,
                                       best_profit=None))
            a["last"] = r["ts"]
            a["seen"] += 1
            for f, src in (("best_edge", "edge10"), ("best_depth", "depth"), ("best_profit", "profit")):
                if r[src] is not None and (a[f] is None or r[src] > a[f]):
                    a[f] = r[src]
            a.update(yes_bid=r["yes_bid"], yes_ask=r["yes_ask"], floor=r["floor"])

    def table(rows, title, note):
        L.extend(["", f"## {title}", "", note, ""])
        if not rows:
            L.append("_none_")
            return
        L.append("| ticker | bucket | state | side | CLI floor / exact | last yes bid/ask | best edge $/ct @10 | "
                 "contracts with edge | $ if decided | seen | first → last |")
        L.append("|---|---|---|---|---|---|---:|---:|---:|---:|---|")
        for a in sorted(rows, key=lambda a: -(a["best_edge"] or -9)):
            fe = f"={a['exact']}" if a["exact"] is not None else f"≥{_p(a['floor'])}"
            L.append(f"| {a['ticker']} | {a['bucket']} | {a['state']} | {a['side']} | {fe} | "
                     f"{_p(a['yes_bid'])}/{_p(a['yes_ask'])} | {_p(a['best_edge'], 3)} | {_p(a['best_depth'])} | "
                     f"{_p(a['best_profit'])} | {a['seen']} | {a['first'][11:16]} → {a['last'][11:16]} |")

    locked = [a for a in agg.values() if a["state"] != "LEAN_NO"]
    flagged = [a for a in locked if (a["best_edge"] or -1) >= args.min_edge]
    table(flagged, "Locked buckets with edge (the target)",
          "Decided by observations (margin applied) or by the final CLI, and the decided side's ask leaves "
          f"≥ ${args.min_edge:.2f}/contract after fees. Depth = order-book contracts that still clear the bar.")
    table([a for a in locked if a not in flagged], "Locked buckets already priced out",
          "Decided, but the decided side costs ≥ $1 − flag after fees (or has no ask): no edge.")
    table([a for a in agg.values() if a["state"] == "LEAN_NO"], "Lean-NO (NOT risk-free)",
          "Heuristic only: a later or unsampled peak can still reach these buckets. Not counted above.")

    if snaps:
        last = snaps[-1]
        L += ["", f"## Event status (last snapshot {last['ts']})", "",
              "| event | station | climate day | obs | obs max °F (time) | CLI floor | est hi | latest °F (time) | 6h groups | CLI | locked/markets |",
              "|---|---|---|---:|---|---:|---:|---|---:|---|---|"]
        for e in last["events"]:
            if e["status"] == "not started":
                L.append(f"| {e['event']} | {e['station']} | not started | | | | | | | | |")
                continue
            L.append(f"| {e['event']} | {e['station']} | {e['status']} | {e['n_obs']} | {_p(e['max_f'], 1)} "
                     f"({(e['max_time'] or '')[11:16]}Z) | {_p(e['floor'])} | {_p(e['est_hi'])} | {_p(e['latest_f'], 1)} "
                     f"({(e['latest_time'] or '')[11:16]}Z) | {e['six']} | {e['cli'] or '–'} | {e['locked']}/{e['markets']} |")
    errs = [f"{s['ts']}: {e}" for s in snaps for e in s["errors"]] + ctx.get("snap_errors", [])
    L += ["", "## API errors and warnings", ""] + ([f"- {e}" for e in errs[:60]] or ["_none_"])
    if len(errs) > 60:
        L.append(f"- … {len(errs) - 60} more")
    L += ["", "## Caveats", "",
          "- Settlement is the NWS Daily Climate Report (CLI), normally the final report issued after the climate day; "
          "Kalshi's rules text (excerpted above) is authoritative, including how corrections/revisions are handled.",
          "- Climate day = local *standard* time; during daylight saving it runs 01:00–01:00 local clock time. Observations "
          "between 00:00 and 01:00 daylight time belong to the previous climate day.",
          "- ASOS observations: METAR body temps are whole °C; the T-group gives tenths. The CLI max is whole °F from the "
          "station's continuous data, so hourly/5-min samples are a *lower* bound (peaks between reports are missed). "
          "The floor here assumes ASOS whole-°F internals; the margin covers sensor, QC and correction risk but not all of it.",
          "- 'est hi' is NOT an upper bound; lean-NO rows can lose.",
          "- Quotes are top-of-book from the event listing; depth is walked only for flagged rows, each level costed and rounded "
          "separately (conservative). Fills, latency and queue position are not modelled. Capital is tied up until settlement.",
          "- No profit is claimed: rows show prices that existed at snapshot time only."]
    return "\n".join(L) + "\n"


def parse_now(s: str | None) -> datetime | None:
    return wc.parse_ts(s) if s else None


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--interval", type=float, default=300.0, help="seconds between snapshot starts")
    ap.add_argument("--margin", type=int, default=MARGIN_F)
    ap.add_argument("--min-edge", type=float, default=MIN_EDGE)
    ap.add_argument("--fee-rate", type=float, default=wc.FEE_RATE)
    ap.add_argument("--max-books", type=int, default=60)
    ap.add_argument("--no-cli", action="store_true", help="skip NWS CLI product lookups")
    ap.add_argument("--rps", type=float, default=8.0)
    ap.add_argument("--fixture", type=Path, help="offline fixture directory")
    ap.add_argument("--now", help="override current time (ISO, for fixtures)")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--json", type=Path)
    a = ap.parse_args(argv)
    log = lambda s: print(s, file=sys.stderr, flush=True)  # noqa: E731
    client = wc.FixtureClient(a.fixture) if a.fixture else wc.LiveClient(rps=a.rps)
    try:
        ctx = setup(client, log)
    except Exception as e:  # noqa: BLE001
        ctx = dict(series=[], stations={}, notes=[f"setup failed: {e}"])
    ctx["snap_errors"] = []
    snaps = []
    for i in range(max(1, a.repeat)):
        t0 = time.monotonic()
        now = parse_now(a.now) or datetime.now(timezone.utc)
        if a.now:
            now = now + timedelta(seconds=a.interval * i)
        try:
            s = snapshot(client, ctx, now, a.margin, a.min_edge, a.fee_rate, not a.no_cli, a.max_books, log)
            snaps.append(s)
            log(f"[{s['ts']}] snapshot {i + 1}/{a.repeat}: rows {len(s['rows'])}, errors {len(s['errors'])}")
        except Exception as e:  # noqa: BLE001
            ctx["snap_errors"].append(f"snapshot {i + 1} failed: {e}")
            log(f"snapshot {i + 1} failed: {e}")
        if i < a.repeat - 1 and not a.fixture:
            rest = a.interval - (time.monotonic() - t0)
            if rest > 0:
                time.sleep(rest)
    report = render(ctx, snaps, a)
    if a.out:
        a.out.write_text(report)
    if a.json:
        a.json.write_text(json.dumps(dict(series=ctx["series"], snapshots=snaps), indent=1, default=str))
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
