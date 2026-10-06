#!/usr/bin/env python3
"""Publish a trained edge model only when the fee-aware OOS gate passes.

Writes ``ml/published/model-YYYYMMDD/`` and, with ``--github``, creates the
GitHub release ``model-YYYYMMDD``. A model that loses to the market, fails
the package check, or fails the sha256 check is not copied and no release
is created.

Public market data only. This script never reads a Kalshi account key.

    python3 ml/publish_model.py
    python3 ml/publish_model.py --github
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable

import publish_gates as gates

REPO = Path(__file__).resolve().parents[1]
ML_DIR = REPO / "ml"
OWNER = "Strobingn"
REPO_NAME = "kalshi-odds-app"
TAG_RE = re.compile(r"^model-\d{8}$")
Runner = Callable[..., Any]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_pair(model_path: Path, manifest_path: Path) -> tuple[bytes, dict[str, Any]]:
    model_bytes = model_path.read_bytes()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("manifest is not an object")
    return model_bytes, manifest


def refusal_reasons(model_bytes: bytes, manifest: dict[str, Any]) -> list[str]:
    """Reasons to refuse a publish. Empty means the gate passed."""
    reasons: list[str] = []
    decision = gates.publish_decision(manifest)
    if not decision["publishable"]:
        reasons.extend(str(r) for r in decision["reasons"])
    beat = manifest.get("beat_market", manifest.get("beats_market"))
    if beat is not True:
        reasons.append("manifest beat_market is not true")
    if manifest.get("synthetic") is True:
        reasons.append("synthetic model")
    if manifest.get("beats_champion") is False:
        reasons.append("challenger does not beat the champion")
    package = manifest.get("package")
    if package != gates.PACKAGE_ID:
        reasons.append(f"package {package!r} != {gates.PACKAGE_ID}")
    tag = str(manifest.get("tag") or "")
    if not TAG_RE.fullmatch(tag):
        reasons.append(f"tag {tag!r} is not model-YYYYMMDD")
    digest = sha256_bytes(model_bytes)
    claimed = str(manifest.get("sha256") or "").lower()
    if claimed != digest:
        reasons.append("sha256 does not match edge_model.json")
    # De-duplicate while keeping order.
    seen: set[str] = set()
    out: list[str] = []
    for r in reasons:
        if r not in seen:
            seen.add(r)
            out.append(r)
    return out


def release_urls(tag: str) -> dict[str, str]:
    base = f"https://github.com/{OWNER}/{REPO_NAME}/releases/download/{tag}"
    return {
        "model_url": f"{base}/edge_model.json",
        "manifest_url": f"{base}/edge_model_manifest.json",
    }


def stage_published(model_bytes: bytes, manifest: dict[str, Any], root: Path) -> Path:
    tag = str(manifest["tag"])
    dest = root / tag
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "edge_model.json").write_bytes(model_bytes)
    (dest / "edge_model_manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    urls = release_urls(tag)
    latest = {
        "tag": tag,
        "package": gates.PACKAGE_ID,
        "beat_market": True,
        "sha256": manifest["sha256"],
        "trained_at": manifest.get("trained_at"),
        "model_url": urls["model_url"],
        "manifest_url": urls["manifest_url"],
        "model_path": f"ml/published/{tag}/edge_model.json",
        "manifest_path": f"ml/published/{tag}/edge_model_manifest.json",
    }
    root.mkdir(parents=True, exist_ok=True)
    (root / "latest.json").write_text(json.dumps(latest, indent=2) + "\n", encoding="utf-8")
    print(f"staged {dest}", flush=True)
    return dest


def _run(cmd: list[str], runner: Runner | None) -> Any:
    if runner is not None:
        return runner(cmd)
    return subprocess.run(cmd, check=False, capture_output=True, text=True)


def create_github_release(
    tag: str,
    model_path: Path,
    manifest_path: Path,
    manifest: dict[str, Any],
    runner: Runner | None = None,
) -> int:
    """Create ``model-YYYYMMDD``. Never deletes an existing release."""
    view = _run(["gh", "release", "view", tag], runner)
    if getattr(view, "returncode", 1) == 0:
        print(f"release {tag} already exists — not replacing it", flush=True)
        return 0
    series = ", ".join(manifest.get("series") or [])
    notes = "\n".join(
        [
            f"Edge model {tag} for {gates.PACKAGE_ID}",
            "",
            f"- trained_at: {manifest.get('trained_at')}",
            f"- series: {series}",
            f"- n_markets: {manifest.get('n_markets')}",
            f"- n_holdout: {manifest.get('n_holdout')}",
            f"- model_brier: {manifest.get('model_brier')} vs market {manifest.get('market_brier')}",
            f"- model_logloss: {manifest.get('model_logloss')} vs market {manifest.get('market_logloss')}",
            f"- sim_pnl: {manifest.get('sim_pnl')} vs market-follow {manifest.get('market_pnl')}",
            f"- beat_market: {manifest.get('beat_market')}",
            f"- sha256: {manifest.get('sha256')}",
        ]
    )
    created = _run(
        [
            "gh", "release", "create", tag,
            str(model_path),
            str(manifest_path),
            "--target", "kashi",
            "--title", f"Edge model {tag}",
            "--notes", notes,
        ],
        runner,
    )
    code = int(getattr(created, "returncode", 1))
    if code != 0:
        err = getattr(created, "stderr", "") or getattr(created, "stdout", "")
        print(f"gh release create failed ({code}): {err}", flush=True)
    else:
        print(f"created release {tag}", flush=True)
        print(release_urls(tag)["model_url"], flush=True)
    return code


def main(argv: list[str] | None = None, runner: Runner | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(ML_DIR / "edge_model.json"))
    ap.add_argument("--manifest", default=str(ML_DIR / "edge_model_manifest.json"))
    ap.add_argument("--published", default=str(ML_DIR / "published"))
    ap.add_argument("--github", action="store_true", help="Create the GitHub release with gh")
    args = ap.parse_args(argv)
    model_path = Path(args.model)
    manifest_path = Path(args.manifest)
    if not model_path.is_file() or not manifest_path.is_file():
        print("model or manifest missing — refusing publish", flush=True)
        return 2
    model_bytes, manifest = load_pair(model_path, manifest_path)
    reasons = refusal_reasons(model_bytes, manifest)
    if reasons:
        print("refusing publish:", flush=True)
        for r in reasons:
            print(f"  - {r}", flush=True)
        return 3
    dest = stage_published(model_bytes, manifest, Path(args.published))
    report = str(manifest.get("eval_report") or "")
    if report:
        src = model_path.resolve().parent / "reports" / report
        if src.is_file() and not (dest / report).exists():
            shutil.copyfile(src, dest / report)
            print(f"staged eval report {dest / report}", flush=True)
    if args.github:
        return create_github_release(
            str(manifest["tag"]),
            dest / "edge_model.json",
            dest / "edge_model_manifest.json",
            manifest,
            runner,
        )
    print("local publish only (pass --github to create the release)", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
