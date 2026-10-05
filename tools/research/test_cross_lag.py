#!/usr/bin/env python3
"""No-network tests for tools/research/cross_lag.py (stdlib only).

Builds synthetic 1 s recordings in a temp dir: BTC spot random walk with
injected jumps; ETH and SOL spot follow BTC instantly (beta 1.0 / 1.5); the
KXBTC15M and KXSOL15M mids track their own spot with no lag, the KXETH15M mid
lags ETH spot by 4 s. The study must find the ETH lag, not a SOL one, and the
taker rule must buy only ETH, in the BTC direction, with exact fees.

    python3 tools/research/test_cross_lag.py
"""
from __future__ import annotations

import gzip
import math
import random
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import cross_lag as cl  # noqa: E402
import lag_study as ls  # noqa: E402
from pipeline import kalshi_total_cost, p_finish_above  # noqa: E402
from recordings import HEADERS  # noqa: E402

SIG1 = 1e-4                                   # per-second log vol of the walk
SIG_ANNUAL = SIG1 * math.sqrt(365.25 * 24 * 3600)
KALSHI_LAG = {"BTC": 0, "ETH": 4, "SOL": 0}
BETA = {"BTC": 1.0, "ETH": 1.0, "SOL": 1.5}
START = {"BTC": 65000.0, "ETH": 2500.0, "SOL": 150.0}
DAYS = ["2026-09-28", "2026-09-29"]
WINDOWS = 3                                   # 15 min markets per day
JUMPS = (240, 520, 760)                       # seconds into each window
JUMP = 0.004                                  # log size, spread over 2 s


def _day0(day: str) -> int:
    return int(datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp()) + 3600


def build(dirpath: Path, seed: int = 7) -> dict:
    """Write recordings; return {"jumps": [(sec, direction)]}."""
    rng = random.Random(seed)
    spot_rows, book_rows, settle_rows, jumps = [], [], [], []
    for day in DAYS:
        t0 = _day0(day)
        n = WINDOWS * 900
        jump_at = {}
        for w in range(WINDOWS):
            for j, off in enumerate(JUMPS):
                sgn = 1 if (w + j + len(jumps)) % 2 == 0 else -1
                s = t0 + w * 900 + off
                jump_at[s] = jump_at[s + 1] = sgn * JUMP / 2
                jumps.append((s, sgn))
        logp = {c: math.log(START[c]) for c in cl.COINS}
        px: dict[str, dict[int, float]] = {c: {} for c in cl.COINS}
        for i in range(n):
            s = t0 + i
            b = rng.gauss(0, SIG1) + jump_at.get(s, 0.0)
            for c in cl.COINS:
                own = 0.0 if c == "BTC" else rng.gauss(0, 2e-5)
                logp[c] += BETA[c] * b + own
                px[c][s] = math.exp(logp[c])
                spot_rows.append((s * 1000 + 100, COIN_PROD[c], f"{px[c][s]:.6f}"))
        for c, (_prod, series) in cl.COINS.items():
            for w in range(WINDOWS):
                open_s, close_s = t0 + w * 900, t0 + (w + 1) * 900
                strike = round(px[c][open_s], 4)
                tk = f"{series}-{day.replace('-', '')}W{w}"
                for s in range(open_s, close_s):
                    ref = px[c].get(s - KALSHI_LAG[c], px[c][open_s])
                    fair = p_finish_above(ref, strike, close_s - s, SIG_ANNUAL)
                    mid = min(0.97, max(0.03, round(fair, 2)))
                    bid, ask = round(mid - 0.01, 2), round(mid + 0.01, 2)
                    book_rows.append((s * 1000 + 300, tk, strike, close_s * 1000,
                                      bid, 100, ask, 100, round(1 - ask, 2), 100, round(1 - bid, 2), 100))
                last = px[c][close_s - 1]
                settle_rows.append((day, (tk, close_s * 1000, strike, "yes" if last >= strike else "no")))
    _write(dirpath, spot_rows, book_rows, settle_rows)
    return {"jumps": jumps}


COIN_PROD = {c: p for c, (p, _s) in cl.COINS.items()}


def _write(dirpath: Path, spot_rows, book_rows, settle_rows) -> None:
    def day_of_ms(ms):
        return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
    by = {}
    for kind, rows in (("spot", spot_rows), ("book", book_rows)):
        for r in rows:
            by.setdefault((kind, day_of_ms(r[0])), []).append(r)
    for (kind, day), rows in by.items():
        text = ",".join(HEADERS[kind]) + "\n" + "".join(",".join(str(v) for v in r) + "\n" for r in rows)
        half = len(text) // 2
        cut = text.rfind("\n", 0, half) + 1
        # two gzip members, like the recorder's periodic flushes
        (dirpath / f"{kind}_{day}.csv.gz").write_bytes(gzip.compress(text[:cut].encode()) + gzip.compress(text[cut:].encode()))
    sb = {}
    for day, r in settle_rows:
        sb.setdefault(day, []).append(r)
    for day, rows in sb.items():
        (dirpath / f"settle_{day}.csv").write_text(
            ",".join(HEADERS["settle"]) + "\n" + "".join(",".join(str(v) for v in r) + "\n" for r in rows))


_CACHE: dict = {}


def fixture():
    if not _CACHE:
        tmp = Path(tempfile.mkdtemp(prefix="cross_lag_"))
        info = build(tmp)
        _CACHE.update(dir=tmp, info=info, run=cl.run(tmp))
    return _CACHE


# --- tests ----------------------------------------------------------------------------

def test_series_of() -> None:
    assert cl.series_of("KXETH15M-26SEP281215-15") == "ETH"
    assert cl.series_of("KXBTC15M-X") == "BTC"
    assert cl.series_of("KXSOL15M-X") == "SOL"
    assert cl.series_of("KXBTCD-X") is None and cl.series_of("") is None


def test_rolling_sigma_matches_lag_study() -> None:
    rng = random.Random(3)
    ret = {s: rng.gauss(0, 1e-4) for s in range(1000, 4000) if s % 17}
    rs = cl.RollingSigma(ret)
    for sec in (1000, 1030, 1040, 1100, 1950, 2500, 3999, 4003, 5000):
        a, b = rs(sec), ls.trailing_sigma1(ret, sec)
        assert (a is None) == (b is None), sec
        if a is not None:
            assert abs(a - b) < 1e-9 * b, (sec, a, b)
    assert cl.RollingSigma({})(100) is None


def test_events_match_lag_study() -> None:
    f = fixture()
    d = cl.load(f["dir"])
    for k in cl.KS:
        mine = cl.find_events(d["spot"]["BTC"], cl.RollingSigma(d["ret"]["BTC"]), k)
        ref = ls.find_events(d["spot"]["BTC"], d["ret"]["BTC"], k)
        assert [(e.sec, e.direction) for e in mine] == [(e.sec, e.direction) for e in ref], k
    ev4 = cl.find_events(d["spot"]["BTC"], cl.RollingSigma(d["ret"]["BTC"]), 4.0)
    secs = {e.sec: e.direction for e in ev4}
    for s, sgn in f["info"]["jumps"]:
        assert secs.get(s) == sgn, (s, sgn)  # every injected jump is detected at its first second


def test_beta_recovered() -> None:
    d = cl.load(fixture()["dir"])
    sec = max(d["ret"]["SOL"]) - 10
    b_sol = cl.beta_at(d["ret"]["BTC"], d["ret"]["SOL"], sec)
    b_eth = cl.beta_at(d["ret"]["BTC"], d["ret"]["ETH"], sec)
    assert abs(b_sol - 1.5) < 0.05 and abs(b_eth - 1.0) < 0.05, (b_sol, b_eth)
    assert cl.beta_at({}, {}, sec) is None


def test_cross_correlation_finds_eth_lag_only() -> None:
    r = fixture()["run"]
    p = r["peaks"]
    assert p["BTC spot -> ETH Kalshi"] == 4, p
    assert p["ETH spot -> ETH Kalshi"] == 4, p
    assert p["BTC spot -> BTC Kalshi"] == 0, p
    assert p["BTC spot -> SOL Kalshi"] == 0, p
    assert p["BTC spot -> ETH spot"] == 0 and p["BTC spot -> SOL spot"] == 0, p
    assert r["verdict_a"] == {"ETH": True, "SOL": False}
    assert set(r["xcorr"]["BTC spot -> ETH Kalshi"]) == {str(x) for x in range(-5, 31)}


def test_event_delays() -> None:
    r = fixture()["run"]
    e = r["events"]["4.0"]
    eth, sol = e["coins"]["ETH"], e["coins"]["SOL"]
    assert eth["spot"]["median"] in (0, 1) and sol["spot"]["median"] in (0, 1)
    assert eth["kalshi"]["median"] >= 3
    assert sol["kalshi"]["median"] <= 1
    assert eth["paired_kalshi_minus_spot"]["median"] >= 3
    assert eth["paired_kalshi_minus_spot"]["share_ge2"] > 0.8
    assert sol["paired_kalshi_minus_spot"]["median"] <= 1
    assert eth["paired_vs_btc_kalshi"]["median"] >= 3
    # only 18 jumps: below the pre-registered 30-pair minimum, so not "supported"
    assert eth["supported"] is False and r["events"]["3.0"]["coins"]["ETH"]["supported"] is None


def test_taker_buys_stale_eth_in_btc_direction() -> None:
    f = fixture()
    r = f["run"]
    dirs = {s: sgn for s, sgn in f["info"]["jumps"]}
    prim = next(x for x in r["rules"] if x["primary"])
    assert (prim["k"], prim["fair"], prim["delay"]) == (4.0, "own", 0)
    assert sum(x["primary"] for x in r["rules"]) == 1 and len(r["rules"]) == 8
    assert prim["coins"]["ETH"]["bets"] == 6           # every stale ETH market, once
    assert prim["coins"]["SOL"]["bets"] == 0          # SOL book is not stale: no edge after fee
    for b in prim["bet_list"]:
        assert b["ticker"].startswith("KXETH15M-")
        assert b["side"] == ("yes" if dirs[b["sec"]] > 0 else "no"), b
        assert b["cost"] <= 5.0 + 1e-9
        assert abs(b["cost"] - kalshi_total_cost(b["contracts"], b["ask"])) < 1e-9
        assert b["fair"] - b["ask"] >= 0.02
    tickers = [b["ticker"] for b in prim["bet_list"]]
    assert len(tickers) == len(set(tickers))           # one bet per market
    for rule in r["rules"]:
        tk = [b["ticker"] for b in rule["bet_list"]]
        assert len(tk) == len(set(tk))
    beta = next(x for x in r["rules"] if x["k"] == 4.0 and x["fair"] == "beta" and x["delay"] == 0)
    assert beta["coins"]["ETH"]["bets"] == 6
    late = next(x for x in r["rules"] if x["k"] == 4.0 and x["fair"] == "own" and x["delay"] == 2)
    assert all(b["sec"] - 2 in dirs for b in late["bet_list"])


def test_stats_and_exploratory_flag() -> None:
    r = fixture()["run"]
    assert r["days"] == DAYS and r["exploratory"] is True and r["primary_pass"] is False
    s = next(x for x in r["rules"] if x["primary"])["pooled"]
    assert s["days"] == 2
    lo95, hi95 = s["ci95"]
    lo99, hi99 = s["ci99"]
    assert lo99 <= lo95 <= s["pnl_mean"] <= hi95 <= hi99
    assert abs(s["pnl_total"] - sum(b["pnl"] for b in next(x for x in r["rules"] if x["primary"])["bet_list"])) < 1e-9
    assert r["settled"] == {"BTC": 6, "ETH": 6, "SOL": 6}


def test_single_day_ci_is_nan() -> None:
    r = cl.run(fixture()["dir"], days=[DAYS[0]])
    s = next(x for x in r["rules"] if x["primary"])["pooled"]
    assert s["bets"] > 0 and math.isnan(s["ci95"][0])
    assert "n/a" in cl.report(r)


def test_report_renders() -> None:
    txt = cl.report(fixture()["run"])
    assert "EXPLORATORY" in txt
    assert "| BTC spot -> ETH Kalshi | +4 |" in txt
    assert "**(primary)**" in txt and "not passed" in txt
    assert "ETH **yes**, SOL **no**" in txt


def test_empty_dir() -> None:
    tmp = Path(tempfile.mkdtemp(prefix="cross_lag_empty_"))
    try:
        r = cl.run(tmp)
        assert r["days"] == [] and r["exploratory"] and not r["primary_pass"]
        assert all(e["events"] == 0 for e in r["events"].values())
        txt = cl.report(r)
        assert "Days: none" in txt
    finally:
        shutil.rmtree(tmp)


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    try:
        for t in tests:
            t()
            print(f"ok  {t.__name__}")
    finally:
        if _CACHE:
            shutil.rmtree(_CACHE["dir"], ignore_errors=True)
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
