"""Part B of SCALP_DESIGN.md: second-level scalps on the KXBTC15M public trade tape.
Run: python3 scalp_b.py <tape dir> <out.json> [nproc]
"""
import json, os, sys, math, datetime as dt
import numpy as np
NAN=np.nan
DEC=np.arange(60,781,5)                 # decision seconds after the open
SIGNALS=["NONE","MOM10","FADE10","FLOW","FLOWFADE"]
B1=[(T,S,H) for T in (0.02,0.04) for S in (0.02,0.04) for H in (30,120)]
B2=[(X,S,H) for X in (0.01,0.02) for S in (0.02,0.04) for H in (30,120)]
ENTRY_WAIT=20
def fee(p): return 0.07*p*(1-p)
def ffill(a,maxage=10):
    n=len(a); idx=np.where(~np.isnan(a),np.arange(n),-1); idx=np.maximum.accumulate(idx)
    out=np.where((idx>=0)&((np.arange(n)-idx)<=maxage), a[np.clip(idx,0,None)], NAN)
    return out
def grid(T,P,C,S):
    """1-second arrays over the 900 s window, YES frame."""
    sec=np.floor(900.0+T).astype(np.int64); ok=(sec>=0)&(sec<900); sec=sec[ok]; P=np.round(P[ok].astype(np.float64),4); C=C[ok].astype(np.float64); S=S[ok]
    no=S==0; yes=S==1
    bid=np.full(900,NAN); ask=np.full(900,NAN)
    bid[sec[no]]=P[no]; ask[sec[yes]]=P[yes]          # repeated index: last print of the second wins (input is time-sorted)
    hitlow=np.full(900,np.inf); liftmax=np.full(900,-np.inf)
    np.minimum.at(hitlow,sec[no],P[no]); np.maximum.at(liftmax,sec[yes],P[yes])
    vy=np.zeros(900); vn=np.zeros(900); np.add.at(vy,sec[yes],C[yes]); np.add.at(vn,sec[no],C[no])
    return dict(bid=ffill(bid),ask=ffill(ask),hitlow=hitlow,liftmax=liftmax,vy=vy,vn=vn)
def mirror(g):
    """NO frame: buying NO at q is selling YES at 1-q."""
    return dict(bid=1-g['ask'],ask=1-g['bid'],hitlow=1-g['liftmax'],liftmax=1-g['hitlow'],vy=g['vn'],vn=g['vy'])
def at(a,i):
    """a[i] with NaN beyond the end."""
    out=np.full(len(i),NAN); m=i<len(a); out[m]=a[i[m]]; return out
def sim_b1(g,T,S,H):
    """Taker in at the ask, taker out at the bid on target / stop / time. Returns pnl per decision (NaN = no trade)."""
    P=g['ask'][DEC]; b0=g['bid'][DEC]
    live=(~np.isnan(P))&(~np.isnan(b0))&(b0<P)&(P>=0.10)&(P<=0.90)
    pnl=np.full(len(DEC),NAN); open_=live.copy()
    for h in range(1,H+1):
        if not open_.any(): break
        b=at(g['bid'],DEC+h)
        hit=open_&(~np.isnan(b))&((b<=P-S+1e-9)|(b>=P+T-1e-9)|(h==H))
        pnl[hit]=b[hit]-P[hit]-fee(P[hit])-fee(b[hit]); open_&=~hit
    # still open at H with no bid print in the last 10 s: look up to 30 s further for any bid
    for h in range(H+1,H+31):
        if not open_.any(): break
        b=at(g['bid'],DEC+h); hit=open_&(~np.isnan(b))
        pnl[hit]=b[hit]-P[hit]-fee(P[hit])-fee(b[hit]); open_&=~hit
    return pnl, open_.sum()
def entry_rest(g,through):
    """Join the best bid at each decision second; fill within ENTRY_WAIT s. Returns (price, fill second or -1)."""
    b0=g['bid'][DEC]; a0=g['ask'][DEC]
    live=(~np.isnan(b0))&(~np.isnan(a0))&(b0<a0)&(b0>=0.10)&(b0<=0.90)
    fs=np.full(len(DEC),-1); open_=live.copy()
    for w in range(1,ENTRY_WAIT+1):
        if not open_.any(): break
        lo=at(g['hitlow'],DEC+w)
        hit=open_&((lo<b0-1e-9) if through else (lo<=b0+1e-9))
        fs[hit]=DEC[hit]+w; open_&=~hit
    return b0,fs,live
def sim_b2(g,b0,fs,X,S,H,through):
    """Filled resting bid at b0 (second fs). Rest an offer at b0+X; stop / time-out sells at the bid with the fee."""
    filled=fs>=0; pnl=np.full(len(DEC),NAN); open_=filled.copy(); a0=b0+X; tgt=np.zeros(len(DEC),bool)
    for h in range(1,H+1):
        if not open_.any(): break
        b=at(g['bid'],fs+h); hi=at(g['liftmax'],fs+h)
        stop=open_&(~np.isnan(b))&(b<=b0-S+1e-9)
        pnl[stop]=b[stop]-b0[stop]-fee(b[stop]); open_&=~stop
        t=open_&((hi>a0+1e-9) if through else (hi>=a0-1e-9))
        pnl[t]=X; tgt|=t; open_&=~t
        if h==H:
            to=open_&(~np.isnan(b)); pnl[to]=b[to]-b0[to]-fee(b[to]); open_&=~to
    for h in range(H+1,H+31):
        if not open_.any(): break
        b=at(g['bid'],fs+h); to=open_&(~np.isnan(b)); pnl[to]=b[to]-b0[to]-fee(b[to]); open_&=~to
    return pnl,tgt
def signals(g):
    """Masks over DEC for the YES frame (True = this signal says buy YES)."""
    mid=(g['bid']+g['ask'])/2
    d=mid[DEC]-mid[DEC-10]
    vy=np.cumsum(g['vy']); vn=np.cumsum(g['vn'])
    wy=vy[DEC]-vy[DEC-30]; wn=vn[DEC]-vn[DEC-30]; tot=wy+wn
    flow=(tot>=500)&((wy-wn)>=0.5*tot)
    flowfade=(tot>=500)&((wn-wy)>=0.5*tot)
    with np.errstate(invalid='ignore'):
        return {"NONE":np.ones(len(DEC),bool),"MOM10":d>=0.02-1e-9,"FADE10":d<=-0.02+1e-9,"FLOW":flow,"FLOWFADE":flowfade}
def run_market(path,result,day):
    z=np.load(path); g0=grid(z['T'],z['P'],z['C'],z['S'])
    out={}
    def add(key,pnl,mask,extra=None):
        m=mask&(~np.isnan(pnl))
        if m.any():
            v=pnl[m]; a=out.setdefault(key,[0.0,0,0]); a[0]+=float(v.sum()); a[1]+=int(m.sum()); a[2]+=int((v>0).sum())
    for frame,g,res in (("Y",g0,1.0 if result=='yes' else 0.0),("N",mirror(g0),0.0 if result=='yes' else 1.0)):
        sg=signals(g)
        for (T,S,H) in B1:
            pnl,_=sim_b1(g,T,S,H)
            for s in SIGNALS: add(f"B1|{s}|T{int(T*100)}|S{int(S*100)}|H{H}",pnl,sg[s])
        for through in (True,False):
            fm="through" if through else "at"
            b0,fs,live=entry_rest(g,through)
            # B3: hold to settlement
            p3=np.where(fs>=0,res-b0,NAN)
            for s in SIGNALS:
                add(f"B3|{s}|{fm}",p3,sg[s])
                a=out.setdefault(f"POSTED|{s}|{fm}",[0.0,0,0]); a[1]+=int((live&sg[s]).sum()); a[2]+=int(((fs>=0)&sg[s]).sum())
            for (X,S,H) in B2:
                pnl,tgt=sim_b2(g,b0,fs,X,S,H,through)
                for s in SIGNALS:
                    add(f"B2|{s}|X{int(X*100)}|S{int(S*100)}|H{H}|{fm}",pnl,sg[s])
    return day,out
def work(args):
    try: return run_market(*args)
    except Exception as e: return ("ERR",str(e)[:200])
if __name__=="__main__":
    D=sys.argv[1]; OUT=sys.argv[2]; NP=int(sys.argv[3]) if len(sys.argv)>3 else 2
    mk=json.load(open(os.path.join(D,"markets.json")))
    jobs=[]
    for m in mk:
        p=os.path.join(D,"raw",m["ticker"]+".npz")
        if os.path.exists(p):
            day=dt.datetime.fromtimestamp(m["open"],dt.timezone.utc).strftime("%Y-%m-%d")
            jobs.append((p,m["result"],day))
    print(len(jobs),"markets with tape",flush=True)
    from multiprocessing import Pool
    agg={}; n=0; errs=0
    with Pool(NP) as pool:
        for day,out in pool.imap_unordered(work,jobs,chunksize=8):
            n+=1
            if day=="ERR": errs+=1; print("ERR",out,flush=True); continue
            for k,v in out.items():
                a=agg.setdefault(k,{}).setdefault(day,[0.0,0,0]); a[0]+=v[0]; a[1]+=v[1]; a[2]+=v[2]
            if n%200==0: print(n,flush=True)
    json.dump({"n":n,"errs":errs,"agg":agg},open(OUT,"w")); print("done",n,"errors",errs,flush=True)
