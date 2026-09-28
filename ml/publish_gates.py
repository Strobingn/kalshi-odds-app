#!/usr/bin/env python3
"""Shared publish / activation gates for the edge trainer and CI.

The phone (ModelActivation) uses the same numbers. A synthetic or
under-powered model must never be marked beats_market.
"""
from __future__ import annotations

from typing import Any

# Distinct settled markets required before a model may be published.
MIN_PUBLISH_MARKETS = 2000
# Feature rows required (one decision minute is one row).
MIN_PUBLISH_ROWS = 2000
# Time-ordered holdout rows required for beats_market.
MIN_HOLDOUT_ROWS = 400
# Brier / log-loss must beat the market by at least this much on holdout.
MIN_BRIER_MARGIN = 0.010
MIN_LOGLOSS_MARGIN = 0.010


def publish_decision(metrics: dict[str, Any]) -> dict[str, Any]:
    """Return a gate verdict. Never raises."""
    synthetic = bool(metrics.get("synthetic"))
    n_markets = int(metrics.get("n_markets") or 0)
    n_rows = int(metrics.get("n_rows") or metrics.get("n_samples") or 0)
    n_holdout = int(metrics.get("n_holdout") or 0)
    model_brier = float(metrics.get("model_brier", 1.0))
    market_brier = float(metrics.get("market_brier", 1.0))
    model_ll = float(metrics.get("model_logloss", 1.0))
    market_ll = float(metrics.get("market_logloss", 1.0))
    brier_margin = market_brier - model_brier
    ll_margin = market_ll - model_ll
    reasons: list[str] = []
    if synthetic:
        reasons.append("synthetic=true — test/fixture data is not publishable")
    if n_markets < MIN_PUBLISH_MARKETS:
        reasons.append(f"n_markets {n_markets} < {MIN_PUBLISH_MARKETS}")
    if n_rows < MIN_PUBLISH_ROWS:
        reasons.append(f"n_rows {n_rows} < {MIN_PUBLISH_ROWS}")
    if n_holdout < MIN_HOLDOUT_ROWS:
        reasons.append(f"n_holdout {n_holdout} < {MIN_HOLDOUT_ROWS}")
    if n_markets <= 0 or n_rows <= 0 or n_holdout <= 0:
        reasons.append("sample counts missing or zero")
    if brier_margin < MIN_BRIER_MARGIN:
        reasons.append(
            f"holdout Brier margin {brier_margin:.4f} < {MIN_BRIER_MARGIN:.3f}"
        )
    if ll_margin < MIN_LOGLOSS_MARGIN:
        reasons.append(
            f"holdout log-loss margin {ll_margin:.4f} < {MIN_LOGLOSS_MARGIN:.3f}"
        )
    beats = not reasons
    return {
        "beats_market": beats,
        "publishable": beats and not synthetic,
        "reasons": reasons,
        "brier_margin": brier_margin,
        "logloss_margin": ll_margin,
        "n_markets": n_markets,
        "n_rows": n_rows,
        "n_holdout": n_holdout,
        "synthetic": synthetic,
        "min_markets": MIN_PUBLISH_MARKETS,
        "min_rows": MIN_PUBLISH_ROWS,
        "min_holdout": MIN_HOLDOUT_ROWS,
        "min_brier_margin": MIN_BRIER_MARGIN,
        "min_logloss_margin": MIN_LOGLOSS_MARGIN,
    }
