#!/usr/bin/env python3
"""Writes app/src/test/resources/updown_parity.json: real recorded moments, their raw inputs as the app holds them
(spot one slot a second, the window's mid per second, the top of book) and what updown_features.py and the model make
of them. UpDownFeaturesTest feeds the same inputs to the Kotlin port and must get the same numbers.

Usage: make_updown_fixture.py <recordings dir> updown_model.json out.json
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_updown as bu  # noqa: E402
import updown_features as uf  # noqa: E402

SPOT_LEN = 1201


def design(row, names):
    i = {n: k for k, n in enumerate(uf.NAMES)}
    t = row[i["tleft"]]
    st = math.sqrt(t)
    lm, el = row[i["logit_mid"]], row[i["edge_logit"]]
    vals = {"logit_mid": lm, "logit_mid*sqrt_tleft": lm * st, "logit_mid*tleft": lm * t, "edge_logit": el,
            "edge_logit*sqrt_tleft": el * st, "imb": row[i["imb"]], "r30": row[i["r30"]], "r60": row[i["r60"]],
            "dmid30": row[i["dmid30"]], "dmid60": row[i["dmid60"]], "spread": row[i["spread"]]}
    return [vals[n] for n in names]


def nn(v):
    return None if (v is None or (isinstance(v, float) and math.isnan(v))) else round(float(v), 8)


def main():
    spot, book, meta, settle = bu.load(Path(sys.argv[1]))
    model = json.load(open(sys.argv[2]))
    secs = np.array(sorted(spot), dtype=np.int64)
    vals = np.array([spot[s] for s in secs])
    cases = []
    tickers = [t for t in sorted(settle) if t in meta and t in book][::97][:8]
    for t in tickers:
        strike, close_ms = meta[t]
        close_s = close_ms // 1000
        open_s = close_s - 900
        lo = open_s - uf.STD_WINDOW_S - 1 - 300
        i0, i1 = np.searchsorted(secs, lo), np.searchsorted(secs, close_s + 1)
        hist = np.full(close_s - lo + 1, np.nan)
        hist[secs[i0:i1] - lo] = vals[i0:i1]
        bk = book[t]
        bsecs = sorted(bk)
        mid_hist = np.full(900, np.nan)
        cur, cur_s, bi = None, None, 0
        tops = {}
        for s in range(900):
            now_s = open_s + s
            while bi < len(bsecs) and bsecs[bi] <= now_s:
                cur, cur_s = bk[bsecs[bi]], bsecs[bi]
                bi += 1
            top = cur if (cur is not None and now_s - cur_s <= bu.BOOK_CARRY_S) else None
            tops[s] = top
            if top is not None and not math.isnan(top[0]) and not math.isnan(top[2]):
                mid_hist[s] = (top[0] + top[2]) / 2.0
        for s in (35, 245, 610, 845, 875):
            top = tops[s]
            if top is None:
                continue
            now_i = open_s + s - lo
            sp = hist[: now_i + 1][-SPOT_LEN:]
            row = uf.features(sp, strike, float(900 - s), s, top[0], top[1], top[2], top[3], mid_hist[: s + 1])
            if row is None:
                continue
            x = design(row, model["features"])
            z = sum(w * v for w, v in zip(model["weights"], x)) + model["bias"]
            cases.append(dict(ticker=t, sec_in=s, tte_s=float(900 - s), strike=strike,
                              yes_bid=nn(top[0]), yes_bid_qty=nn(top[1]), yes_ask=nn(top[2]), yes_ask_qty=nn(top[3]),
                              spot=[nn(v) for v in sp], mid=[nn(v) for v in mid_hist[: s + 1]],
                              features=[round(v, 10) for v in row], x=[round(v, 10) for v in x],
                              up=round(1.0 / (1.0 + math.exp(-z)), 10)))
    json.dump(dict(names=uf.NAMES, model_features=model["features"], cases=cases), open(sys.argv[3], "w"))
    print(len(cases), "cases")


if __name__ == "__main__":
    main()
