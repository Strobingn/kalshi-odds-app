"""Load FallbackWeights from production FallbackWeights.kt (do not edit that file)."""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FALLBACK_KT = REPO_ROOT / "app/src/main/java/com/dirk/kalshiodds/prediction/FallbackWeights.kt"


def _floats(block: str) -> list[float]:
    raw = re.findall(r"[-+]?(?:\d+\.\d+|\d+)(?:[eE][-+]?\d+)?f?", block)
    return [float(x.rstrip("fF")) for x in raw]


def load_fallback_weights(path: Path | None = None) -> dict:
    text = (path or FALLBACK_KT).read_text()
    mean = _floats(re.search(r"val MEAN = floatArrayOf\((.*?)\)", text, re.S).group(1))
    std = _floats(re.search(r"val STD = floatArrayOf\((.*?)\)", text, re.S).group(1))
    w1_block = re.search(r"private val w1 = arrayOf\((.*?)\)\n    private val b1", text, re.S).group(1)
    w2_block = re.search(r"private val w2 = arrayOf\((.*?)\)\n    private val b2", text, re.S).group(1)
    w3_block = re.search(r"private val w3 = arrayOf\((.*?)\)\n    private val b3", text, re.S).group(1)
    b1 = _floats(re.search(r"private val b1 = floatArrayOf\((.*?)\)", text, re.S).group(1))
    b2 = _floats(re.search(r"private val b2 = floatArrayOf\((.*?)\)", text, re.S).group(1))
    b3 = _floats(re.search(r"private val b3 = floatArrayOf\((.*?)\)", text, re.S).group(1))
    w1 = [_floats(m) for m in re.findall(r"floatArrayOf\((.*?)\)", w1_block, re.S)]
    w2 = [_floats(m) for m in re.findall(r"floatArrayOf\((.*?)\)", w2_block, re.S)]
    w3 = [_floats(m) for m in re.findall(r"floatArrayOf\((.*?)\)", w3_block, re.S)]
    assert len(mean) == 8 and len(std) == 8
    assert len(w1) == 8 and all(len(r) == 32 for r in w1)
    assert len(w2) == 32 and all(len(r) == 16 for r in w2)
    assert len(w3) == 16 and all(len(r) == 2 for r in w3)
    return {"mean": mean, "std": std, "w1": w1, "b1": b1, "w2": w2, "b2": b2, "w3": w3, "b3": b3}


def dense_relu(x: list[float], w: list[list[float]], b: list[float]) -> list[float]:
    out = []
    for j in range(len(b)):
        s = b[j]
        for i, xi in enumerate(x):
            s += xi * w[i][j]
        out.append(s if s > 0.0 else 0.0)
    return out


def dense_linear(x: list[float], w: list[list[float]], b: list[float]) -> list[float]:
    out = []
    for j in range(len(b)):
        s = b[j]
        for i, xi in enumerate(x):
            s += xi * w[i][j]
        out.append(s)
    return out


def softmax(logits: list[float]) -> list[float]:
    m = max(logits)
    exps = [pow(2.718281828459045, z - m) for z in logits]
    # match Kotlin Math.exp more closely
    import math

    exps = [math.exp(z - m) for z in logits]
    s = sum(exps)
    return [e / s for e in exps]


def forward(scaled: list[float], w: dict | None = None) -> list[float]:
    w = w or load_fallback_weights()
    h1 = dense_relu(scaled, w["w1"], w["b1"])
    h2 = dense_relu(h1, w["w2"], w["b2"])
    logits = dense_linear(h2, w["w3"], w["b3"])
    return softmax(logits)
