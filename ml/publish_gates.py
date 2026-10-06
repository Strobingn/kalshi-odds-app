#!/usr/bin/env python3
"""Shared publish / activation gates for the edge trainer and CI.

The phone (ModelActivation) uses the same numbers. A synthetic or
under-powered model must never be marked beats_market.
"""
from __future__ import annotations

from typing import Any

# Android applicationId this model is allowed to activate.
PACKAGE_ID = "com.dirk.kalshiodds.kashi"
# Distinct settled markets required before a model may be published.
MIN_PUBLISH_MARKETS = 2000
# Feature rows required (one decision minute is one row).
MIN_PUBLISH_ROWS = 2000
# Time-ordered holdout rows required for beats_market.
MIN_HOLDOUT_ROWS = 400
# Brier / log-loss must beat the market by at least this much on holdout.
MIN_BRIER_MARGIN = 0.010
MIN_LOGLOSS_MARGIN = 0.010
# Fee-aware holdout trades required. A lucky handful must not publish.
MIN_SIM_TRADES = 30


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
    sim_trades = int(metrics.get("sim_trades") or 0)
    sim_pnl_raw = metrics.get("sim_pnl")
    market_pnl_raw = metrics.get("market_pnl")
    sim_pnl = float(sim_pnl_raw) if sim_pnl_raw is not None else None
    market_pnl = float(market_pnl_raw) if market_pnl_raw is not None else None
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
    if sim_pnl is None:
        reasons.append("sim_pnl missing — fee-aware holdout P&L is required")
    elif sim_pnl <= 0.0:
        reasons.append(
            f"fee-aware holdout P&L {sim_pnl:.4f} <= 0 (loses to the market after fees)"
        )
    if sim_trades < MIN_SIM_TRADES:
        reasons.append(f"sim_trades {sim_trades} < {MIN_SIM_TRADES}")
    if market_pnl is None:
        reasons.append("market_pnl missing — fee-aware market-follow baseline is required")
    elif sim_pnl is not None and sim_pnl <= market_pnl:
        reasons.append(
            f"fee-aware P&L {sim_pnl:.4f} does not beat market-follow {market_pnl:.4f}"
        )
    if metrics.get("beats_champion") is False:
        reasons.append("challenger does not beat the champion out of sample (Brier, log loss, fee-aware P&L)")
    for r in metrics.get("sanity_reasons") or []:
        reasons.append(f"sanity: {r}")
    beats = not reasons
    return {
        "beats_market": beats,
        "beat_market": beats,
        "publishable": beats and not synthetic,
        "reasons": reasons,
        "brier_margin": brier_margin,
        "logloss_margin": ll_margin,
        "sim_pnl": sim_pnl,
        "sim_trades": sim_trades,
        "market_pnl": market_pnl,
        "n_markets": n_markets,
        "n_rows": n_rows,
        "n_holdout": n_holdout,
        "synthetic": synthetic,
        "min_markets": MIN_PUBLISH_MARKETS,
        "min_rows": MIN_PUBLISH_ROWS,
        "min_holdout": MIN_HOLDOUT_ROWS,
        "min_brier_margin": MIN_BRIER_MARGIN,
        "min_logloss_margin": MIN_LOGLOSS_MARGIN,
        "min_sim_trades": MIN_SIM_TRADES,
        "package": PACKAGE_ID,
    }
