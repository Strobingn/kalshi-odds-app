import json, sys, random, collections
d=json.load(open(sys.argv[1])); agg=d["agg"]
days=sorted({day for v in agg.values() for day in v})
OOS=set(days[-7:]); IS=set(days[:-7])
print(f"windows {d['n']}, days {len(days)} ({days[0]}..{days[-1]}); in sample {min(IS)}..{max(IS)} ({len(IS)} days), out of sample {min(OOS)}..{max(OOS)} ({len(OOS)} days)")
def st(cell,which,B=4000):
    v=agg.get(cell,{}); ds=[x for x in v if x in which]
    s=sum(v[x][0] for x in ds); n=sum(v[x][1] for x in ds); w=sum(v[x][2] for x in ds)
    if n==0: return None
    random.seed(21); bs=[]
    for _ in range(B):
        a=b=0.0
        for x in random.choices(ds,k=len(ds)): a+=v[x][0]; b+=v[x][1]
        bs.append(100*a/b if b else 0.0)
    bs.sort()
    return dict(mean=100*s/n,n=n,win=100*w/n,lo95=bs[int(.025*B)],hi95=bs[int(.975*B)],lo99=bs[int(.005*B)],hi99=bs[int(.995*B)],posdays=sum(1 for x in ds if v[x][0]>0),ndays=len(ds))
fam=collections.defaultdict(list)
for cell in agg:
    f=cell.split("|")[0]
    if f in("B1","B2","B3"): fam[f].append(cell)
names={"B1":"B1 buy at the ask, sell at the bid (target / stop / time)","B2":"B2 resting bid in, resting offer out (stop / time-out sells at the bid)","B3":"B3 resting bid in, hold to settlement (reference)"}
for f in ("B1","B2","B3"):
    cells=sorted(fam[f]); print(f"\n=== {names[f]} — {len(cells)} cells ===")
    rows=[(c,st(c,IS),st(c,OOS)) for c in cells]
    rows=[r for r in rows if r[1] and r[2]]
    elig=[r for r in rows if r[1]['n']>=500 and (f=="B1" or r[0].endswith("through"))]
    pick=max(elig,key=lambda r:r[1]['mean'])
    for label,sel in (("through-fill / taker cells (count)",[r for r in rows if f=="B1" or r[0].endswith("through")]),("front-of-queue 'at' cells (context)",[r for r in rows if r[0].endswith("|at")])):
        if not sel: continue
        o=[r[2]['mean'] for r in sel]; i=[r[1]['mean'] for r in sel]
        print(f" {label}: {len(sel)} cells; in-sample range {min(i):+.2f}..{max(i):+.2f}c; out-of-sample range {min(o):+.2f}..{max(o):+.2f}c; cells positive out of sample: {sum(1 for x in o if x>0)}; with 95% interval above zero: {sum(1 for r in sel if r[2]['lo95']>0)}")
    c,a,b=pick
    print(f" PICK (best in sample, >=500 scalps): {c}")
    print(f"   in sample : {a['mean']:+.2f}c per scalp, n={a['n']}, winners {a['win']:.1f}%")
    print(f"   out of sample: {b['mean']:+.2f}c per scalp, 95% [{b['lo95']:+.2f},{b['hi95']:+.2f}], 99% [{b['lo99']:+.2f},{b['hi99']:+.2f}], n={b['n']}, winners {b['win']:.1f}%, positive days {b['posdays']}/{b['ndays']}")
    print(" all cells, out of sample (cents per scalp [95%], n, winners%):")
    for c,a,b in sorted(rows,key=lambda r:-r[2]['mean']):
        print(f"   {c:36s} IS {a['mean']:+6.2f}  OOS {b['mean']:+6.2f} [{b['lo95']:+.2f},{b['hi95']:+.2f}] n={b['n']:7d} win {b['win']:4.1f}%")
print("\nFill rates of a resting bid joined at the best bid, 20 s (all days):")
for s in ("NONE","MOM10","FADE10","FLOW","FLOWFADE"):
    for fm in ("through","at"):
        v=agg.get(f"POSTED|{s}|{fm}",{}); p=sum(x[1] for x in v.values()); f=sum(x[2] for x in v.values())
        if p: print(f"   {s:9s} {fm:8s} posted {p:8d} filled {100*f/p:5.1f}%")
