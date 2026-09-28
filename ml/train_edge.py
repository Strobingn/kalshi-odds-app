#!/usr/bin/env python3
"""
Offline edge trainer, schema 2: a logistic model of the **market's error**.

    logit P(YES) = logit(mid) + b + Σ w_i · (x_i − mean_i) / std_i

With b = 0 and every w = 0 it returns the Kalshi mid, so it can only beat the
market by fitting something real. Rows are every decision minute (1…13) of
settled KXBTC15M / KXETH15M / KXSOL15M windows in the backtest cache. The
features come from `tools/backtest/pipeline.edge_features`; the Android twin
is `prediction/EdgeFeatures.kt`.

    python3 ml/train_edge.py --cache tools/backtest/cache
    python3 ml/train_edge.py --fetch --days 30          # fetch the cache first (CI)
    python3 ml/train_edge.py --fixture                  # synthetic smoke test only

A real run exits non-zero when there is not enough data. It never falls back
to synthetic rows. `--fixture` output is marked `"fixture": true`, and the
app refuses to activate it.

Honest-number rules:
- Days are split in time order: first ~2/3 fit, last ~1/3 scored (same split
  as the backtest).
- `beats_market` needs lower out-of-sample Brier and log-loss than the mid,
  and a market-clustered bootstrap 95% CI of the log-loss gain above zero.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[1]
ML_DIR = REPO / "ml"
BACKTEST = REPO / "tools" / "backtest"
sys.path.insert(0, str(BACKTEST))

import pipeline as pl  # noqa: E402

FEATURE_NAMES = list(pl.EDGE_FEATURES)
SCHEMA = pl.EDGE_SCHEMA
L2 = 1.0
NEWTON_ITERS = 12
MIN_TRAIN_ROWS = 5_000
MIN_OOS_MARKETS = 300
BOOTSTRAP_REPS = 2_000
# EV simulation: bet the first minute whose net EV at the close ask clears this.
EV_MARGIN = 0.02


# --- rows ---------------------------------------------------------------------

def rows_from_cache(cache: Path) -> list[dict[str, Any]]:
    import simulate as sim

    markets, candles, spots = sim.load_cache(cache)
    markets = [m for m in markets if m.get("result") in ("yes", "no") and m["ticker"] in candles]
    out: list[dict[str, Any]] = []
    for m in sorted(markets, key=lambda m: (m["open_ms"], m["ticker"])):
        idx = spots.get(m.get("coin") or sim.COIN.get(m["series"], "BTC"), {})
        out.extend(market_rows(m, candles[m["ticker"]], idx))
    return out


def market_rows(m: dict, bars: list[dict], spot_idx: dict[int, float]) -> list[dict[str, Any]]:
    import simulate as sim

    open_ms = int(m["open_ms"])
    close_ms = int(m["close_ms"])
    y = 1 if m["result"] == "yes" else 0
    strike = m.get("floor_strike")
    rows = []
    for r in sorted((b for b in bars if b.get("end_ts")), key=lambda b: b["end_ts"]):
        now_ms = int(r["end_ts"]) * 1000
        if now_ms > close_ms:
            break
        elapsed = int(round((now_ms - open_ms) / 60_000.0))
        if elapsed < 1 or elapsed > 13:
            continue
        yb = pl.usable((r.get("yes_bid") or {}).get("close"))
        ya = pl.usable((r.get("yes_ask") or {}).get("close"))
        if yb is None or ya is None or ya < yb:
            continue
        mid = 0.5 * (yb + ya)
        spot, r1, r5, closes = sim.spot_inputs(spot_idx, now_ms // 1000)
        sigma = pl.sigma_annual_from_closes(closes)
        tte = max(0.0, (close_ms - now_ms) / 1000.0)
        x = pl.edge_features(mid, ya - yb, spot, strike, tte, sigma, r1, r5, now_ms)
        rows.append(
            dict(
                x=x,
                mid=mid,
                y=y,
                ticker=m["ticker"],
                day=sim._day(close_ms),
                yes_ask=ya,
                no_ask=pl.usable(1.0 - yb),
            )
        )
    return rows


def fixture_rows(n_markets: int = 900, seed: int = 11) -> list[dict[str, Any]]:
    """Synthetic rows with a planted edge. For smoke tests only."""
    rng = random.Random(seed)
    rows = []
    t0 = 1_790_000_000_000
    for k in range(n_markets):
        day = datetime.fromtimestamp((t0 + k * 900_000) / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
        truth = rng.uniform(0.1, 0.9)
        y = 1 if rng.random() < truth else 0
        for minute in range(1, 14):
            mid = min(0.95, max(0.05, truth + rng.gauss(0, 0.08)))
            spot_hint = truth - mid
            x = [4 * spot_hint, 0.0, 0.0, 0.0, 1 - minute / 15, 0.02, 0.0, 1.0, 1.0]
            rows.append(dict(x=x, mid=mid, y=y, ticker=f"FX-{k}", day=day, yes_ask=min(0.99, mid + 0.01), no_ask=min(0.99, 1 - mid + 0.01)))
    return rows


# --- fit ----------------------------------------------------------------------

def standardize_stats(X: list[list[float]]) -> tuple[list[float], list[float]]:
    n = len(X[0])
    mean = [sum(r[i] for r in X) / len(X) for i in range(n)]
    std = []
    for i in range(n):
        var = sum((r[i] - mean[i]) ** 2 for r in X) / max(1, len(X) - 1)
        std.append(math.sqrt(var) if var > 1e-12 else 1.0)
    return mean, std


def solve(A: list[list[float]], b: list[float]) -> list[float]:
    n = len(b)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(M[r][c]))
        M[c], M[p] = M[p], M[c]
        if abs(M[c][c]) < 1e-12:
            continue
        for r in range(n):
            if r != c:
                f = M[r][c] / M[c][c]
                if f:
                    for k in range(c, n + 1):
                        M[r][k] -= f * M[c][k]
    return [M[i][n] / M[i][i] if abs(M[i][i]) > 1e-12 else 0.0 for i in range(n)]


def fit_offset(Z: list[list[float]], off: list[float], y: list[int], l2: float = L2) -> tuple[list[float], float]:
    """Newton / IRLS for logistic with a fixed offset and an L2 pull to 0 on w."""
    d = len(Z[0]) + 1  # last slot = bias (unpenalized)
    beta = [0.0] * d
    for _ in range(NEWTON_ITERS):
        g = [0.0] * d
        H = [[0.0] * d for _ in range(d)]
        for z, o, yi in zip(Z, off, y):
            v = z + [1.0]
            eta = o + sum(bj * vj for bj, vj in zip(beta, v))
            eta = max(-30.0, min(30.0, eta))
            p = 1.0 / (1.0 + math.exp(-eta))
            e = p - yi
            wgt = p * (1.0 - p)
            for i in range(d):
                vi = v[i]
                g[i] += e * vi
                wv = wgt * vi
                Hi = H[i]
                for j in range(i, d):
                    Hi[j] += wv * v[j]
        for i in range(d):
            for j in range(i):
                H[i][j] = H[j][i]
        for i in range(d - 1):
            g[i] += l2 * beta[i]
            H[i][i] += l2
        step = solve(H, g)
        beta = [bj - sj for bj, sj in zip(beta, step)]
        if max(abs(s) for s in step) < 1e-7:
            break
    return beta[:-1], beta[-1]


def fit_model(rows: list[dict[str, Any]], l2: float = L2) -> dict[str, Any]:
    X = [r["x"] for r in rows]
    mean, std = standardize_stats(X)
    Z = [[(x[i] - mean[i]) / std[i] for i in range(len(mean))] for x in X]
    off = [pl.logit(r["mid"]) for r in rows]
    w, b = fit_offset(Z, off, [r["y"] for r in rows], l2)
    return {"weights": w, "bias": b, "mean": mean, "std": std}


# --- score --------------------------------------------------------------------

def clip_p(p: float) -> float:
    return min(pl.EDGE_P_MAX, max(pl.EDGE_P_MIN, p))


def loss_terms(p: float, y: int) -> tuple[float, float]:
    q = clip_p(p)
    return (q - y) ** 2, -(y * math.log(q) + (1 - y) * math.log(1 - q))


def ev_side(p_yes: float, yes_ask: float | None, no_ask: float | None) -> tuple[str | None, float]:
    """Side with the higher net EV per contract at the ask ($5 ticket fees)."""
    best, best_ev = None, -1.0
    for side, p_side, ask in (("YES", p_yes, yes_ask), ("NO", 1.0 - p_yes, no_ask)):
        if ask is None:
            continue
        c = max(1, int(math.floor(pl.STAKE_USD / ask + 1e-9)))
        ev = p_side - ask - pl.kalshi_fee_total(c, ask) / c
        if ev > best_ev:
            best, best_ev = side, ev
    return best, best_ev


def bootstrap_gain_ci(per_market: dict[str, tuple[float, int]], reps: int = BOOTSTRAP_REPS, seed: int = 7) -> tuple[float, float]:
    keys = list(per_market)
    if not keys:
        return float("nan"), float("nan")
    rng = random.Random(seed)
    means = []
    for _ in range(reps):
        s = 0.0
        n = 0
        for _k in range(len(keys)):
            g, c = per_market[keys[rng.randrange(len(keys))]]
            s += g
            n += c
        means.append(s / n)
    means.sort()
    return means[int(0.025 * (reps - 1))], means[int(0.975 * (reps - 1))]


def evaluate(model: dict[str, Any], rows: list[dict[str, Any]]) -> dict[str, Any]:
    n = len(rows)
    mb = ml = kb = kl = 0.0
    per_market: dict[str, list[float]] = {}
    for r in rows:
        p = pl.edge_predict(model, r["x"], r["mid"])
        b1, l1 = loss_terms(p, r["y"])
        b0, l0 = loss_terms(r["mid"], r["y"])
        mb += b1
        ml += l1
        kb += b0
        kl += l0
        acc = per_market.setdefault(r["ticker"], [0.0, 0])
        acc[0] += l0 - l1
        acc[1] += 1
    lo, hi = bootstrap_gain_ci({k: (v[0], v[1]) for k, v in per_market.items()})
    # EV at the close ask: first qualifying minute per market, $5 all-in.
    pnls: list[float] = []
    seen: set[str] = set()
    for r in rows:
        if r["ticker"] in seen:
            continue
        p = pl.edge_predict(model, r["x"], r["mid"])
        side, ev = ev_side(p, r["yes_ask"], r["no_ask"])
        if side is None or ev < EV_MARGIN:
            continue
        ask = r["yes_ask"] if side == "YES" else r["no_ask"]
        c, cost, _ = pl.size_all_in(ask)
        if c <= 0:
            continue
        seen.add(r["ticker"])
        won = (r["y"] == 1) == (side == "YES")
        pnls.append((c - cost) if won else -cost)
    pnl_lo = pnl_hi = float("nan")
    if pnls:
        import simulate as sim

        _, pnl_lo, pnl_hi = sim.bootstrap_ci(pnls, n=BOOTSTRAP_REPS)
    return {
        "n_holdout": n,
        "n_markets_holdout": len(per_market),
        "model_brier": mb / n,
        "market_brier": kb / n,
        "model_logloss": ml / n,
        "market_logloss": kl / n,
        "logloss_gain": (kl - ml) / n,
        "logloss_gain_ci_low": lo,
        "logloss_gain_ci_high": hi,
        "sim_trades": len(pnls),
        "sim_pnl": sum(pnls),
        "sim_pnl_per_bet_ci_low": pnl_lo,
        "sim_pnl_per_bet_ci_high": pnl_hi,
        "sim_hit_rate": (sum(1 for x in pnls if x > 0) / len(pnls)) if pnls else 0.0,
    }


def beats_market(metrics: dict[str, Any]) -> bool:
    return (
        metrics["n_markets_holdout"] >= MIN_OOS_MARKETS
        and metrics["model_brier"] < metrics["market_brier"]
        and metrics["model_logloss"] < metrics["market_logloss"]
        and metrics["logloss_gain_ci_low"] > 0.0
    )


def split_rows(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    import simulate as sim

    is_days, _ = sim.split_days(sorted({r["day"] for r in rows}))
    return [r for r in rows if r["day"] in is_days], [r for r in rows if r["day"] not in is_days]


# --- export -------------------------------------------------------------------

def export(model: dict[str, Any], metrics: dict[str, Any], path: Path, fixture: bool) -> None:
    payload = {
        "version": SCHEMA,
        "schema": SCHEMA,
        "kind": "market_offset",
        "fixture": fixture,
        "feature_names": FEATURE_NAMES,
        "weights": model["weights"],
        "bias": model["bias"],
        "mean": model["mean"],
        "std": model["std"],
        "ev_margin": EV_MARGIN,
        "metrics": {k: v for k, v in metrics.items() if isinstance(v, (int, float))},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"wrote {path}", flush=True)


def write_manifest(metrics: dict[str, Any], path: Path, fixture: bool, trained_at: str | None = None) -> None:
    payload = {
        "version": str(SCHEMA),
        "schema": SCHEMA,
        "fixture": fixture,
        "trained_at": trained_at or datetime.now(timezone.utc).isoformat(),
        "n_samples": int(metrics["n_holdout"]),
        "n_holdout": int(metrics["n_holdout"]),
        "n_markets_holdout": int(metrics["n_markets_holdout"]),
        "model_brier": metrics["model_brier"],
        "market_brier": metrics["market_brier"],
        "model_logloss": metrics["model_logloss"],
        "market_logloss": metrics["market_logloss"],
        "logloss_gain_ci_low": metrics["logloss_gain_ci_low"],
        "logloss_gain_ci_high": metrics["logloss_gain_ci_high"],
        "sim_trades": metrics["sim_trades"],
        "sim_pnl": metrics["sim_pnl"],
        "sim_hit_rate": metrics["sim_hit_rate"],
        "model_asset": "edge_model.json",
        "tag": "edge-model-latest",
        "beats_market": (not fixture) and beats_market(metrics),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"wrote {path}", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(BACKTEST / "cache"))
    ap.add_argument("--fetch", action="store_true", help="refresh the backtest cache first")
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--out", default=str(ML_DIR / "edge_model.json"))
    ap.add_argument("--manifest", default=str(ML_DIR / "edge_model_manifest.json"))
    ap.add_argument("--fixture", action="store_true", help="synthetic rows; output never activates")
    ap.add_argument("--l2", type=float, default=L2)
    args = ap.parse_args()

    if args.fixture:
        rows = fixture_rows()
    else:
        cache = Path(args.cache)
        if args.fetch:
            from fetch import run_fetch

            print(run_fetch(cache, days=args.days), flush=True)
        rows = rows_from_cache(cache)
        if len(rows) < MIN_TRAIN_ROWS:
            print(f"only {len(rows)} decision rows (< {MIN_TRAIN_ROWS}); refusing to train", file=sys.stderr)
            return 2

    train, hold = split_rows(rows)
    print(f"rows {len(rows)} train {len(train)} holdout {len(hold)}", flush=True)
    if not train or not hold:
        print("need both train and holdout days", file=sys.stderr)
        return 2
    metrics = evaluate(fit_model(train, args.l2), hold)
    metrics["n_train"] = len(train)
    print(json.dumps(metrics, indent=2), flush=True)
    final = fit_model(rows, args.l2)
    print("weights", dict(zip(FEATURE_NAMES, (round(w, 4) for w in final["weights"]))), "bias", round(final["bias"], 4), flush=True)
    export(final, metrics, Path(args.out), fixture=args.fixture)
    write_manifest(metrics, Path(args.manifest), fixture=args.fixture)
    print(f"beats_market={(not args.fixture) and beats_market(metrics)}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
