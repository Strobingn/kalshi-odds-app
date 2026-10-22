"""Convert the app's scalper trees (scalper_model.json) into an ONNX model the app runs with ONNX Runtime.

    python3 tools/research/scalp/export_onnx.py app/src/main/assets/scalper_model.json app/src/main/assets/scalper_model.onnx

The JSON trees stay the source of truth (the trainer writes them, the Kotlin fallback reads them); this script
is a straight translation into one ai.onnx.ml TreeEnsembleRegressor, so both give the same number:

- The app compares each float32 feature with the tree's double threshold (`x.toDouble() <= t`). ONNX
  thresholds are float32, so each one becomes the largest float32 not above it: for any float32 x,
  x <= t  <=>  x <= that float. Splits land exactly where they did.
- A missing feature (NaN) goes right in both (`NaN <= t` is false; missing_value_tracks_true = 0).
- Leaves are summed with no base value, as `ScalperModel.predict` does.

Input `x`: float32 [N, len(features)], in the JSON's feature order. Output `y`: float32 [N, 1], cents a contract.
The source JSON's sha256 and the feature names are written into the model's metadata, so the app can refuse an
ONNX file that was built from a different JSON.
"""
import hashlib
import json
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper

OPSET_ML = 3
OPSET = 17


def float_threshold(t: float) -> float:
    """Largest float32 not above [t]: for float32 x, x <= t exactly when x <= this."""
    f = np.float32(t)
    if float(f) > t:
        f = np.nextafter(f, np.float32(-np.inf), dtype=np.float32)
    return float(f)


def build(model: dict, source_sha256: str) -> onnx.ModelProto:
    features = model["features"]
    tree_ids, node_ids, feature_ids, modes, values, true_ids, false_ids = [], [], [], [], [], [], []
    target_tree, target_node, target_ids, target_weights = [], [], [], []
    for ti, t in enumerate(model["trees"]):
        f, th, left, right, v = t["f"], t["t"], t["l"], t["r"], t["v"]
        if not (len(f) == len(th) == len(left) == len(right) == len(v)):
            raise ValueError(f"tree {ti}: arrays differ in length")
        for ni in range(len(f)):
            tree_ids.append(ti)
            node_ids.append(ni)
            if f[ni] >= 0:
                if not 0 <= f[ni] < len(features):
                    raise ValueError(f"tree {ti} node {ni}: feature {f[ni]} out of range")
                feature_ids.append(int(f[ni]))
                modes.append("BRANCH_LEQ")
                values.append(float_threshold(float(th[ni])))
                true_ids.append(int(left[ni]))
                false_ids.append(int(right[ni]))
            else:
                feature_ids.append(0)
                modes.append("LEAF")
                values.append(0.0)
                true_ids.append(0)
                false_ids.append(0)
                target_tree.append(ti)
                target_node.append(ni)
                target_ids.append(0)
                target_weights.append(float(v[ni]))

    node = helper.make_node(
        "TreeEnsembleRegressor",
        inputs=["x"],
        outputs=["y"],
        domain="ai.onnx.ml",
        n_targets=1,
        aggregate_function="SUM",
        post_transform="NONE",
        nodes_treeids=tree_ids,
        nodes_nodeids=node_ids,
        nodes_featureids=feature_ids,
        nodes_modes=modes,
        nodes_values=values,
        nodes_truenodeids=true_ids,
        nodes_falsenodeids=false_ids,
        nodes_missing_value_tracks_true=[0] * len(node_ids),
        target_treeids=target_tree,
        target_nodeids=target_node,
        target_ids=target_ids,
        target_weights=target_weights,
    )
    graph = helper.make_graph(
        [node],
        "scalper_trees",
        [helper.make_tensor_value_info("x", TensorProto.FLOAT, ["N", len(features)])],
        [helper.make_tensor_value_info("y", TensorProto.FLOAT, ["N", 1])],
    )
    m = helper.make_model(
        graph,
        opset_imports=[helper.make_opsetid("", OPSET), helper.make_opsetid("ai.onnx.ml", OPSET_ML)],
        producer_name="kalshi-odds-app/export_onnx.py",
    )
    m.ir_version = 8
    helper.set_model_props(m, {"source_sha256": source_sha256, "features": ",".join(features)})
    onnx.checker.check_model(m)
    return m


def reference_predict(model: dict, x: np.ndarray) -> np.ndarray:
    """What ScalperModel.predict computes: float32 features against double thresholds, leaves summed in double."""
    out = np.zeros(len(x))
    for r, row in enumerate(x.astype(np.float32)):
        s = 0.0
        for t in model["trees"]:
            i = 0
            while t["f"][i] >= 0:
                i = t["l"][i] if float(row[t["f"][i]]) <= t["t"][i] else t["r"][i]
            s += t["v"][i]
        out[r] = s
    return out


def main(src: str, dst: str) -> None:
    raw = open(src, "rb").read()
    model = json.loads(raw)
    m = build(model, hashlib.sha256(raw).hexdigest())
    onnx.save(m, dst)
    print(f"{dst}: {len(model['trees'])} trees, {len(model['features'])} features")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: export_onnx.py scalper_model.json scalper_model.onnx")
    main(sys.argv[1], sys.argv[2])
