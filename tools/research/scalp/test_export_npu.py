"""The NPU graph (scalper_model_npu.onnx) must pick the very leaves the JSON trees pick, in fp32 and in fp16.

    pip install numpy onnx onnxruntime
    PYTHONPATH=tools/research/scalp python3 tools/research/scalp/test_export_npu.py
"""
import hashlib
import json
import os
import sys
import tempfile

import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper

import export_npu
import export_onnx
from test_export_onnx import edge_rows

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
JSON = os.path.join(ROOT, "app/src/main/assets/scalper_model.json")
NPU = os.path.join(ROOT, "app/src/main/assets/scalper_model_npu.onnx")
PARITY = os.path.join(ROOT, "app/src/test/resources/scalper_parity.json")


def run_cpu(path, b):
    o = ort.SessionOptions()
    o.intra_op_num_threads = 1
    s = ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])
    return np.vstack([s.run(["leaf"], {"bins": b[i:i + 1]})[0] for i in range(len(b))])


def run_fp16(model_proto, b):
    """The same graph with every number rounded to fp16 at every step: what the NPU's fp16 math can do at worst."""
    w = {i.name: numpy_helper.to_array(i) for i in model_proto.graph.initializer}
    h = lambda v: np.asarray(v, dtype=np.float16)
    nt, _, ni = w["shape3"]
    out = []
    for r in b:
        t = h(h(r[None, :]).astype(np.float32) @ h(w["A"]).astype(np.float32))
        go = h(np.clip(h(h(w["K1"]) - t), 0, 1))
        p = h(np.matmul(go.reshape(nt, 1, ni).astype(np.float32), h(w["C"]).astype(np.float32)))
        hit = h(np.maximum(h(1.0 - np.abs(h(p - h(w["D"])))), 0))
        out.append(hit.reshape(1, -1).astype(np.float32))
    return np.vstack(out)


def main():
    raw = open(JSON, "rb").read()
    model = json.loads(raw)
    shipped = onnx.load(NPU)
    onnx.checker.check_model(shipped)
    props = {p.key: p.value for p in shipped.metadata_props}
    assert props["source_sha256"] == hashlib.sha256(raw).hexdigest(), "scalper_model_npu.onnx is from another scalper_model.json: rerun export_npu.py"
    assert props["features"] == ",".join(model["features"])
    ops = {n.op_type for n in shipped.graph.node}
    assert ops <= {"MatMul", "Sub", "Abs", "Clip", "Relu", "Reshape"}, f"an op the NPU path was not built for: {ops}"
    assert all(o.domain in ("", "ai.onnx") for o in shipped.opset_import), "no ai.onnx.ml ops on the NPU"

    fx = json.load(open(PARITY))
    x_rec = np.array([r["x"] for r in fx["rows"]], dtype=np.float32)
    want_rec = np.array([r.get("margin", r["pred"]) for r in fx["rows"]])
    x_edge = edge_rows(model, np.random.default_rng(11))
    x_nan = np.full((1, len(model["features"])), np.nan, dtype=np.float32)
    x = np.vstack([x_rec, x_edge, x_nan])
    want = export_onnx.reference_predict(model, x)
    assert np.array_equal(want[:len(x_rec)], want_rec) or np.max(np.abs(want[:len(x_rec)] - want_rec)) < 1e-9

    b = export_npu.bins(model, x)
    assert b.max() < 2048 and np.all(b == np.round(b))

    got_cpu = export_npu.margin_from_leaf(model, run_cpu(NPU, b))
    assert np.array_equal(got_cpu, want), f"fp32 graph: worst {np.max(np.abs(got_cpu - want))}"

    got_16 = export_npu.margin_from_leaf(model, run_fp16(shipped, b))
    assert np.array_equal(got_16, want), f"fp16 graph: worst {np.max(np.abs(got_16 - want))}"

    with tempfile.TemporaryDirectory() as d:
        fresh = os.path.join(d, "n.onnx")
        export_npu.main(JSON, fresh)
        assert onnx.load(fresh).SerializeToString() == shipped.SerializeToString(), "a fresh export differs from the shipped file"

    print(f"ok: {len(x)} rows ({len(x_rec)} recorded, {len(x_edge)} on the splits, 1 all missing) pick the trees' leaves exactly, fp32 and fp16")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("FAIL:", e)
        sys.exit(1)
