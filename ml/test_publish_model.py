#!/usr/bin/env python3
"""Publish gate: a model that loses to the market must not be published."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import publish_model as pm  # noqa: E402


def _model_bytes() -> bytes:
    payload = {
        "version": 2,
        "kind": "logistic",
        "feature_names": [
            "dist_to_strike_vol", "tte_frac", "market_mid", "imbalance", "spread",
            "momentum", "realized_vol", "cross_asset", "time_of_day", "digital_fair",
        ],
        "weights": [0.0] * 10,
        "bias": 0.0,
        "mean": [0.0] * 10,
        "std": [1.0] * 10,
    }
    return (json.dumps(payload, indent=2) + "\n").encode("utf-8")


def _manifest(model: bytes, **overrides) -> dict:
    body = {
        "version": "2",
        "trained_at": "2026-10-05T08:17:00Z",
        "package": "com.dirk.kalshiodds.kashi",
        "sha256": hashlib.sha256(model).hexdigest(),
        "series": ["KXBTC15M", "KXETH15M", "KXSOL15M"],
        "n_samples": 12000,
        "n_rows": 12000,
        "n_markets": 2500,
        "n_holdout": 800,
        "model_brier": 0.160,
        "market_brier": 0.186,
        "model_logloss": 0.480,
        "market_logloss": 0.520,
        "sim_pnl": 25.0,
        "sim_trades": 40,
        "market_pnl": -10.0,
        "synthetic": False,
        "data_source": "kalshi_settled_coinbase_spot_v1",
        "beat_market": True,
        "beats_market": True,
        "publishable": True,
        "tag": "model-20261005",
    }
    body.update(overrides)
    return body


class PublishGateTest(unittest.TestCase):
    def test_losing_model_is_not_published(self) -> None:
        model = _model_bytes()
        manifest = _manifest(
            model,
            model_brier=0.30,
            market_brier=0.18,
            model_logloss=0.70,
            market_logloss=0.50,
            sim_pnl=-8.0,
            market_pnl=4.0,
            beat_market=False,
            beats_market=False,
            publishable=False,
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model_path = root / "edge_model.json"
            man_path = root / "edge_model_manifest.json"
            published = root / "published"
            model_path.write_bytes(model)
            man_path.write_text(json.dumps(manifest), encoding="utf-8")
            calls: list[list[str]] = []

            def runner(cmd: list[str]):
                calls.append(cmd)
                raise AssertionError("gh must not be invoked when the model loses")

            rc = pm.main(
                ["--model", str(model_path), "--manifest", str(man_path),
                 "--published", str(published), "--github"],
                runner=runner,
            )
            self.assertEqual(rc, 3)
            self.assertFalse((published / "latest.json").exists())
            self.assertFalse((published / "model-20261005").exists())
            self.assertEqual(calls, [])

    def test_sha_or_package_mismatch_is_not_published(self) -> None:
        model = _model_bytes()
        bad_hash = _manifest(model, sha256="0" * 64)
        wrong_pkg = _manifest(model, package="com.dirk.kalshiodds")
        self.assertTrue(pm.refusal_reasons(model, bad_hash))
        self.assertTrue(pm.refusal_reasons(model, wrong_pkg))
        self.assertTrue(any("sha256" in r for r in pm.refusal_reasons(model, bad_hash)))
        self.assertTrue(any("package" in r for r in pm.refusal_reasons(model, wrong_pkg)))

    def test_passing_model_is_staged_and_released(self) -> None:
        model = _model_bytes()
        manifest = _manifest(model)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model_path = root / "edge_model.json"
            man_path = root / "edge_model_manifest.json"
            published = root / "published"
            model_path.write_bytes(model)
            man_path.write_text(json.dumps(manifest), encoding="utf-8")
            calls: list[list[str]] = []

            class Result:
                def __init__(self, code: int) -> None:
                    self.returncode = code
                    self.stdout = ""
                    self.stderr = ""

            def runner(cmd: list[str]):
                calls.append(cmd)
                if cmd[:3] == ["gh", "release", "view"]:
                    return Result(1)
                return Result(0)

            rc = pm.main(
                ["--model", str(model_path), "--manifest", str(man_path),
                 "--published", str(published), "--github"],
                runner=runner,
            )
            self.assertEqual(rc, 0)
            staged = json.loads((published / "latest.json").read_text())
            self.assertEqual(staged["tag"], "model-20261005")
            self.assertTrue(staged["beat_market"])
            self.assertEqual(staged["package"], "com.dirk.kalshiodds.kashi")
            self.assertEqual(
                staged["model_url"],
                "https://github.com/Strobingn/kalshi-odds-app/releases/download/model-20261005/edge_model.json",
            )
            self.assertTrue((published / "model-20261005" / "edge_model.json").is_file())
            create = next(c for c in calls if "release" in c and "create" in c)
            self.assertIn("model-20261005", create)
            self.assertNotIn("delete", " ".join(create))
            self.assertTrue(all("delete" not in " ".join(c) for c in calls))

    def test_fixture_daily_run_does_not_publish(self) -> None:
        import publish_daily

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            published = root / "published"
            rc = publish_daily.main(
                [
                    "--fixture",
                    "--out", str(root / "edge_model.json"),
                    "--manifest", str(root / "manifest.json"),
                    "--published", str(published),
                    "--github",
                ]
            )
            self.assertNotEqual(rc, 0)
            self.assertFalse((published / "latest.json").exists())


class WorkflowLockTest(unittest.TestCase):
    def test_workflow_does_not_delete_releases(self) -> None:
        yml = Path(__file__).resolve().parents[1] / ".github" / "workflows" / "train-edge-model.yml"
        text = yml.read_text(encoding="utf-8")
        self.assertNotIn("release delete", text)
        self.assertIn("publish_model.py", text)
        self.assertIn("KXBTC15M,KXETH15M,KXSOL15M", text)


if __name__ == "__main__":
    unittest.main()
