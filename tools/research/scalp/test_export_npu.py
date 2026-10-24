"""The NPU graph (scalper_model_npu.onnx) must pick the very leaves the JSON trees pick, and be fully quantized (QDQ),
which is what the Snapdragon HTP NPU runs.

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


def main():
    raw = open(JSON, "rb").read()
    model = json.loads(raw)
    shipped = onnx.load(NPU)
    onnx.checker.check_model(shipped)
    props = {p.key: p.value for p in shipped.metadata_props}
    assert props["source_sha256"] == hashlib.sha256(raw).hexdigest(), "scalper_model_npu.onnx is from another scalper_model.json: rerun export_npu.py"
    assert props["features"] == ",".join(model["features"])
    ops = {n.op_type for n in shipped.graph.node}
    assert ops <= {"MatMul", "Sub", "Abs", "Relu", "Reshape", "QuantizeLinear", "DequantizeLinear"}, f"an op the NPU path was not built for: {ops}"
    # Every compute node reads only dequantized tensors (or shape constants) and feeds only a QuantizeLinear: QDQ units.
    produced = {o: n for n in shipped.graph.node for o in n.output}
    consumers = {}
    for n in shipped.graph.node:
        for i in n.input:
            consumers.setdefault(i, []).append(n)
    for n in shipped.graph.node:
        if n.op_type in ("QuantizeLinear", "DequantizeLinear"):
            continue
        for i in n.input:
            if i in ("shape3", "shape_out"):
                continue
            assert i in produced and produced[i].op_type == "DequantizeLinear", f"{n.op_type} reads {i} not from a DequantizeLinear"
        for o in n.output:
            assert all(c.op_type == "QuantizeLinear" for c in consumers.get(o, [])) and consumers.get(o), f"{n.op_type} output {o} is not quantized"
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

    with tempfile.TemporaryDirectory() as d:
        fresh = os.path.join(d, "n.onnx")
        export_npu.main(JSON, fresh)
        assert onnx.load(fresh).SerializeToString() == shipped.SerializeToString(), "a fresh export differs from the shipped file"

    print(f"ok: {len(x)} rows ({len(x_rec)} recorded, {len(x_edge)} on the splits, 1 all missing) pick the trees' leaves exactly through the quantized graph")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("FAIL:", e)
        sys.exit(1)
