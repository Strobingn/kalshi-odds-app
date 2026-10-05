#!/usr/bin/env python3
"""Fee probe: what does Kalshi charge (or pay) a resting maker on our series?

Read-only, public, unauthenticated, stdlib only. For each series (default
KXBTC15M, KXETH15M, KXSOL15M, KXBTCD) it calls:

  GET /series/{ticker}                                 fee_type, fee_multiplier
  GET /series/fee_changes?series_ticker=..&show_historical=true
                                                      scheduled / past changes
  GET /markets?series_ticker=..&status=open&limit=3    fee_waiver_expiration_time
  GET /events/fee_changes                              per-event overrides (paged,
                                                       filtered to our series)
  GET /incentive_programs?status=..&type=liquidity     liquidity-incentive rewards
                                                       on our markets (paged)

and prints every fee-related field raw, then an interpretation.

Interpretation (from the field description in Kalshi's OpenAPI spec as shipped
in the official `kalshi_python_sync` 3.31.0 SDK, 2026-09-29):

  fee_type 'quadratic'                        taker = 0.07*m*C*P*(1-P), maker = 0
  fee_type 'quadratic_with_maker_fees'        maker = 0.25 * taker formula
  fee_type 'quadratic_with_combo_maker_fees'  maker = 0.50 * taker formula
  fee_type 'flat'                             "Specific Trading Fees Table" of
                                              the fee schedule PDF -> unknown here
  m = fee_multiplier. Trade fees round UP to $0.0001 per fill (docs "Fee
  rounding"); balances are then squared to whole cents via a rounding fee with
  a per-order accumulator/rebate.

The maker factor scaling the taker formula (0.25 / 0.5) and m applying to the
maker side are our reading of that description; the probe prints the raw
fields so a human can check.

Usage:
  python3 tools/research/fee_probe.py [--series KXBTC15M,KXETH15M] [--json out.json]
Exit code 0 if at least one series was read, 1 otherwise.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from decimal import ROUND_CEILING, Decimal
from typing import Callable

KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
UA = {"User-Agent": "DipHunterFeeProbe/0.1 (research; +https://github.com/Strobingn/kalshi-odds-app)"}
DEFAULT_SERIES = ("KXBTC15M", "KXETH15M", "KXSOL15M", "KXBTCD")

TAKER_BASE = Decimal("0.07")
MAKER_FACTOR = {
    "quadratic": Decimal("0"),
    "quadratic_with_maker_fees": Decimal("0.25"),
    "quadratic_with_combo_maker_fees": Decimal("0.5"),
}
EXAMPLE_PRICES = ("0.10", "0.30", "0.50", "0.70", "0.90")
EXAMPLE_CONTRACTS = 10  # ~ a $5 ticket at 50c
MAX_EVENT_FEE_PAGES = 10
MAX_INCENTIVE_PAGES = 20

Getter = Callable[[str], object]


# --------------------------------------------------------------------------- HTTP


def http_get(url: str, retries: int = 4) -> object:
    """GET JSON. 404 -> None. Retries 429/5xx with backoff; other errors raise."""
    last: Exception | None = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 404:
                return None
            if e.code in (429, 500, 502, 503, 504):
                time.sleep(1.5 * (2 ** i))
                continue
            body = ""
            try:
                body = e.read().decode()[:300]
            except Exception:  # noqa: BLE001
                pass
            raise RuntimeError(f"HTTP {e.code} for {url}: {body}") from e
        except (urllib.error.URLError, TimeoutError) as e:
            last = e
            time.sleep(1.5 * (2 ** i))
    raise RuntimeError(f"GET failed after {retries} tries: {url}: {last}")


def url(base: str, path: str, **params: object) -> str:
    q = {k: str(v).lower() if isinstance(v, bool) else str(v) for k, v in params.items() if v is not None}
    return base.rstrip("/") + path + ("?" + urllib.parse.urlencode(q) if q else "")


# --------------------------------------------------------------------------- fee math


def ceil_to(x: Decimal, step: str) -> Decimal:
    return x.quantize(Decimal(step), rounding=ROUND_CEILING)


def rates_for(fee_type: str | None, multiplier: object) -> dict:
    """Taker and maker rate (coefficient on C*P*(1-P)) for a fee_type/multiplier.

    Rates are None when the fee type is not one of the quadratic schedules
    (e.g. 'flat', or a value we have never seen)."""
    try:
        m = Decimal(str(multiplier)) if multiplier is not None else Decimal(1)
    except Exception:  # noqa: BLE001
        m = None
    if fee_type not in MAKER_FACTOR or m is None:
        return {"taker_rate": None, "maker_rate": None, "known": False}
    taker = TAKER_BASE * m
    return {"taker_rate": taker, "maker_rate": taker * MAKER_FACTOR[fee_type], "known": True}


def trade_fee(rate: Decimal, contracts: int, price: str) -> Decimal:
    """Per-fill trade fee: rate*C*P*(1-P) rounded up to $0.0001 (docs: Fee rounding)."""
    p = Decimal(price)
    return ceil_to(rate * contracts * p * (1 - p), "0.0001")


def trade_fee_cent(rate: Decimal, contracts: int, price: str) -> Decimal:
    """Same, rounded up to a whole cent (the older fee-schedule wording)."""
    p = Decimal(price)
    return ceil_to(rate * contracts * p * (1 - p), "0.01")


# --------------------------------------------------------------------------- incentives


def dollars_from_centicents(v: object) -> float | None:
    try:
        return int(v) / 10_000
    except (TypeError, ValueError):
        return None


def summarize_program(p: dict) -> dict:
    start, end = p.get("start_date"), p.get("end_date")
    days = None
    try:
        s = datetime.fromisoformat(str(start).replace("Z", "+00:00"))
        e = datetime.fromisoformat(str(end).replace("Z", "+00:00"))
        days = max((e - s).total_seconds() / 86400.0, 0.0)
    except (TypeError, ValueError):
        pass
    reward = dollars_from_centicents(p.get("period_reward"))
    return {
        "market_ticker": p.get("market_ticker"),
        "type": p.get("incentive_type"),
        "start": start,
        "end": end,
        "period_days": days,
        "period_reward_usd": reward,
        "reward_per_day_usd": (reward / days) if (reward is not None and days) else None,
        "discount_factor": (int(p["discount_factor_bps"]) / 10_000) if p.get("discount_factor_bps") is not None else None,
        "target_size": p.get("target_size_fp"),
        "max_reward_per_account_usd": dollars_from_centicents(p.get("max_reward_per_account")),
        "paid_out": p.get("paid_out"),
        "description": p.get("incentive_description"),
    }


def series_of(market_ticker: str | None) -> str:
    return (market_ticker or "").split("-", 1)[0]


# --------------------------------------------------------------------------- probe


def fee_fields(obj: dict) -> dict:
    return {k: v for k, v in obj.items() if "fee" in k.lower()}


def probe_series(get: Getter, base: str, ticker: str, now: datetime) -> dict:
    out: dict = {"series": ticker, "errors": []}
    try:
        raw = get(url(base, f"/series/{ticker}"))
        s = (raw or {}).get("series") if isinstance(raw, dict) else None
        if not s:
            out["errors"].append("series not found")
        else:
            out["series_fee_fields"] = fee_fields(s)
            out["fee_type"] = s.get("fee_type")
            out["fee_multiplier"] = s.get("fee_multiplier")
            out["title"] = s.get("title")
    except Exception as e:  # noqa: BLE001
        out["errors"].append(f"series: {e}")

    try:
        raw = get(url(base, "/series/fee_changes", series_ticker=ticker, show_historical=True))
        changes = (raw or {}).get("series_fee_change_arr") or [] if isinstance(raw, dict) else []
        out["fee_changes"] = changes
        out["future_fee_changes"] = [c for c in changes if _after(c.get("scheduled_ts"), now)]
    except Exception as e:  # noqa: BLE001
        out["errors"].append(f"fee_changes: {e}")

    try:
        raw = get(url(base, "/markets", series_ticker=ticker, status="open", limit=3))
        mkts = (raw or {}).get("markets") or [] if isinstance(raw, dict) else []
        out["open_markets"] = [{"ticker": m.get("ticker"), **fee_fields(m)} for m in mkts]
    except Exception as e:  # noqa: BLE001
        out["errors"].append(f"markets: {e}")

    rates = rates_for(out.get("fee_type"), out.get("fee_multiplier")) if "fee_type" in out else \
        {"taker_rate": None, "maker_rate": None, "known": False}
    out.update(rates)
    if rates["known"]:
        out["examples"] = [
            {
                "price": p,
                "contracts": EXAMPLE_CONTRACTS,
                "taker_fee": str(trade_fee(rates["taker_rate"], EXAMPLE_CONTRACTS, p)),
                "maker_fee": str(trade_fee(rates["maker_rate"], EXAMPLE_CONTRACTS, p)),
                "maker_fee_cent_rounded": str(trade_fee_cent(rates["maker_rate"], EXAMPLE_CONTRACTS, p)),
            }
            for p in EXAMPLE_PRICES
        ]
    return out


def _after(ts: object, now: datetime) -> bool:
    try:
        return datetime.fromisoformat(str(ts).replace("Z", "+00:00")) > now
    except (TypeError, ValueError):
        return False


def probe_event_fee_changes(get: Getter, base: str, series: tuple[str, ...]) -> dict:
    """Page through /events/fee_changes and keep overrides on our series."""
    kept, pages, err = [], 0, None
    cursor = None
    try:
        while pages < MAX_EVENT_FEE_PAGES:
            raw = get(url(base, "/events/fee_changes", limit=200, cursor=cursor))
            pages += 1
            if not isinstance(raw, dict):
                break
            for c in raw.get("event_fee_changes") or []:
                if c.get("series_ticker") in series or series_of(c.get("event_ticker")) in series:
                    kept.append(c)
            cursor = raw.get("cursor")
            if not cursor:
                break
    except Exception as e:  # noqa: BLE001
        err = str(e)
    return {"pages": pages, "matches": kept, "error": err, "exhausted": cursor in (None, "")}


def probe_incentives(get: Getter, base: str, series: tuple[str, ...]) -> dict:
    """All liquidity incentive programs (active + upcoming), grouped by series."""
    res: dict = {"by_status": {}, "error": None}
    for status in ("active", "upcoming"):
        programs, pages, cursor = [], 0, None
        try:
            while pages < MAX_INCENTIVE_PAGES:
                raw = get(url(base, "/incentive_programs", status=status, type="liquidity",
                              limit=1000, cursor=cursor))
                pages += 1
                if not isinstance(raw, dict):
                    break
                programs += raw.get("incentive_programs") or []
                cursor = raw.get("next_cursor")
                if not cursor:
                    break
        except Exception as e:  # noqa: BLE001
            res["error"] = f"{status}: {e}"
        counts: dict[str, int] = {}
        for p in programs:
            counts[series_of(p.get("market_ticker"))] = counts.get(series_of(p.get("market_ticker")), 0) + 1
        ours = [summarize_program(p) for p in programs if series_of(p.get("market_ticker")) in series]
        res["by_status"][status] = {
            "total_programs": len(programs),
            "pages": pages,
            "truncated": bool(cursor),
            "ours": ours,
            "top_series": sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:15],
        }
    return res


def run(get: Getter, base: str, series: tuple[str, ...], now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    return {
        "generated_utc": now.isoformat(timespec="seconds"),
        "base": base,
        "series": [probe_series(get, base, s, now) for s in series],
        "event_fee_changes": probe_event_fee_changes(get, base, series),
        "incentives": probe_incentives(get, base, series),
    }


# --------------------------------------------------------------------------- report


def fmt_rate(r: Decimal | None) -> str:
    return "UNKNOWN" if r is None else f"{float(r):g}"


def report(res: dict) -> str:
    L = [f"Kalshi fee probe — {res['generated_utc']} — {res['base']}", ""]
    for s in res["series"]:
        L.append(f"## {s['series']}  {s.get('title') or ''}".rstrip())
        for e in s["errors"]:
            L.append(f"  ERROR {e}")
        L.append(f"  raw series fee fields: {json.dumps(s.get('series_fee_fields'), sort_keys=True)}")
        L.append(f"  raw fee_changes (historical+scheduled): {json.dumps(s.get('fee_changes'), sort_keys=True)}")
        for m in s.get("open_markets") or []:
            L.append(f"  open market raw fee fields: {json.dumps(m, sort_keys=True)}")
        if not s.get("open_markets"):
            L.append("  open markets: none returned")
        if s.get("known"):
            L.append(f"  => taker rate {fmt_rate(s['taker_rate'])} x C*P*(1-P); "
                     f"MAKER RATE {fmt_rate(s['maker_rate'])} x C*P*(1-P) (fills round up to $0.0001)")
            L.append(f"  examples, {EXAMPLE_CONTRACTS} contracts: " + "; ".join(
                f"P={x['price']}: taker ${x['taker_fee']}, maker ${x['maker_fee']} (cent-rounded ${x['maker_fee_cent_rounded']})"
                for x in s["examples"]))
        else:
            L.append(f"  => fee_type {s.get('fee_type')!r} not a known quadratic schedule: MAKER RATE UNKNOWN — "
                     "read the fee schedule PDF (Specific Trading Fees Table)")
        for c in s.get("future_fee_changes") or []:
            L.append(f"  WARNING scheduled change: {json.dumps(c, sort_keys=True)}")
        L.append("")

    ev = res["event_fee_changes"]
    L.append("## Event-level fee overrides on these series (/events/fee_changes)")
    if ev["error"]:
        L.append(f"  ERROR {ev['error']}")
    L.append(f"  pages read {ev['pages']} (exhausted={ev['exhausted']}), matches {len(ev['matches'])}")
    for c in ev["matches"][:20]:
        L.append(f"  {json.dumps(c, sort_keys=True)}")
    L.append("")

    inc = res["incentives"]
    L.append("## Liquidity incentive programs (/incentive_programs?type=liquidity)")
    if inc["error"]:
        L.append(f"  ERROR {inc['error']}")
    for status, d in inc["by_status"].items():
        L.append(f"  {status}: {d['total_programs']} programs exchange-wide"
                 f"{' (TRUNCATED)' if d['truncated'] else ''}; on our series: {len(d['ours'])}")
        L.append(f"    busiest series: {', '.join(f'{k}={v}' for k, v in d['top_series']) or 'none'}")
        for p in d["ours"][:25]:
            L.append(f"    {json.dumps(p, sort_keys=True)}")
    L.append("")

    L.append("## Verdict")
    for s in res["series"]:
        n_inc = sum(1 for d in inc["by_status"].values() for p in d["ours"] if series_of(p["market_ticker"]) == s["series"])
        L.append(f"  {s['series']}: fee_type={s.get('fee_type')!r} multiplier={s.get('fee_multiplier')!r} "
                 f"MAKER_RATE={fmt_rate(s.get('maker_rate'))} TAKER_RATE={fmt_rate(s.get('taker_rate'))} "
                 f"scheduled_changes={len(s.get('future_fee_changes') or [])} liquidity_incentives={n_inc}")
    return "\n".join(L)


def to_jsonable(o: object) -> object:
    if isinstance(o, Decimal):
        return str(o)
    if isinstance(o, dict):
        return {k: to_jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [to_jsonable(v) for v in o]
    return o


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--series", default=",".join(DEFAULT_SERIES))
    ap.add_argument("--base", default=KALSHI)
    ap.add_argument("--json", help="also write the full result as JSON here")
    args = ap.parse_args(argv)
    series = tuple(s.strip().upper() for s in args.series.split(",") if s.strip())
    res = run(http_get, args.base, series)
    print(report(res))
    if args.json:
        with open(args.json, "w") as f:
            json.dump(to_jsonable(res), f, indent=2, sort_keys=True)
    ok = any("fee_type" in s for s in res["series"])
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
