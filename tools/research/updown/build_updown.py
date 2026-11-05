#!/usr/bin/env python3
"""Rows for the up/down model: one per BTC 15-minute window every STEP seconds.

Reads the second-by-second recordings (tools/research/recordings.py format: spot_, book_, settle_ files per UTC
day, e.g. the "scalper-data" release) and writes, for every settled KXBTC15M window, the features the app can
compute live at that second plus the result (1 = YES / UP settled).

Every feature uses only data at or before its second, the same way the app sees it:
  * spot   = Coinbase BTC-USD, the last print of each second (CfIndexBook history), fresh within 5 s;
  * std1m  = RMS of consecutive 1-second log returns over the last 900 s, x sqrt(60) (CfIndexBook.std1m, >= 120 returns);
  * fair   = ScalpFair.fairYes on that spot (settlement-average digital, Coinbase noise 0.5 bp);
  * book   = the last top-of-book row of each second, carried at most 10 s.
The feature math lives in updown_features.py so the Kotlin port (UpDownFeatures.kt) can be checked against it.

Usage: build_updown.py <recordings dir> <out.npz> [step_seconds]
"""
from __future__ import annotations

import csv
import gzip
import io
import math
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
from recordings import read_gzip_tolerant  # noqa: E402
import updown_features as uf  # noqa: E402

SERIES = "KXBTC15M"
BOOK_CARRY_S = 10


def _rows(path: Path):
    if path.suffix == ".gz":
        text = read_gzip_tolerant(path.read_bytes()).decode("utf-8", "replace")
    else:
        text = path.read_text()
    return csv.DictReader(io.StringIO(text))


def load(dirpath: Path):
    spot: dict[int, float] = {}          # epoch second -> last BTC print
    book: dict[str, dict[int, tuple]] = defaultdict(dict)  # ticker -> second -> (yb, ybq, ya, yaq)
    meta: dict[str, tuple[float, int]] = {}
    settle: dict[str, int] = {}
    for p in sorted(dirpath.iterdir()):
        m = re.match(r"^(spot|book|settle)_(\d{4}-\d{2}-\d{2})\.csv(\.gz)?$", p.name)
        if not m:
            continue
        kind = m.group(1)
        if kind == "spot":
            for r in _rows(p):
                if r["product"] != "BTC-USD" or not r["price"]:
                    continue
                spot[int(r["ts_ms"]) // 1000] = float(r["price"])  # rows are time-ordered: last one wins
        elif kind == "book":
            for r in _rows(p):
                t = r["ticker"]
                if not t.startswith(SERIES):
                    continue
                def f(k):
                    v = r[k]
                    return float(v) if v not in ("", None) else math.nan
                book[t][int(r["ts_ms"]) // 1000] = (f("yes_bid"), f("yes_bid_qty"), f("yes_ask"), f("yes_ask_qty"))
                if r["strike"] and r["close_ms"]:
                    meta[t] = (float(r["strike"]), int(r["close_ms"]))
        else:
            for r in _rows(p):
                t = r["ticker"]
                if not t.startswith(SERIES) or r["result"] not in ("yes", "no"):
                    continue
                settle[t] = 1 if r["result"] == "yes" else 0
                if r["strike"] and r["close_ms"]:
                    meta.setdefault(t, (float(r["strike"]), int(r["close_ms"])))
    return spot, book, meta, settle


def build(dirpath: Path, step: int = 5):
    spot, book, meta, settle = load(dirpath)
    secs = np.array(sorted(spot), dtype=np.int64)
    vals = np.array([spot[s] for s in secs], dtype=np.float64)
    X, y, win, sec_in, day, mids, asks, nasks, fairs = [], [], [], [], [], [], [], [], []
    for t in sorted(settle):
        if t not in meta or t not in book:
            continue
        strike, close_ms = meta[t]
        close_s = close_ms // 1000
        open_s = close_s - 900
        # spot history for this window: 900 s before the open to the close, one slot a second (NaN = no print)
        lo = open_s - uf.STD_WINDOW_S - 1
        i0, i1 = np.searchsorted(secs, lo), np.searchsorted(secs, close_s + 1)
        hist = np.full(close_s - lo + 1, np.nan)
        hist[secs[i0:i1] - lo] = vals[i0:i1]
        bk = book[t]
        bsecs = sorted(bk)
        if not bsecs:
            continue
        bi = 0
        cur, cur_s = None, None
        mid_hist = np.full(901, np.nan)  # mid at the end of each second of the window
        for s in range(0, 900):
            now_s = open_s + s
            while bi < len(bsecs) and bsecs[bi] <= now_s:
                cur, cur_s = bk[bsecs[bi]], bsecs[bi]
                bi += 1
            top = cur if (cur is not None and now_s - cur_s <= BOOK_CARRY_S) else None
            if top is not None and not math.isnan(top[0]) and not math.isnan(top[2]):
                mid_hist[s] = (top[0] + top[2]) / 2.0
            if s < uf.ENTRY_FROM_S or s > uf.ENTRY_UNTIL_S or (s - uf.ENTRY_FROM_S) % step:
                continue
            if top is None:
                continue
            sp = hist[: now_s - lo + 1]  # seconds lo..now
            feats = uf.features(
                spot_hist=sp, strike=strike, tte_s=float(900 - s), sec_in=s,
                yes_bid=top[0], yes_bid_qty=top[1], yes_ask=top[2], yes_ask_qty=top[3],
                mid_hist=mid_hist[: s + 1],
            )
            if feats is None:
                continue
            X.append(feats)
            y.append(settle[t])
            win.append(t)
            sec_in.append(s)
            day.append(f"{(open_s // 86400)}")
            mids.append((top[0] + top[2]) / 2.0)
            asks.append(top[2])
            nasks.append(1.0 - top[0])
            fairs.append(feats[uf.NAMES.index("fair")])
    return dict(
        X=np.array(X, dtype=np.float64), y=np.array(y, dtype=np.int8), window=np.array(win), sec=np.array(sec_in),
        day=np.array(day, dtype=np.int64), mid=np.array(mids), yes_ask=np.array(asks), no_ask=np.array(nasks),
        fair=np.array(fairs), names=np.array(uf.NAMES),
    )


if __name__ == "__main__":
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    step = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    d = build(src, step)
    np.savez_compressed(out, **d)
    n_w = len(set(d["window"].tolist()))
    print(f"{len(d['y'])} rows, {n_w} windows, {len(set(d['day'].tolist()))} days, UP share {d['y'].mean():.3f}")
