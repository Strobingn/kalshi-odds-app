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
    print("parity ok", p, "mlp", pred.yes)


if __name__ == "__main__":
    main()
