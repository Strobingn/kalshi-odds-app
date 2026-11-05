#!/usr/bin/env python3
"""Checks for the up/down model (stdlib + numpy + onnx/onnxruntime):
  * the shipped ONNX file was built from the shipped JSON (metadata sha256, features) and gives its numbers;
  * updown_features.py still gives the fixture's features and design rows (the Kotlin port is held to the same file);
  * the feature helpers behave at their edges (missing prints, a cold volatility estimate, a one-sided book).
Run from the repository root."""
from __future__ import annotations

import hashlib
import json
import math
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import updown_features as uf  # noqa: E402
from export_updown_onnx import build, numpy_predict  # noqa: E402
from make_updown_fixture import design  # noqa: E402

ASSETS = ROOT / "app/src/main/assets"
FIXTURE = ROOT / "app/src/test/resources/updown_parity.json"


def test_onnx_matches_json():
    import onnxruntime as ort
    src = (ASSETS / "updown_model.json").read_bytes()
    onnx_bytes = (ASSETS / "updown_model.onnx").read_bytes()
    s = ort.InferenceSession(onnx_bytes)
    meta = s.get_modelmeta().custom_metadata_map
    m = json.loads(src)
    assert meta["source_sha256"] == hashlib.sha256(src).hexdigest(), "updown_model.onnx was built from other JSON"
    assert meta["features"] == ",".join(m["features"])
    rng = np.random.default_rng(3)
    X = rng.normal(scale=2.0, size=(400, len(m["features"]))).astype(np.float32)
    got = np.array([s.run(None, {"x": X[i:i + 1]})[0][0, 0] for i in range(len(X))])
    assert np.abs(got - numpy_predict(src, X)).max() < 1e-5


def test_fixture_features():
    fx = json.loads(FIXTURE.read_text())
    m = json.loads((ASSETS / "updown_model.json").read_text())
    assert fx["names"] == uf.NAMES and fx["model_features"] == m["features"]
    assert len(fx["cases"]) >= 10
    for c in fx["cases"]:
        f = lambda v: math.nan if v is None else v
        row = uf.features([f(v) for v in c["spot"]], c["strike"], c["tte_s"], c["sec_in"], f(c["yes_bid"]),
                          f(c["yes_bid_qty"]), f(c["yes_ask"]), f(c["yes_ask_qty"]), [f(v) for v in c["mid"]])
        assert row is not None
        assert max(abs(a - b) for a, b in zip(row, c["features"])) < 1e-7, c["ticker"]
        x = design(row, m["features"])
        assert max(abs(a - b) for a, b in zip(x, c["x"])) < 1e-7
        z = sum(w * v for w, v in zip(m["weights"], x)) + m["bias"]
        assert abs(1 / (1 + math.exp(-z)) - c["up"]) < 1e-8


def test_edges():
    nan = math.nan
    flat = [100.0] * 1201
    assert uf.std1m(flat) is None or uf.std1m(flat) == 0.0
    walk = [100.0 * math.exp(0.0001 * math.sin(i)) for i in range(1201)]
    assert uf.std1m(walk) > 0
    assert uf.std1m(walk[:100]) is None  # fewer than 120 one-second returns
    stale = walk[:-6] + [nan] * 6
    assert uf.value_ago(stale, 0) is None  # no print in the last 5 s: no fresh spot
    assert uf.features(stale, 100.0, 300.0, 600, 0.4, 10, 0.42, 10, [0.41] * 601) is None
    assert uf.features(walk, 100.0, 300.0, 600, nan, 10, 0.42, 10, [0.41] * 601) is None  # one-sided book
    row = uf.features(walk, 100.0, 300.0, 600, 0.40, 10, 0.42, 30, [0.41] * 601)
    i = {n: k for k, n in enumerate(uf.NAMES)}
    assert abs(row[i["mid"]] - 0.41) < 1e-12 and abs(row[i["imb"]] + 0.5) < 1e-12 and abs(row[i["dmid30"]]) < 1e-12
    assert 0.0 < row[i["fair"]] < 1.0


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
