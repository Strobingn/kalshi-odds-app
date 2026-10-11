"""Per-window files for Part J (DESIGN.md): the complete trade tape plus the recorder's 15 book levels a side.

Per window (ticker) one npz in <out>/win/:
  T,P,C,S        the tape (pull_tape.py)
  YP,YQ / NP,NQ  resting YES bids / NO bids at the END of each of the 900 seconds: price and size of up to
                 15 levels, best first, NaN padded (last snapshot received in that second)
  YN,NN          how many levels the snapshot listed on each side (-1 = no snapshot in that second)
and <out>/windows.json.

Run: python3 depth_build.py <recordings dir> <out dir> <tape dir> [<tape dir> ...]
"""
import io, json, os, sys
import numpy as np, pandas as pd
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.dirname(HERE))
from recordings import read_gzip_tolerant
L = 15


def levels(s):
    """'0.46:3|0.45:10' -> ([0.46, 0.45], [3, 10])"""
    ps, qs = [], []
    if isinstance(s, str) and s:
        for part in s.split("|")[:L]:
            a, _, b = part.partition(":")
            try: ps.append(float(a)); qs.append(float(b))
            except ValueError: pass
    return ps, qs


if __name__ == "__main__":
    REC, OUT = sys.argv[1], sys.argv[2]; TAPES = sys.argv[3:]
    os.makedirs(os.path.join(OUT, "win"), exist_ok=True)
    tape = {}
    for d in TAPES:
        for m in json.load(open(os.path.join(d, "markets.json"))):
            p = os.path.join(d, "raw", m["ticker"] + ".npz")
            if os.path.exists(p): tape[m["ticker"]] = (p, m)
    frames = []
    for f in sorted(os.listdir(REC)):
        if f.startswith("depth_") and f.endswith(".csv.gz"):
            b = read_gzip_tolerant(open(os.path.join(REC, f), "rb").read())
            df = pd.read_csv(io.BytesIO(b), dtype=str, keep_default_na=False, on_bad_lines="skip")
            frames.append(df[df.ts_ms != "ts_ms"])
    depth = pd.concat(frames); depth["ts"] = pd.to_numeric(depth.ts_ms, errors="coerce"); depth = depth.dropna(subset=["ts"]).sort_values("ts", kind="stable")
    wins = []
    for tk, g in depth.groupby("ticker", sort=False):
        if tk not in tape: continue
        path, m = tape[tk]; close = int(m["close"]); op = close - 900
        YP = np.full((900, L), np.nan, np.float32); YQ = YP.copy(); NP = YP.copy(); NQ = YP.copy()
        YN = np.full(900, -1, np.int16); NN = YN.copy()
        sec = ((g.ts.values.astype(np.int64) - op * 1000) // 1000).astype(np.int64)
        for s, ys, ns in zip(sec, g.yes_levels.values, g.no_levels.values):
            if s < 0 or s >= 900: continue
            yp, yq = levels(ys); np_, nq = levels(ns)
            YP[s] = np.nan; YQ[s] = np.nan; NP[s] = np.nan; NQ[s] = np.nan      # the last snapshot of the second wins
            YP[s, :len(yp)] = yp; YQ[s, :len(yq)] = yq; NP[s, :len(np_)] = np_; NQ[s, :len(nq)] = nq
            YN[s] = len(yp); NN[s] = len(np_)
        z = np.load(path)
        np.savez_compressed(os.path.join(OUT, "win", tk + ".npz"), T=z["T"], P=z["P"], C=z["C"], S=z["S"], YP=YP, YQ=YQ, NP=NP, NQ=NQ, YN=YN, NN=NN)
        wins.append(dict(ticker=tk, open=op, close=close, result=m["result"], strike=m.get("strike"), depth_secs=int((YN >= 0).sum())))
    wins.sort(key=lambda w: w["open"]); json.dump(wins, open(os.path.join(OUT, "windows.json"), "w"))
    print(len(wins), "windows with tape and depth;", sum(1 for w in wins if w["depth_secs"] >= 600), "with at least 600 s of depth", flush=True)
