#!/usr/bin/env python3
"""No-network checks that the ported math is internally consistent."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from pipeline import (
    DipHunterMlp,
    build_features,
    close_fill,
    conservative_fill,
    dirk_ok,
    kalshi_total_cost,
    net_profit_if_win,
    p_finish_above,
    size_all_in,
)
from weights import forward, load_fallback_weights


def entry_filter_cases() -> None:
    """Hand-computed EntryFilter cases; same list as EntryFilterTest.matchesBacktestPortHandCases."""
    from pipeline import entry_filter

    cases = [
        (820, 84_311.0, 84_144.0, 2.0, False, "too early: 13:40 left (wait until 12:00)"),
        (720, 84_311.0, 84_144.0, 2.0, True, None),
        (600, 84_160.0, 84_144.0, 2.0, False, "near strike: 1.9bp < 5bp"),
        (600, 84_160.0, 84_144.0, 11.0, True, "near strike 1.9bp < 5bp allowed: net edge +11.0pp ≥ 10pp"),
        (600, None, 84_144.0, 2.0, True, "near-strike check skipped: no spot"),
        (840, 84_144.0, 84_144.0, 2.0, False, "too early: 14:00 left (wait until 12:00) · near strike: 0.0bp < 5bp"),
    ]
    for tte, spot, strike, edge, want_ok, want_reason in cases:
        ok, reason = entry_filter(tte, spot, strike, edge)
        assert ok == want_ok, (tte, spot, strike, edge, ok, reason)
        assert reason == want_reason, (tte, spot, strike, edge, reason)
    # 16 / 84144 * 1e4 = 1.9015bp by hand.
    assert abs(16.0 / 84_144.0 * 10_000.0 - 1.9015) < 1e-4
    # Exactly 12:00 left passes; 12:01 does not.
    assert entry_filter(720, 100_241.0, 100_000.0, 1.0) == (True, None)
    assert entry_filter(721, 100_241.0, 100_000.0, 1.0)[0] is False
    # Signed override: a large negative edge never unlocks a near-strike entry.
    assert entry_filter(600, 100_021.0, 100_000.0, -15.0) == (False, "near strike: 2.1bp < 5bp")
    # Missing / oversized time left and disabled never block.
    assert entry_filter(None, 100_241.0, 100_000.0, 1.0) == (True, "entry-time check skipped: no close time")
    assert entry_filter(1_790, 100_241.0, 100_000.0, 1.0) == (
        True,
        "entry-time check skipped: 29:50 left is longer than the 15:00 window",
    )
    assert entry_filter(880, 100_000.0, 100_000.0, -20.0, enabled=False) == (True, None)
    # Zero thresholds turn each rule off (the sweep's baseline cell == app_shipped).
    assert entry_filter(899, 100_000.0, 100_000.0, 0.0, min_elapsed_min=0, min_strike_bp=0.0) == (True, None)
    assert entry_filter(660, 100_241.0, 100_000.0, 1.0, min_elapsed_min=5) == (
        False,
        "too early: 11:00 left (wait until 10:00)",
    )


def main() -> None:
    w = load_fallback_weights()
    assert len(w["mean"]) == 8
    x = [0.1 * i for i in range(8)]
    p = forward(x, w)
    assert abs(sum(p) - 1.0) < 1e-6
    assert 0.0 < p[0] < 1.0 and 0.0 < p[1] < 1.0

    assert abs(kalshi_total_cost(10, 0.50) - 5.18) < 1e-9
    assert abs(kalshi_total_cost(1, 0.50) - 0.52) < 1e-9
    c, cost, _ = size_all_in(0.31)
    assert c >= 15
    assert net_profit_if_win(c, 0.31) + 1e-9 >= 10.0
    assert dirk_ok(0.31)
    assert not dirk_ok(0.40)

    atm = p_finish_above(100_000.0, 100_000.0, 900.0, 0.60)
    assert atm is not None and abs(atm - 0.5) < 0.04

    raw = build_features(0.40, 10_000.0, 900_000, 300_000, [], "KXBTC15M-X", 500.0)
    assert len(raw) == 8
    assert abs(raw[0] - 0.40) < 1e-9
    assert raw[6] == 0.0

    mlp = DipHunterMlp()
    pred = mlp.predict("KXBTC15M-T", 0.55, 8_000.0, 900_000, 120_000, 400.0)
    assert 0.02 <= pred.yes <= 0.98
    assert abs(pred.yes + pred.no - 1.0) < 1e-6

    assert abs(close_fill("YES", 0.47, 0.40) - 0.47) < 1e-12
    assert abs(close_fill("NO", 0.47, 0.40) - 0.60) < 1e-12
    assert close_fill("YES", 0.0, 0.40) is None
    assert abs(conservative_fill("YES", 0.47, 0.75, 0.40, 0.10) - 0.75) < 1e-12
    # bid.low=0 → 1-0=1.0 unusable; stress NO falls back to 1-close
    assert abs(conservative_fill("NO", 0.47, 0.75, 0.40, 0.0) - 0.60) < 1e-12
    entry_filter_cases()
    print("parity ok", p, "mlp", pred.yes)


if __name__ == "__main__":
    main()
