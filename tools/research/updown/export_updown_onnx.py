#!/usr/bin/env python3
"""Writes the up/down model (updown_model.json, a logistic regression) as ONNX for the app.

Graph: x float32 [1, n] -> MatMul W [n, 1] -> Add b -> Sigmoid -> y float32 [1, 1] (the chance of UP).
Metadata: source_sha256 = sha256 of the exact JSON bytes and features = the comma-joined feature names, the same
contract OnnxTrees.open checks for the scalper, so a stale ONNX file can never run beside newer JSON.

Usage: export_updown_onnx.py updown_model.json updown_model.onnx
"""
from __future__ import annotations

import hashlib
import json
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

OPSET = 13
IR_VERSION = 7  # ONNX Runtime Mobile 1.29 reads it; the scalper's file uses the same family


def build(json_bytes: bytes) -> onnx.ModelProto:
    m = json.loads(json_bytes)
    feats = m["features"]
    n = len(feats)
    w = np.array(m["weights"], dtype=np.float32).reshape(n, 1)
    b = np.array([[m["bias"]]], dtype=np.float32)
    x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, n])
    y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 1])
    nodes = [
        helper.make_node("MatMul", ["x", "W"], ["xw"]),
        helper.make_node("Add", ["xw", "b"], ["z"]),
        helper.make_node("Sigmoid", ["z"], ["y"]),
    ]
    g = helper.make_graph(nodes, "updown_logistic", [x], [y],
                          initializer=[numpy_helper.from_array(w, "W"), numpy_helper.from_array(b, "b")])
    model = helper.make_model(g, opset_imports=[helper.make_opsetid("", OPSET)], producer_name="kalshi-odds updown")
    model.ir_version = IR_VERSION
    for k, v in (("source_sha256", hashlib.sha256(json_bytes).hexdigest()), ("features", ",".join(feats)),
                 ("kind", "updown_logistic")):
        e = model.metadata_props.add()
        e.key, e.value = k, v
    onnx.checker.check_model(model)
    return model


def numpy_predict(json_bytes: bytes, rows: np.ndarray) -> np.ndarray:
    m = json.loads(json_bytes)
    z = rows.astype(np.float64) @ np.array(m["weights"]) + m["bias"]
    return 1.0 / (1.0 + np.exp(-z))


if __name__ == "__main__":
    src = open(sys.argv[1], "rb").read()
    onnx.save(build(src), sys.argv[2])
    print("wrote", sys.argv[2])
