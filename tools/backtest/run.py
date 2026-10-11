#!/usr/bin/env python3
"""Fetch → simulate → report. Isolated from production Android sources."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from fetch import run_fetch
from report import run_report
from simulate import run_sim


def write_sample_fixture(cache: Path, dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    markets = []
    p = cache / "markets.jsonl"
    if p.is_file():
        for i, line in enumerate(p.open()):
            markets.append(json.loads(line))
            if len(markets) >= 3:
                break
    (dest / "markets.jsonl").write_text("".join(json.dumps(m) + "\n" for m in markets))
    cdir = dest / "candles"
    cdir.mkdir(exist_ok=True)
    for m in markets:
        src = cache / "candles" / f"{m['ticker']}.json"
        if src.is_file():
            shutil.copy(src, cdir / f"{m['ticker']}.json")
    # tiny spot slice: first 200 bars
    for name in ("spot_BTC-USD.json", "spot_ETH-USD.json", "spot_SOL-USD.json"):
        src = cache / name
        if src.is_file():
            bars = json.loads(src.read_text())[:200]
            (dest / name).write_text(json.dumps(bars, separators=(",", ":")))
    if (cache / "meta.json").is_file():
        shutil.copy(cache / "meta.json", dest / "meta.json")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--days", type=int, default=28)
    p.add_argument("--cache", default=str(Path(__file__).parent / "cache"))
    p.add_argument("--skip-fetch", action="store_true")
    p.add_argument("--artifacts", default="/opt/cursor/artifacts")
    p.add_argument("--edge-model", default=None, help="schema-2 edge_model.json to replay (default: blend only)")
    args = p.parse_args()
    cache = Path(args.cache)
    if not args.skip_fetch:
        print(run_fetch(cache, days=args.days))
    engine_kwargs = {}
    if args.edge_model:
        engine_kwargs["edge_model"] = json.loads(Path(args.edge_model).read_text())
    result = run_sim(cache, engine_kwargs)
    meta = json.loads((cache / "meta.json").read_text()) if (cache / "meta.json").is_file() else {}
    run_report(result, ROOT, Path(args.artifacts), meta)
    write_sample_fixture(cache, Path(__file__).parent / "fixtures")
    san = result.get("sanity") or {}
    stl = result.get("settlement") or {}
    print("done", result["n_markets"], "markets", result["n_decisions"], "decisions")
    print("sanity", san.get("passed"), "settlement", stl.get("passed"))
    for s, pack in result["strategies"].items():
        o = pack["oos"]
        print(f"  {s:20s} oos n={o['n']:4d} pnl={o['pnl']:+8.2f} wr={o['win_rate']}")


if __name__ == "__main__":
    main()
