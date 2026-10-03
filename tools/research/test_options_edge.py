#!/usr/bin/env python3
"""No-network checks for options_edge (Deribit smile -> P(above K))."""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backtest"))

import options_edge as o  # noqa: E402
from pipeline import SECONDS_PER_YEAR, norm_cdf  # noqa: E402

NOW = 1_790_000_000_000
DAY = 86_400_000


def flat(exp_ms: int, iv: float, fwd: float = 100_000.0) -> list:
    out = []
    p = o.parse_instrument
    for k in range(80_000, 121_000, 5_000):
        cp = "C" if k >= fwd else "P"
        d = __import__("datetime").datetime.fromtimestamp(exp_ms / 1000, tz=__import__("datetime").timezone.utc)
        name = f"BTC-{d.day}{'JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC'.split()[d.month - 1]}{d.year % 100}-{k}-{cp}"
        assert p(name) is not None, name
        out.append({"instrument_name": name, "mark_iv": iv * 100, "underlying_price": fwd})
    return out


def test_parse_instrument() -> None:
    ms, k, cp = o.parse_instrument("BTC-4OCT26-60000-C")
    assert (k, cp) == (60000.0, "C")
    assert ms == int(__import__("datetime").datetime(2026, 10, 4, 8, tzinfo=__import__("datetime").timezone.utc).timestamp() * 1000)
    assert o.parse_instrument("BTC-PERPETUAL") is None


def test_flat_smile_matches_black_scholes_digital() -> None:
    exp = (NOW // DAY + 3) * DAY + 8 * 3_600_000
    sm = o.build_smiles(flat(exp, 0.5), NOW)
    close = NOW + DAY
    p = o.p_above(sm, 105_000.0, close, NOW)
    t = DAY / 1000 / SECONDS_PER_YEAR
    sq = 0.5 * math.sqrt(t)
    want = norm_cdf((math.log(100_000 / 105_000) - 0.5 * sq * sq) / sq)
    assert abs(p - want) < 1e-9, (p, want)
    assert abs(o.p_above(sm, 100_000.0, close, NOW) - 0.5) < 0.01


def test_skew_lowers_upside_digital() -> None:
    exp = (NOW // DAY + 3) * DAY + 8 * 3_600_000
    rows = flat(exp, 0.5)
    for r in rows:  # put skew: iv rises as strike falls
        k = o.parse_instrument(r["instrument_name"])[1]
        r["mark_iv"] = (0.5 - (k - 100_000) / 100_000) * 100
    sm = o.build_smiles(rows, NOW)
    flat_sm = o.build_smiles(flat(exp, 0.5), NOW)
    assert o.p_above(sm, 100_000.0, NOW + DAY, NOW) > o.p_above(flat_sm, 100_000.0, NOW + DAY, NOW)


def test_candidates_flag_cheap_side() -> None:
    exp = (NOW // DAY + 3) * DAY + 8 * 3_600_000
    sm = o.build_smiles(flat(exp, 0.5), NOW)
    close = __import__("datetime").datetime.fromtimestamp((NOW + DAY) / 1000, tz=__import__("datetime").timezone.utc)
    m = {"ticker": "KXBTCD-X-T100000", "strike_type": "greater", "floor_strike": 100_000,
         "close_time": close.isoformat().replace("+00:00", "Z"), "yes_ask_dollars": "0.4000", "no_ask_dollars": "0.6200"}
    c = {x["side"]: x for x in o.candidates([m], sm, NOW)}
    assert c["YES"]["ev"] > o.MARGIN and c["NO"]["ev"] < 0, c


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
