"""Part K (tools/research/scalp/DESIGN.md): the clear-lead rule from Kalshi prices only.

Run: python3 clear_lead_kalshi.py <mk_KXBTC15M.json> <cd_KXBTC15M.jsonl>
(both from tools/research/scalp/pull_candles.py: Kalshi markets and 1-minute candles, nothing else).
"""
import json, sys, datetime as dt, random
BANDS = [(0.60, 0.70), (0.70, 0.80), (0.80, 0.90)]


def fee(p): return 0.07 * p * (1 - p)


def bets(mk, cd, band):
    lo, hi = band; out = []
    for m in mk:
        c = cd.get(m["ticker"])
        if not c or m.get("result") not in ("yes", "no"): continue
        for end, yb, ya, _v in c:
            minute = (end - m["open"]) / 60.0
            if minute < 3 or minute > 10 or yb is None or ya is None: continue
            up = (yb + ya) / 2 > 0.5
            ask = ya if up else round(1 - yb, 4)
            if lo - 1e-9 <= ask < hi - 1e-9:
                win = 1.0 if (m["result"] == "yes") == up else 0.0
                out.append(dict(day=dt.datetime.fromtimestamp(m["open"], dt.timezone.utc).strftime("%Y-%m-%d"), ask=ask, win=win, net=win - ask - fee(ask)))
                break
    return out


def summary(b, iters=4000):
    if not b: return "no bets"
    by = {}
    for x in b: s = by.setdefault(x["day"], [0.0, 0]); s[0] += x["net"]; s[1] += 1
    ds = list(by); r = random.Random(3); ms = []
    for _ in range(iters):
        t = n = 0
        for d in r.choices(ds, k=len(ds)): t += by[d][0]; n += by[d][1]
        ms.append(t / n)
    ms.sort(); n = len(b)
    return (f"n={n:5d} wins {100 * sum(x['win'] for x in b) / n:5.1f}% paid {100 * sum(x['ask'] for x in b) / n:5.1f}c "
            f"net {100 * sum(x['net'] for x in b) / n:+.2f}c [{100 * ms[int(.025 * iters)]:+.2f}, {100 * ms[int(.975 * iters)]:+.2f}] days+ {sum(1 for d in ds if by[d][0] > 0)}/{len(ds)}")


if __name__ == "__main__":
    mk = json.load(open(sys.argv[1])); cd = {}
    for line in open(sys.argv[2]):
        r = json.loads(line); cd[r["t"]] = r["c"]
    days = sorted({dt.datetime.fromtimestamp(m["open"], dt.timezone.utc).strftime("%Y-%m-%d") for m in mk})
    first = set(days[: len(days) // 2])
    print(f"{len(mk)} windows, {len(days)} days; first half {days[0]}..{max(first)}, second half {min(set(days) - first)}..{days[-1]}")
    best = None
    for band in BANDS:
        b = bets(mk, cd, band); a = [x for x in b if x["day"] in first]; z = [x for x in b if x["day"] not in first]
        print(f"favourite ask {band[0]*100:.0f}-{band[1]*100:.0f}c  first half: {summary(a)}")
        print(f"{'':22s}second half: {summary(z)}")
        m = sum(x["net"] for x in a) / max(len(a), 1)
        if best is None or m > best[0]: best = (m, band)
    print(f"picked on the first half: {best[1][0]*100:.0f}-{best[1][1]*100:.0f}c")
