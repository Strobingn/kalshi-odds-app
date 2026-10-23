"""The shipped scalper_model.onnx must give what the JSON trees give (ScalperModel.predict), everywhere.

    pip install numpy onnx onnxruntime
    PYTHONPATH=tools/research/scalp python3 tools/research/scalp/test_export_onnx.py
"""
import hashlib
import json
import os
import sys
import tempfile

import numpy as np
import onnx
import onnxruntime as ort

import export_onnx

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
JSON = os.path.join(ROOT, "app/src/main/assets/scalper_model.json")
ONNX = os.path.join(ROOT, "app/src/main/assets/scalper_model.onnx")
PARITY = os.path.join(ROOT, "app/src/test/resources/scalper_parity.json")

TOL = 1e-4  # cents: float32 leaf sums vs double


def session(path):
    o = ort.SessionOptions()
    o.intra_op_num_threads = 1
    return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])


def run(sess, x):
    return sess.run(["y"], {"x": x.astype(np.float32)})[0][:, 0].astype(np.float64)


def edge_rows(model, rng):
    """Rows whose features sit exactly on, just under and just over the trees' thresholds, plus NaNs."""
    n = len(model["features"])
    ths = [[] for _ in range(n)]
    for t in model["trees"]:
        for f, th in zip(t["f"], t["t"]):
            if f >= 0:
                ths[f].append(th)
    rows = []
    for _ in range(3000):
        r = np.empty(n, dtype=np.float32)
        for k in range(n):
            if not ths[k]:
                r[k] = np.float32(rng.normal())
                continue
            th = ths[k][rng.integers(len(ths[k]))]
            f = np.float32(th)
            pick = rng.integers(4)
            if pick == 0:
                r[k] = f
            elif pick == 1:
                r[k] = np.nextafter(f, np.float32(np.inf), dtype=np.float32)
            elif pick == 2:
                r[k] = np.nextafter(f, np.float32(-np.inf), dtype=np.float32)
            else:
                r[k] = np.float32(th + rng.normal() * (abs(th) + 1e-3))
        if rng.random() < 0.1:
            r[rng.integers(n)] = np.nan
        rows.append(r)
    return np.array(rows, dtype=np.float32)


def main():
    raw = open(JSON, "rb").read()
    model = json.loads(raw)
    sha = hashlib.sha256(raw).hexdigest()

    # The shipped file is current: built from this very scalper_model.json, for the same features.
    shipped = onnx.load(ONNX)
    onnx.checker.check_model(shipped)
    props = {p.key: p.value for p in shipped.metadata_props}
    assert props["source_sha256"] == sha, "scalper_model.onnx was built from another scalper_model.json: rerun export_onnx.py"
    assert props["features"] == ",".join(model["features"])

    sess = session(ONNX)

    # 1. The recorded window the Kotlin parity test uses: the study's own predictions.
    fx = json.load(open(PARITY))
    x = np.array([r["x"] for r in fx["rows"]], dtype=np.float32)
    # ONNX gives the trees' sum: a classifier's log-odds ("margin"), or an older regression file's cents ("pred").
    want = np.array([r.get("margin", r["pred"]) for r in fx["rows"]])
    got = run(sess, x)
    worst = np.max(np.abs(got - want))
    assert worst < TOL, f"recorded rows: worst {worst}"
    ref = export_onnx.reference_predict(model, x)
    assert np.max(np.abs(ref - want)) < 1e-9, "reference evaluator disagrees with the study"

    # 2. Values on, just under and just over every threshold, and missing values.
    rng = np.random.default_rng(7)
    xe = edge_rows(model, rng)
    worst = np.max(np.abs(run(sess, xe) - export_onnx.reference_predict(model, xe)))
    assert worst < TOL, f"threshold-edge rows: worst {worst}"

    # 3. One row at a time (how the app calls it) gives the same as a batch.
    one = np.array([run(sess, xe[i:i + 1])[0] for i in range(50)])
    assert np.max(np.abs(one - run(sess, xe[:50]))) == 0.0

    # 4. A fresh export (what the retrain workflow writes) answers exactly as the shipped file.
    with tempfile.TemporaryDirectory() as d:
        fresh = os.path.join(d, "m.onnx")
        export_onnx.main(JSON, fresh)
        assert np.array_equal(run(session(fresh), xe), run(sess, xe)), "a fresh export answers differently"

    print(f"ok: {len(x)} recorded rows, {len(xe)} threshold-edge rows, tolerance {TOL} cents")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("FAIL:", e)
        sys.exit(1)
