"""Build the NPU copy of the scalper trees (scalper_model_npu.onnx): a graph the Snapdragon NPU (QNN HTP) can run.

    python3 tools/research/scalp/export_npu.py app/src/main/assets/scalper_model.json app/src/main/assets/scalper_model_npu.onnx

Why a second file: the NPU runs only plain tensor ops (no ai.onnx.ml TreeEnsemble), and it computes in fp16, which
would move a feature across a split. So this graph never sees a raw feature value or a leaf value:

- In: `bins`, float32 [1, F]. bins[f] = how many of feature f's split thresholds lie below the value
  (`count(t < x)`, a missing value counts all of them). Then `x <= t_k` exactly when `bins[f] <= k`, and every
  number in the graph is a small whole number, exact in fp16. The app computes the bins (one binary search a feature).
- Out: `leaf`, float32 [1, T * L]. For tree t, slot l is 1 for the leaf the row lands in, else 0.
  The app adds those leaves' values in double, in tree order, so the sum is the Kotlin trees' sum to the last bit.

Graph (all static shapes; ops: MatMul, Sub, Abs, Relu, Reshape, each wrapped in QuantizeLinear / DequantizeLinear
with scale 1, because the HTP NPU runs only quantized graphs; every value is a small whole number, so it is exact):
  T    = bins @ A                      A [F, T*I]: one 1 per split column, picking that split's feature
  go   = Relu(K1 - T) - Relu(K1 - T - 1)   K1 = split's bin index + 1; 1 = go left (bins <= k)
  P    = Reshape(go, [T,1,I]) @ C      C [T, I, L]: +1 if the leaf is under the split's left child, -1 right
  leaf = Relu(1 - |P - D|)             D [T, 1, L]: left turns on the leaf's path; padding slots D = -100
Padding: trees with fewer splits or leaves get zero columns (A, C) and leaf slots that can never be 1.

Metadata: source_sha256 and features (as in scalper_model.onnx), plus trees / splits / leaves (I, L), so the app
refuses a file built from other trees or laid out differently.
"""
import hashlib
import json
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

OPSET = 21
PAD_D = -100.0


def edges(model: dict) -> list:
    """Per feature: its split thresholds, sorted, without repeats (what the app's binary search uses)."""
    out = [set() for _ in model["features"]]
    for t in model["trees"]:
        for f, th in zip(t["f"], t["t"]):
            if f >= 0:
                out[f].add(float(th))
    return [np.array(sorted(s), dtype=np.float64) for s in out]


def bins(model: dict, x: np.ndarray) -> np.ndarray:
    """[N, F] float32 features -> [N, F] float32 bins, exactly as the app makes them."""
    e = edges(model)
    xd = x.astype(np.float32).astype(np.float64)
    b = np.empty(xd.shape, dtype=np.float32)
    for f, ef in enumerate(e):
        col = xd[:, f]
        v = np.searchsorted(ef, col, side="left").astype(np.float32)  # count(t < x)
        v[np.isnan(col)] = len(ef)
        b[:, f] = v
    return b


def layout(model: dict):
    """Per tree: split node ids and leaf node ids, each in node-index order."""
    splits, leaves = [], []
    for t in model["trees"]:
        splits.append([i for i, f in enumerate(t["f"]) if f >= 0])
        leaves.append([i for i, f in enumerate(t["f"]) if f < 0])
    return splits, leaves


def build(model: dict, source_sha256: str) -> onnx.ModelProto:
    feats = model["features"]
    nf, nt = len(feats), len(model["trees"])
    e = edges(model)
    splits, leaves = layout(model)
    ni = max(1, max(len(s) for s in splits))
    nl = max(len(lv) for lv in leaves)

    a = np.zeros((nf, nt * ni), dtype=np.float32)
    k1 = np.full((1, nt * ni), 1.0, dtype=np.float32)  # padding splits: any value, their C rows are zero
    c = np.zeros((nt, ni, nl), dtype=np.float32)
    d = np.full((nt, 1, nl), PAD_D, dtype=np.float32)
    for ti, t in enumerate(model["trees"]):
        f, th, left, right = t["f"], t["t"], t["l"], t["r"]
        col = {n: j for j, n in enumerate(splits[ti])}
        slot = {n: j for j, n in enumerate(leaves[ti])}
        for n, j in col.items():
            if not 0 <= f[n] < nf:
                raise ValueError(f"tree {ti} node {n}: feature {f[n]} out of range")
            a[f[n], ti * ni + j] = 1.0
            k = int(np.searchsorted(e[f[n]], float(th[n]), side="left"))
            assert e[f[n]][k] == float(th[n])
            k1[0, ti * ni + j] = k + 1.0
        # Walk every root-to-leaf path.
        stack = [(0, [])]
        seen = 0
        while stack:
            n, path = stack.pop()
            if f[n] < 0:
                lj = slot[n]
                d[ti, 0, lj] = float(sum(1 for _, went_left in path if went_left))
                for sn, went_left in path:
                    c[ti, col[sn], lj] = 1.0 if went_left else -1.0
                seen += 1
                continue
            stack.append((left[n], path + [(n, True)]))
            stack.append((right[n], path + [(n, False)]))
        if seen != len(leaves[ti]):
            raise ValueError(f"tree {ti}: {seen} leaves reachable, {len(leaves[ti])} in the file")
    if max(len(x) for x in e) + 1 > 2048:
        raise ValueError("a feature has more split points than fp16 counts exactly")

    # Every tensor is quantized (QDQ, scale 1): the HTP NPU runs quantized graphs only, and every number here is a
    # small whole number, so the quantization is exact as long as each one stays inside its type's range.
    max_bin = max(len(x) for x in e)  # bins and K1 are 0..max_bin (+1)
    if max_bin + 1 > 32767:
        raise ValueError("too many split points for 16-bit bins")
    if ni > 127 or nl > 255:
        raise ValueError("trees too big for the 8-bit tables")
    zp_u16 = 32768

    init = [
        numpy_helper.from_array(a.astype(np.uint8), "A_q"),
        numpy_helper.from_array(k1.astype(np.uint16), "K1_q"),
        numpy_helper.from_array((c + 1).astype(np.uint8), "C_q"),            # -1, 0, +1 -> 0, 1, 2 (zero point 1)
        numpy_helper.from_array((d + 128).astype(np.uint8), "D_q"),          # 0..ni and -100 -> 128.. and 28 (zero point 128)
        numpy_helper.from_array(np.array([1], dtype=np.uint8), "one_q"),
        numpy_helper.from_array(np.array(1.0, dtype=np.float32), "s1"),
        numpy_helper.from_array(np.array(0, dtype=np.uint8), "z8"),
        numpy_helper.from_array(np.array(1, dtype=np.uint8), "z8_1"),
        numpy_helper.from_array(np.array(128, dtype=np.uint8), "z8_128"),
        numpy_helper.from_array(np.array(0, dtype=np.uint16), "z16"),
        numpy_helper.from_array(np.array(zp_u16, dtype=np.uint16), "z16_mid"),
        numpy_helper.from_array(np.array([nt, 1, ni], dtype=np.int64), "shape3"),
        numpy_helper.from_array(np.array([1, nt * nl], dtype=np.int64), "shape_out"),
    ]
    nodes = []

    def dq(src, zp, out):
        nodes.append(helper.make_node("DequantizeLinear", [src, "s1", zp], [out]))
        return out

    def q(src, zp, out):
        nodes.append(helper.make_node("QuantizeLinear", [src, "s1", zp], [out]))
        return out

    def op(kind, ins, name, zp):
        """One quantized node unit: float op on dequantized inputs, its output quantized with [zp] and dequantized again."""
        nodes.append(helper.make_node(kind, ins, [name + "_f"]))
        return dq(q(name + "_f", zp, name + "_q"), zp, name)

    # In: the bins, as 16-bit (0..max_bin).
    b = dq(q("bins", "z16", "bins_q"), "z16", "bins_dq")
    a_f = dq("A_q", "z8", "A")
    k1_f = dq("K1_q", "z16", "K1")
    c_f = dq("C_q", "z8_1", "C")
    d_f = dq("D_q", "z8_128", "D")
    one_f = dq("one_q", "z8", "one")
    t = op("MatMul", [b, a_f], "t", "z16")                           # 0..max_bin
    y = op("Sub", [k1_f, t], "k_minus_t", "z16_mid")                  # -max_bin..max_bin+1
    pos = op("Relu", [y], "pos", "z16")                               # max(y, 0)
    y1 = op("Sub", [y, one_f], "k_minus_t_1", "z16_mid")
    pos1 = op("Relu", [y1], "pos1", "z16")                            # max(y - 1, 0)
    go = op("Sub", [pos, pos1], "go", "z8")                           # clip(y, 0, 1): 0 or 1
    go3 = op("Reshape", [go, "shape3"], "go3", "z8")
    p = op("MatMul", [go3, c_f], "p", "z8_128")                       # -ni..ni
    pd = op("Sub", [p, d_f], "p_minus_d", "z16_mid")                  # -ni-? .. ni+100
    dist = op("Abs", [pd], "dist", "z8")                              # 0..ni+100
    om = op("Sub", [one_f, dist], "one_minus", "z16_mid")             # 1-dist
    hit = op("Relu", [om], "hit", "z8")                               # 1 on the leaf, else 0
    op("Reshape", [hit, "shape_out"], "leaf", "z8")
    g = helper.make_graph(
        nodes, "scalper_trees_npu",
        [helper.make_tensor_value_info("bins", TensorProto.FLOAT, [1, nf])],
        [helper.make_tensor_value_info("leaf", TensorProto.FLOAT, [1, nt * nl])],
        init,
    )
    m = helper.make_model(g, producer_name="kalshi-odds-app export_npu.py", opset_imports=[helper.make_opsetid("", OPSET)])
    m.ir_version = 10
    for key, val in (
        ("source_sha256", source_sha256), ("features", ",".join(feats)),
        ("trees", str(nt)), ("splits", str(ni)), ("leaves", str(nl)),
    ):
        p = m.metadata_props.add()
        p.key, p.value = key, val
    onnx.checker.check_model(m)
    return m


def margin_from_leaf(model: dict, hit: np.ndarray) -> np.ndarray:
    """[N, T*L] leaf masks -> [N] trees' sums, adding the hit leaves in tree order (as the app does)."""
    _, leaves = layout(model)
    nl = max(len(lv) for lv in leaves)
    out = np.zeros(hit.shape[0])
    for r in range(hit.shape[0]):
        s = 0.0
        for ti, t in enumerate(model["trees"]):
            row = hit[r, ti * nl:(ti + 1) * nl]
            on = np.flatnonzero(row == 1.0)
            if len(on) != 1 or np.any((row != 0.0) & (row != 1.0)):
                raise AssertionError(f"row {r} tree {ti}: leaf mask {row}")
            s += float(t["v"][leaves[ti][on[0]]])
        out[r] = s
    return out


def main(src: str, dst: str) -> None:
    raw = open(src, "rb").read()
    m = build(json.loads(raw), hashlib.sha256(raw).hexdigest())
    onnx.save(m, dst)
    print(f"wrote {dst}: {len(json.loads(raw)['trees'])} trees, {sum(i.ByteSize() for i in m.graph.initializer)} bytes of tables")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
