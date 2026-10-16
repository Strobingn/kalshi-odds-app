"""Small, dependency-free diagnostics for the Kashi BTC 15-minute scalp audit.

This intentionally does not simulate profitability or alter the app.  It checks
the source-level invariants that matter for the paper-only review and compares
the legacy per-batch fee helper with the repository's order-level fee model.
Run from the repository root:

    python tools/scalp_audit/audit.py

The output is evidence for the Markdown audit, not a performance estimate.
"""

from __future__ import annotations

from decimal import Decimal, ROUND_CEILING
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCALP = ROOT / "app/src/main/java/com/dirk/kalshiodds/decision/Scalp.kt"
V2 = ROOT / "app/src/main/java/com/dirk/kalshiodds/decision/ScalpV2.kt"
CONTROLS = ROOT / "app/src/main/java/com/dirk/kalshiodds/decision/ScalpControls.kt"
PAPER_LIMITS = ROOT / "app/src/main/java/com/dirk/kalshiodds/signal/paper/PaperLimitOrders.kt"


def ceil_cent(value: Decimal) -> Decimal:
    return value.quantize(Decimal("0.01"), rounding=ROUND_CEILING)


def accumulated_order_fee(levels: list[tuple[int, Decimal]]) -> Decimal:
    """A diagnostic accumulator: raw/ceil-6dp per fill, cent-align once."""
    principal = sum((n * p for n, p in levels), Decimal(0))
    trade_fee = sum(
        (
            (Decimal("0.07") * n * p * (Decimal(1) - p)).quantize(
                Decimal("0.000001"), rounding=ROUND_CEILING
            )
            for n, p in levels
        ),
        Decimal(0),
    )
    return ceil_cent(principal + trade_fee) - principal


def legacy_batch_fee(contracts: int, price: Decimal) -> Decimal:
    """ScalpRule.orderFee: cents-ceiling applied to each batch."""
    cents = (Decimal(7) * contracts * price * (Decimal(1) - price)).quantize(
        Decimal("1"), rounding=ROUND_CEILING
    )
    return cents / Decimal(100)


def source_checks() -> list[str]:
    scalp = SCALP.read_text(encoding="utf-8")
    v2 = V2.read_text(encoding="utf-8")
    controls = CONTROLS.read_text(encoding="utf-8")
    limits = PAPER_LIMITS.read_text(encoding="utf-8")
    checks = {
        "inclusive 50c hard cap": "const val ENTRY_HI = 0.50" in scalp
        and "price <= ENTRY_HI + 1e-9" in scalp,
        "V2 cap cannot exceed 50c": "ask!! <= minOf(MAX_ASK, maxAsk) + 1e-9" in v2,
        "V2 trail uses peak bid": "bid <= peakBid - trail + 1e-9" in v2,
        "V2 book freshness is 3s": "BOOK_MAX_AGE_MS = 3_000L" in v2,
        "manual controls clamp max ask": "maxAskCents = maxAskCents.coerceIn(1, 50)" in controls,
        "paper touch-fill caveat is explicit": "slightly optimistic vs real queue priority" in limits,
        "paper fill uses displayed depth": "touchFillQty" in limits and "askDepthAtOrBelow" in limits,
    }
    return [f"{'PASS' if ok else 'FAIL'}  {name}" for name, ok in checks.items()]


def fee_diagnostic() -> list[str]:
    rows: list[str] = []
    # Two price levels represent one depth-walk order.  The runtime currently
    # charges each level separately; compare that with a diagnostic accumulator
    # that carries raw/ceil-6dp fees and aligns the debit once.  This avoids
    # incorrectly substituting a VWAP into a nonlinear fee formula.
    cases = [(5, Decimal("0.45"), 5, Decimal("0.48")),
             (3, Decimal("0.21"), 7, Decimal("0.49")),
             (1, Decimal("0.11"), 9, Decimal("0.37"))]
    for n1, p1, n2, p2 in cases:
        levels = [(n1, p1), (n2, p2)]
        per_level = legacy_batch_fee(n1, p1) + legacy_batch_fee(n2, p2)
        one_order = accumulated_order_fee(levels)
        rows.append(
            f"fee levels {n1}@{p1} + {n2}@{p2}: "
            f"legacy=${per_level:.2f}, accumulated=${one_order:.2f}, "
            f"delta=${per_level - one_order:+.2f}"
        )
    return rows


def main() -> int:
    print(f"repo: {ROOT}")
    print("source invariants:")
    for row in source_checks():
        print(row)
    print("fee-rounding diagnostics (not a P&L simulation):")
    for row in fee_diagnostic():
        print(row)
    return 0 if all(row.startswith("PASS") for row in source_checks()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
