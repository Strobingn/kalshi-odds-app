"""Fixed exits for the ML scalper's picks: stop size x time-out, on the VALID and TEST days of splits.py.

Cents per order posted (unfilled = 0), front of the queue, the shipped entry model's picks; day-bootstrap 95 %
intervals, the difference against the shipped 4c / 120 s exit, and the tail (worst order, share losing > 10c).

  python3 -m exit_sweep <tape dir> <shipped scalper_model.json>"""
import datetime as dt
import json
import os
import sys

import numpy as np

import exit_ml as E
import scalp_b as B
from gate_scalper import day_ci, predict
from splits import split

GRID = [(0.04, 120), (0.06, 120), (0.10, 120), (0.20, 120), (0.99, 120), (0.10, 300), (0.99, 300), (0.99, 600)]


def main(tape, shipped):
    mod = json.load(open(shipped))
    mk = [m for m in json.load(open(os.path.join(tape, "markets.json"))) if os.path.exists(os.path.join(tape, "raw", m["ticker"] + ".npz"))]
    mk.sort(key=lambda m: m["open"])
    day = lambda m: dt.datetime.fromtimestamp(m["open"], dt.timezone.utc).strftime("%Y-%m-%d")
    _, VA, TE = split(sorted({day(m) for m in mk}))
    out = {k: {"valid": [], "test": []} for k in GRID}
    days = {"valid": [], "test": []}
    for m in mk:
        d = day(m)
        part = "valid" if d in VA else ("test" if d in TE else None)
        if part is None:
            continue
        z = np.load(os.path.join(tape, "raw", m["ticker"] + ".npz"))
        g0 = B.grid(z["T"], z["P"], z["C"], z["S"])
        my, mn, cnt, hy, hn = E.extras(z["T"], z["P"], z["C"], z["S"]); sy = E.since(hy); sn = E.since(hn)
        r = 1.0 if m["result"] == "yes" else 0.0
        for g, ex, res in ((g0, (my, mn, cnt, sn, sy), r), (B.mirror(g0), (mn, my, cnt, sy, sn), 1 - r)):
            F = E.compact_all(g, ex); b0, fs, live = B.entry_rest(g, False)
            pick = np.zeros(len(B.DEC), bool)
            pick[live] = predict(mod, F[B.DEC[live]]) >= mod["theta_cents"]
            days[part] += [d] * int(pick.sum())
            for S, H in GRID:
                E.STOP, E.HOLD = S, H
                p, _ = E.fixed_exit(g, b0, fs)
                p = np.where((fs >= 0) & np.isnan(p), res - b0, p)
                p = np.where(fs >= 0, p, 0.0)
                out[(S, H)][part] += list(p[pick] * 100)
    for part in ("valid", "test"):
        base = np.array(out[GRID[0]][part]); dd = days[part]
        print(part.upper(), "orders", len(base))
        for k in GRID:
            v = np.array(out[k][part]); a = day_ci(v, dd); df = day_ci(v - base, dd)
            print(f"  stop {k[0] * 100:3.0f}c hold {k[1]:3d}s: {a['mean']:+.3f}c [{a['lo']:+.2f},{a['hi']:+.2f}] up {a['up_days']}/{a['days']}"
                  f" | vs 4c/120s {df['mean']:+.3f} [{df['lo']:+.3f},{df['hi']:+.3f}] better days {df['up_days']}/{df['days']}"
                  f" | worst {v.min():+.0f}c, losses > 10c {np.mean(v < -10) * 100:.2f}%")


if __name__ == "__main__":
    main(*sys.argv[1:3])
