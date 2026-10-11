"""History check for the paper scalper's fast strategies (fixed here before scoring, no tuning).
Each: buy the side at the ask now (taker fee), rest an offer X higher, stop S lower / time-out H sells at the bid (fee).
  DIP_HUNTER        side's price fell >= 2c in 10 s      X=2c S=2c H=30s
  MOMENTUM_SNIPER   side's price rose >= 2c in 10 s      X=2c S=2c H=30s
  EXTREME_REVERSION side's price fell >= 6c in 30 s      X=3c S=3c H=60s
Offer fills 'through' (back of queue) or 'at' (front of queue). Also the same signals with a resting entry
(join the bid 20 s, +1c offer, 4c stop, 30 s), which is how the ML scalper trades."""
import json, os, sys, numpy as np, datetime as dt, random
import scalp_b as B
DEC=B.DEC; NAN=np.nan
STR={"DIP_HUNTER":(0.02,0.02,30),"MOMENTUM_SNIPER":(0.02,0.02,30),"EXTREME_REVERSION":(0.03,0.03,60)}
def sig(g):
    mid=(g['bid']+g['ask'])/2
    with np.errstate(invalid='ignore'):
        d10=mid[DEC]-mid[DEC-10]; d30=mid[DEC]-mid[DEC-30]
        return {"DIP_HUNTER":d10<=-0.02+1e-9,"MOMENTUM_SNIPER":d10>=0.02-1e-9,"EXTREME_REVERSION":d30<=-0.06+1e-9}
def take_rest(g,res,X,S,H,through):
    P=g['ask'][DEC]; b0=g['bid'][DEC]
    live=(~np.isnan(P))&(~np.isnan(b0))&(b0<P)&(P>=0.10)&(P<=0.90)
    pnl=np.full(len(DEC),NAN); open_=live.copy(); a0=P+X
    for h in range(1,H+1):
        if not open_.any(): break
        b=B.at(g['bid'],DEC+h); hi=B.at(g['liftmax'],DEC+h)
        stop=open_&(~np.isnan(b))&(b<=P-S+1e-9)
        pnl[stop]=b[stop]-P[stop]-B.fee(P[stop])-B.fee(b[stop]); open_&=~stop
        t=open_&((hi>a0+1e-9) if through else (hi>=a0-1e-9))
        pnl[t]=X-B.fee(P[t]); open_&=~t
        if h==H:
            to=open_&(~np.isnan(b)); pnl[to]=b[to]-P[to]-B.fee(P[to])-B.fee(b[to]); open_&=~to
    pnl[open_]=res-P[open_]-B.fee(P[open_])
    return pnl
def one(a):
    path,result,day=a; z=np.load(path); g0=B.grid(z['T'],z['P'],z['C'],z['S']); out={}
    for g,res in ((g0,1.0 if result=='yes' else 0.0),(B.mirror(g0),0.0 if result=='yes' else 1.0)):
        sg=sig(g)
        for name,(X,S,H) in STR.items():
            for th in (True,False):
                p=take_rest(g,res,X,S,H,th); m=sg[name]&~np.isnan(p)
                if m.any():
                    v=p[m]; k=f"{name}|take|{'through' if th else 'at'}"; a_=out.setdefault(k,[0.0,0,0]); a_[0]+=float(v.sum()); a_[1]+=int(m.sum()); a_[2]+=int((v>0).sum())
        for th in (True,False):
            b0,fs,lv=B.entry_rest(g,th); p,_=B.sim_b2(g,b0,fs,0.01,0.04,30,th)
            p=np.where((fs>=0)&np.isnan(p),res-b0,p); p=np.where(lv,np.where(fs>=0,p,0.0),NAN)
            for name in STR:
                m=sg[name]&~np.isnan(p)
                if m.any():
                    v=p[m]; k=f"{name}|rest|{'through' if th else 'at'}"; a_=out.setdefault(k,[0.0,0,0]); a_[0]+=float(v.sum()); a_[1]+=int(m.sum()); a_[2]+=int((v>0).sum())
    return day,out
if __name__=="__main__":
    D=sys.argv[1]; mk=json.load(open(os.path.join(D,"markets.json")))
    jobs=[(os.path.join(D,"raw",m["ticker"]+".npz"),m["result"],dt.datetime.fromtimestamp(m["open"],dt.timezone.utc).strftime("%Y-%m-%d")) for m in mk if os.path.exists(os.path.join(D,"raw",m["ticker"]+".npz"))]
    from multiprocessing import Pool
    agg={}
    with Pool(2) as pool:
        for day,out in pool.imap_unordered(one,jobs,chunksize=16):
            for k,v in out.items():
                a=agg.setdefault(k,{}).setdefault(day,[0.0,0,0]); a[0]+=v[0]; a[1]+=v[1]; a[2]+=v[2]
    days=sorted({d for v in agg.values() for d in v}); nwin=len(jobs)
    print(f"{nwin} windows, {len(days)} days {days[0]}..{days[-1]}. Cents per scalp (per order posted for 'rest'), 95% day-resampled, winners, signals per window")
    for k in sorted(agg):
        v=agg[k]; s=sum(x[0] for x in v.values()); n=sum(x[1] for x in v.values()); w=sum(x[2] for x in v.values())
        rnd=random.Random(2); ds=list(v); bs=[]
        for _ in range(3000):
            a=b=0.0
            for d in rnd.choices(ds,k=len(ds)): a+=v[d][0]; b+=v[d][1]
            bs.append(100*a/b)
        bs.sort()
        print(f"  {k:34s} {100*s/n:+6.2f}c [{bs[75]:+.2f},{bs[2924]:+.2f}]  winners {100*w/n:4.1f}%  n={n:7d}  {n/nwin:5.1f}/window  days+ {sum(1 for d in ds if v[d][0]>0)}/{len(ds)}")
    json.dump(agg,open('strat_bt.json','w'))
