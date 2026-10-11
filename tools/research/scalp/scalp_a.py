"""Part A of SCALP_DESIGN.md: minute-level scalps on KXBTC15M (buy at the ask, sell at the bid k minutes later)."""
import json, math, random, collections, datetime as dt, sys
IS_END="2026-09-23"   # days before this are in sample
mk={m['ticker']:m for m in json.load(open('mk_KXBTC15M.json'))}
cb=dict((int(a),b) for a,b in json.load(open('cb_btc.json')))
def spot(t): return cb.get(int(t)-60)          # close of the bar that ended at t
def fee(p): return 0.07*p*(1-p)
def phi(x): return 0.5*(1+math.erf(x/math.sqrt(2)))
def sigma1m(now):
    cl=[spot(now-60*i) for i in range(30,-1,-1)]; cl=[c for c in cl if c]
    if len(cl)<11: return None
    r=[math.log(b/a) for a,b in zip(cl,cl[1:])]; mu=sum(r)/len(r)
    v=sum((x-mu)**2 for x in r)/(len(r)-1)
    return math.sqrt(v) if v>0 else None
def fair(S,K,sig,tte):
    sd=math.sqrt(sig*sig*((max(tte,60)-60)+20)/60+(0.5e-4)**2)
    return phi(math.log(S/K)/sd)
SIGS=["ALWAYS_FAV","SPOT_MOM1","SPOT_MOM3","KAL_MOM1","KAL_FADE1","LAG3","LAG5","JUMP_FOLLOW","JUMP_FADE"]
HOLDS=[1,2,3]
trades=collections.defaultdict(list)    # (sig,k) -> [(day, pnl_taker, pnl_ceiling, entry_ask)]
nwin=0; dropped=0; seen=set()
for l in open('cd_KXBTC15M.jsonl'):
    o=json.loads(l); t=o['t']
    if t in seen or t not in mk: continue
    seen.add(t); m=mk[t]
    if m['strike'] is None: continue
    K=float(m['strike']); op=int(m['open']); cl=int(m['close'])
    day=dt.datetime.fromtimestamp(op,dt.timezone.utc).strftime('%Y-%m-%d')
    q={int(e):(yb,ya) for e,yb,ya,v in o['c']}
    nwin+=1
    for mnt in range(2,12):
        now=op+60*mnt
        if now not in q or (now-60) not in q: continue
        yb,ya=q[now]; pb,pa=q[now-60]
        if None in (yb,ya,pb,pa) or not (0<yb<=ya<1): continue
        mid=(yb+ya)/2; pmid=(pb+pa)/2
        S=spot(now); S1=spot(now-60); S3=spot(now-180); sig=sigma1m(now)
        side={}
        side["ALWAYS_FAV"]='UP' if mid>=0.5 else 'DN'
        if S and S1 and S!=S1: side["SPOT_MOM1"]='UP' if S>S1 else 'DN'
        if S and S3 and S!=S3: side["SPOT_MOM3"]='UP' if S>S3 else 'DN'
        if abs(mid-pmid)>1e-9: side["KAL_MOM1"]='UP' if mid>pmid else 'DN'
        if abs(mid-pmid)>=0.05-1e-9: side["KAL_FADE1"]='DN' if mid>pmid else 'UP'
        if S and sig:
            f=fair(S,K,sig,cl-now)
            for g,name in ((0.03,"LAG3"),(0.05,"LAG5")):
                if abs(f-mid)>=g: side[name]='UP' if f>mid else 'DN'
            if S1 and abs(math.log(S/S1))>=1.5*sig:
                up=S>S1; side["JUMP_FOLLOW"]='UP' if up else 'DN'; side["JUMP_FADE"]='DN' if up else 'UP'
        for k in HOLDS:
            ex=now+60*k
            if ex not in q or q[ex][0] is None or q[ex][1] is None: dropped+=1; continue
            eb,ea=q[ex]
            for name,sd in side.items():
                ask=ya if sd=='UP' else 1-yb; bid_in=yb if sd=='UP' else 1-ya
                if not (0.05<=ask<=0.95): continue
                xbid=eb if sd=='UP' else 1-ea; xask=ea if sd=='UP' else 1-eb
                xbid=max(0.0,min(1.0,xbid))
                pnl=xbid-ask-fee(ask)-fee(xbid)
                ceil=xask-bid_in
                trades[(name,k)].append((day,pnl,ceil,ask))
print(f"windows {nwin}; exits with no quote (dropped) {dropped}")
def stat(xs,idx=1,B=3000):
    if not xs: return None
    by=collections.defaultdict(list)
    for x in xs: by[x[0]].append(x[idx])
    days=list(by); random.seed(11); v=[]
    for _ in range(B):
        s=[y for d in random.choices(days,k=len(days)) for y in by[d]]; v.append(100*sum(s)/len(s))
    v.sort(); allv=[x[idx] for x in xs]
    return 100*sum(allv)/len(allv), v[int(.025*B)], v[int(.975*B)], len(allv), 100*sum(1 for a in allv if a>0)/len(allv)
print(f"\n{'signal':12s} hold | IN SAMPLE: n, cents/trade [95%], winners% | OUT OF SAMPLE: n, cents/trade [95%], winners% | no-fee both-resting ceiling (OOS)")
best=None
for name in SIGS:
    for k in HOLDS:
        xs=trades[(name,k)]; a=[x for x in xs if x[0]<IS_END]; b=[x for x in xs if x[0]>=IS_END]
        sa=stat(a); sb=stat(b); sc=stat(b,2)
        if not sa or not sb: continue
        print(f"{name:12s} {k}m   | {sa[3]:6d} {sa[0]:+6.2f} [{sa[1]:+.2f},{sa[2]:+.2f}] {sa[4]:4.1f}% | {sb[3]:6d} {sb[0]:+6.2f} [{sb[1]:+.2f},{sb[2]:+.2f}] {sb[4]:4.1f}% | {sc[0]:+6.2f} [{sc[1]:+.2f},{sc[2]:+.2f}]")
        if sa[3]>=300 and (best is None or sa[0]>best[0]): best=(sa[0],name,k,sb)
print(f"\nPre-registered pick: best in sample = {best[1]} hold {best[2]}m ({best[0]:+.2f}c in sample). Out of sample: {best[3][0]:+.2f}c per trade, 95% [{best[3][1]:+.2f},{best[3][2]:+.2f}], n={best[3][3]}")
# what the round trip costs, and how far prices move
allfav=trades[("ALWAYS_FAV",1)]
import statistics as st
print("\nRound-trip cost for a taker (spread + two fees), by entry price:")
for lo,hi in ((.05,.2),(.2,.4),(.4,.6),(.6,.8),(.8,.95)):
    p=(lo+hi)/2; print(f"  entry {int(lo*100)}-{int(hi*100)}c: about {100*(0.01+2*fee(p)):.1f}c per contract")
json.dump({f"{k[0]}|{k[1]}":v for k,v in trades.items()}, open('scalp_a_trades.json','w'))
