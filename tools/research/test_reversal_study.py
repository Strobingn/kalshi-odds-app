#!/usr/bin/env python3
"""No-network unit tests for tools/research/reversal_study.py (stdlib only).

Run: python3 tools/research/test_reversal_study.py
"""
from __future__ import annotations

import contextlib
import io
import random
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import reversal_study as rs  # noqa: E402
from pipeline import fee_per_contract  # noqa: E402  (path set by reversal_study)

T0 = 1_788_220_800  # 2026-09-01 00:00 UTC


def win(i: int, strike: float, settle: float | None, result: str, bid=0.48, ask=0.52, gap: int = 0) -> rs.Window:
    o = T0 + i * 900 + gap
    return rs.Window(f"W{i}", o, o + 900, strike, settle, result, bid, ask)


def test_pairs_and_moves() -> None:
    ws = [
        win(0, 60000.0, 60120.0, "yes"),          # +20 bps, up
        win(1, 60120.0, 60060.0, "no"),           # −~10 bps, down → flip
        win(2, 60060.0, None, "no"),              # settle missing: use next strike
        win(3, 60030.0, 60030.0, "yes"),          # tie settles YES
    ]
    ps = rs.pairs(ws)
    assert len(ps) == 3
    assert abs(ps[0].move_bps - 20.0) < 1e-9 and ps[0].flip and ps[0].against == "NO"
    assert not ps[1].flip and ps[1].against == "YES"
    # W2 has no expiration_value: its settlement is W3's strike (60030), −5 bps
    assert abs(ps[2].move_bps - (60030.0 - 60060.0) / 60060.0 * 1e4) < 1e-9
    assert ps[2].flip  # no → yes (tie)
    # a gap between windows breaks the pair
    gapped = [win(0, 60000.0, 60010.0, "yes"), win(2, 60010.0, 60000.0, "no")]
    assert rs.pairs(gapped) == []


def test_buckets() -> None:
    assert [rs.bucket(x) for x in (0, 4.99, 5, -9.9, 10, 19.99, -20, 39.9, 40, 400)] == [0, 0, 1, 1, 2, 2, 3, 3, 4, 4]
    assert rs.bucket_label(4) == "≥40 bps" and rs.bucket_label(0) == "[0,5) bps"


def test_side_quotes_and_pnl() -> None:
    prev = win(0, 60000.0, 60200.0, "yes")
    nxt = win(1, 60200.0, 60100.0, "no", bid=0.40, ask=0.44)
    p = rs.pairs([prev, nxt])[0]
    assert p.against == "NO" and p.flip
    # NO quote mirrors YES: bid 1 − 0.44 = 0.56, ask 1 − 0.40 = 0.60
    assert rs.side_quote(nxt, "NO") == (1 - 0.44, 1 - 0.40)
    assert abs(rs.implied_flip(p) - 0.58) < 1e-12
    assert abs(rs.taker_pnl(p) - (1.0 - 0.60 - fee_per_contract(0.60, 0.07, 5.0))) < 1e-12
    assert abs(rs.maker_pnl(p) - (1.0 - 0.57)) < 1e-12
    # a 1¢ spread leaves no room to rest inside it
    tight = rs.pairs([prev, win(1, 60200.0, 60100.0, "no", bid=0.40, ask=0.41)])[0]
    assert rs.maker_pnl(tight) is None
    # no quote at all
    nq = rs.pairs([prev, win(1, 60200.0, 60100.0, "no", bid=None, ask=None)])[0]
    assert rs.taker_pnl(nq) is None and rs.implied_flip(nq) is None


def test_wilson() -> None:
    lo, hi = rs.wilson(50, 100)
    assert lo < 0.5 < hi and abs((lo + hi) / 2 - 0.5) < 1e-9
    assert abs(rs.wilson(0, 10)[0]) < 1e-12


def _synthetic(days: int, flip_big: float, seed: int, ask: float = 0.52) -> list[rs.Window]:
    """Back-to-back windows; after a ≥ 20 bps move the next window flips with prob flip_big."""
    rng = random.Random(seed)
    ws, strike, prev_res, prev_move = [], 60000.0, "yes", 0.0
    for i in range(days * 96):
        move = rng.choice([3.0, 8.0, 15.0, 30.0, 60.0]) * rng.choice([1, -1])
        if abs(prev_move) >= 20.0:
            res = ("no" if prev_res == "yes" else "yes") if rng.random() < flip_big else prev_res
        else:
            res = rng.choice(["yes", "no"])
        move = abs(move) if res == "yes" else -abs(move)
        settle = strike * (1 + move / 1e4)
        bid = round(ask - 0.04, 2) if prev_res == "yes" else round(1 - ask, 2)
        a = ask if prev_res == "yes" else round(1 - ask + 0.04, 2)
        ws.append(rs.Window(f"S{i}", T0 + i * 900, T0 + (i + 1) * 900, strike, settle, res, bid, a))
        strike, prev_res, prev_move = settle, res, move
    return ws


def test_report_verdicts() -> None:
    # strong reversal priced at ~50¢ → PASS; none → FAIL (the ask + fee loses); 3 days → exploratory
    strong = rs.report(_synthetic(15, 0.80, 1), iters=300)
    assert "OOS" in strong and "Decision: PASS" in strong, strong
    none = rs.report(_synthetic(15, 0.50, 2), iters=300)
    assert "Decision: FAIL" in none or "Decision: NO EDGE SHOWN" in none, none
    short = rs.report(_synthetic(3, 0.80, 3), iters=100)
    assert "EXPLORATORY" in short and "NOT EVALUABLE" in short
    assert "No back-to-back pairs" in rs.report([], iters=10)


def test_cache_roundtrip_and_cli() -> None:
    ws = _synthetic(2, 0.6, 4)
    with tempfile.TemporaryDirectory() as td:
        c = Path(td) / "rev.json"
        rs.save(ws, c)
        assert rs.load(c) == ws
        out = Path(td) / "rev.md"
        with contextlib.redirect_stdout(io.StringIO()):
            assert rs.main(["report", "--cache", str(c), "--out", str(out), "--iters", "20"]) == 0
        assert out.read_text().startswith("# Window-to-window reversal")


def test_window_from_market() -> None:
    m = {"ticker": "KXBTC15M-X", "open_time": "2026-09-01T00:00:00Z", "close_time": "2026-09-01T00:15:00Z",
         "floor_strike": 60000.5, "expiration_value": "60010.25", "result": "yes"}
    candles = [
        {"end_period_ts": T0 + 60, "yes_bid": {"close_dollars": "0.47"}, "yes_ask": {"close_dollars": "0.50"}},
        {"end_period_ts": T0 + 120, "yes_bid": {"close_dollars": "0.10"}, "yes_ask": {"close_dollars": "0.20"}},
    ]
    w = rs.window_from_market(m, candles)
    assert (w.open_ts, w.close_ts, w.strike, w.settle, w.result) == (T0, T0 + 900, 60000.5, 60010.25, "yes")
    assert (w.yes_bid, w.yes_ask) == (0.47, 0.50)  # first candle closing ≥ 60 s in
    assert rs.window_from_market({**m, "result": ""}, candles) is None
    assert rs.window_from_market({**m, "expiration_value": ""}, candles).settle is None


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
