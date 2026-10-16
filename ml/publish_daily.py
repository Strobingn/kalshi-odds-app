#!/usr/bin/env python3
"""Daily edge-model train and publish for Bitcoin Kalshi (DipHunter / Kashi).

Fetches public Kalshi settled markets and public Coinbase candles for
KXBTC15M, KXETH15M, and KXSOL15M. Does not read Dirk's Kalshi account key.

Writes a model only when training finishes. Copies ``ml/published/`` and
(with ``--github``) creates release ``model-YYYYMMDD`` only when the
fee-aware out-of-sample gate says ``beat_market``.

    python3 ml/publish_daily.py
    python3 ml/publish_daily.py --github
    python3 ml/publish_daily.py --days 30
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import publish_model
import train_edge as te

ML_DIR = Path(__file__).resolve().parent


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=3650)
    ap.add_argument("--max-markets", type=int, default=50_000)
    ap.add_argument("--series", default=",".join(te.DEFAULT_SERIES))
    ap.add_argument("--github", action="store_true")
    ap.add_argument("--fixture", action="store_true", help="Test mode. Never publishable.")
    ap.add_argument("--out", default=str(ML_DIR / "edge_model.json"))
    ap.add_argument("--manifest", default=str(ML_DIR / "edge_model_manifest.json"))
    ap.add_argument("--published", default=str(ML_DIR / "published"))
    args = ap.parse_args(argv)

    train_argv = [
        "--days", str(args.days),
        "--max-markets", str(args.max_markets),
        "--series", args.series,
        "--out", args.out,
        "--manifest", args.manifest,
    ]
    if args.fixture:
        train_argv.append("--fixture")
    rc = te.main(train_argv)
    if rc != 0:
        print(f"training exited {rc} — not publishing", flush=True)
        return rc
    publish_argv = ["--model", args.out, "--manifest", args.manifest, "--published", args.published]
    if args.github:
        publish_argv.append("--github")
    return publish_model.main(publish_argv)


if __name__ == "__main__":
    sys.exit(main())
