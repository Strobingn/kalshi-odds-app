import pickle,glob,math,json,sys,itertools
import numpy as np
from math import erf
res=pickle.load(open("/workspace/forecast53/results.pkl","rb"))
S,strike=pickle.load(open("/workspace/fc54/spot.pkl","rb"))
days=sorted(p.split("ser-")[1][:10] for p in glob.glob("/workspace/forecast53/ser-*.pkl"))
TRAIN,TEST=days[:10],days[10:]
def fee(c,p): return math.ceil(0.07*c*p*(1-p)*100-1e-9)/100.0 if c>0 else 0.0
SIG={}
for c,(t0,a) in S.items():
    r=np.diff(np.log(a),prepend=np.log(a[0])); cs=np.cumsum(r*r); w=900
    SIG[c]=np.sqrt(np.maximum((cs-np.concatenate([np.zeros(w),cs[:-w]]))/w,1e-12))
Phi=np.vectorize(lambda x:0.5*(1+erf(x/math.sqrt(2))))
M={}
for d in days:
    L=[]
    for tk,rows in pickle.load(open(f"/workspace/forecast53/ser-{d}.pkl","rb")).items():
        if tk not in res or tk not in strike or len(rows)<30: continue
        a=np.array(sorted(rows)); close=a[0,7]; a=a[a[:,0]<close]
        if len(a)<30: continue
        coin=tk[2:5]; t0,sp=S[coin]; K=strike[tk]
        ts=a[:,0].astype(np.int64); sec=ts//1000-t0; osec=int((close-900000)//1000-t0)
        if sec.min()<70 or sec.max()>=len(sp): continue
        b=math.log(sp[osec]/K); s=sp[sec]*math.exp(-b)
        tau=np.maximum((close-ts)/1000.0,1); z=SIG[coin][sec]*np.sqrt(tau)
        fair=Phi(np.clip(np.log(s/K)/z,-8,8))
        L.append(dict(tk=tk,coin=coin,ts=ts,yb=a[:,1],ya=a[:,2],ybq=a[:,3],yaq=a[:,4],tau=tau,fair=fair,s=s,sec=sec,sp=sp,b=b,res=res[tk]))
    M[d]=L
print("markets",{d:len(v) for d,v in M.items()},flush=True)
C=10
def side_px(mk,i,side):  # (ask, askq, bid, bidq) for side
    if side=="YES": return mk["ya"][i],mk["yaq"][i],mk["yb"][i],mk["ybq"][i]
    return 1-mk["yb"][i],mk["ybq"][i],1-mk["ya"][i],mk["yaq"][i]
def settle_val(mk,side): return mk["res"] if side=="YES" else 1-mk["res"]
def taker_trade(mk,i,side,exit_fn,maxhold):
    """buy at ask (depth-capped), exit when exit_fn(k) true at bid (depth-capped, rest settles)."""
    ask,aq,_,_=side_px(mk,i,side); q=min(C,int(aq))
    if q<=0: return None,i
    cost=q*ask+fee(q,ask); n=len(mk["ts"]); k=i+1
    while k<n:
        if mk["tau"][k]<30 or (mk["ts"][k]-mk["ts"][i])/1000>=maxhold or exit_fn(k): break
        k+=1
    pay=settle_val(mk,side)
    if k>=n or mk["tau"][min(k,n-1)]<30 and maxhold>=900:
        return q*pay-cost, n
    _,_,bid,bq=side_px(mk,k,side); sold=min(q,int(bq))
    return sold*bid-fee(sold,bid)+(q-sold)*pay-cost, k
# ---------------- strategies -----------------
def s_fvgap(mk,p):
    out=[]; n=len(mk["ts"]); i=0
    while i<n:
        t=mk["tau"][i]
        if p["tmin"]<=t<=p["tmax"]:
            for side in ("YES","NO"):
                f=mk["fair"][i] if side=="YES" else 1-mk["fair"][i]
                ask=side_px(mk,i,side)[0]
                if f-ask>=p["gap"] and 0.03<=ask<=0.97:
                    if p["exit"]=="settle": ex=lambda k: False; mh=900
                    else:
                        ex=lambda k,side=side: side_px(mk,k,side)[2] >= ((mk["fair"][k] if side=="YES" else 1-mk["fair"][k])-p["conv"]); mh=p.get("mh",300)
                    pn,k=taker_trade(mk,i,side,ex,mh)
                    if pn is not None: out.append(pn); i=k; break
        i+=1
    return out
def s_dip(mk,p):
    out=[]; n=len(mk["ts"]); i=0; ts=mk["ts"]
    while i<n:
        if 60<=mk["tau"][i]<=870:
            j=np.searchsorted(ts,ts[i]-p["win"]*1000)
            if j<i:
                for side in ("YES","NO"):
                    a0=side_px(mk,j,side)[0]; a1=side_px(mk,i,side)[0]
                    f0=mk["fair"][j] if side=="YES" else 1-mk["fair"][j]; f1=mk["fair"][i] if side=="YES" else 1-mk["fair"][i]
                    if a0-a1>=p["drop"] and (f0-f1)<p["spotmax"] and a1>=0.05:
                        entry=a1
                        ex=lambda k,side=side,e=entry: side_px(mk,k,side)[2]>=e+p["tp"]
                        pn,k=taker_trade(mk,i,side,ex,p["mh"])
                        if pn is not None: out.append(pn); i=k; break
        i+=1
    return out
def s_maker(mk,p):
    """rest a bid at best bid (or bid+1c) on the side whose fair > bid+edge; filled only when the ask trades THROUGH
    our price (ask <= px-0.01) i.e. adverse-selection-aware; exit: resting ask at px+tp filled when bid >= that+0.01;
    otherwise taker exit at bid when tau<45s or after mh s. Maker fee 0 (these series)."""
    out=[]; n=len(mk["ts"]); i=0
    while i<n:
        if 120<=mk["tau"][i]<=870:
            for side in ("YES","NO"):
                ask,aq,bid,bq=side_px(mk,i,side); f=mk["fair"][i] if side=="YES" else 1-mk["fair"][i]
                px=round(bid+p["imp"],2)
                if px>=ask or px<0.05 or px>0.95 or f-px<p["edge"]: continue
                # wait for fill up to ttl
                k=i+1; filled=None
                while k<n and (mk["ts"][k]-mk["ts"][i])/1000<=p["ttl"] and mk["tau"][k]>60:
                    if side_px(mk,k,side)[0]<=px-0.01+1e-9: filled=k; break
                    k+=1
                if filled is None: i=k; break
                tgt=px+p["tp"]; k=filled+1; pn=None
                while k<n:
                    _,_,b2,bq2=side_px(mk,k,side)
                    if b2>=tgt+0.01-1e-9: pn=C*(tgt-px); break
                    if mk["tau"][k]<45 or (mk["ts"][k]-mk["ts"][filled])/1000>=p["mh"]:
                        sold=min(C,int(bq2)); pn=sold*b2-fee(sold,b2)+(C-sold)*settle_val(mk,side)-C*px; break
                    k+=1
                if pn is None: pn=C*(settle_val(mk,side)-px); k=n
                out.append(pn); i=k; break
        i+=1
    return out
def s_shock(mk,p):
    out=[]; n=len(mk["ts"]); i=0; sp=mk["sp"]
    while i<n:
        if 60<=mk["tau"][i]<=870:
            sec=mk["sec"][i]; r=math.log(sp[sec]/sp[sec-p["Y"]])*1e4
            if abs(r)>=p["X"]:
                side="YES" if r>0 else "NO"
                f=mk["fair"][i] if side=="YES" else 1-mk["fair"][i]; ask=side_px(mk,i,side)[0]
                if f-ask>=p["lag"] and 0.05<=ask<=0.95:
                    ex=lambda k,side=side: side_px(mk,k,side)[2]>=((mk["fair"][k] if side=="YES" else 1-mk["fair"][k])-0.01)
                    pn,k=taker_trade(mk,i,side,ex,p["mh"])
                    if pn is not None: out.append(pn); i=k; continue
        i+=1
    return out
def s_arb(mk,p):
    # YES ask + NO ask = ya + (1-yb): < $1 only if the book is crossed
    out=[]
    for i in range(len(mk["ts"])):
        if mk["tau"][i]<=p["tmax"]:
            ya=mk["ya"][i]; na=1-mk["yb"][i]; q=min(C,int(mk["yaq"][i]),int(mk["ybq"][i]))
            if q>0 and q*(ya+na)+fee(q,ya)+fee(q,na)<q-1e-9: out.append(q-(q*(ya+na)+fee(q,ya)+fee(q,na)))
    return out
def s_latefav(mk,p):
    out=[]
    for i in range(len(mk["ts"])):
        if mk["tau"][i]<=p["tmax"] and mk["tau"][i]>=20:
            for side in ("YES","NO"):
                f=mk["fair"][i] if side=="YES" else 1-mk["fair"][i]; ask,aq,_,_=side_px(mk,i,side)
                if f>=p["fmin"] and f-ask>=p["gap"] and ask<=0.97:
                    q=min(C,int(aq))
                    if q>0: return [q*settle_val(mk,side)-q*ask-fee(q,ask)]
    return out

BTCONLY=__import__("os").environ.get("BTCONLY")=="1"
def run(fn,p,ds):
    per=[]  # (market, pnl list)
    for d in ds:
        for mk in M[d]:
            if BTCONLY and mk["coin"]!="BTC": continue
            tr=fn(mk,p)
            if tr: per.append(tr)
    return per
def ci(per,B=2000,seed=0):
    if not per: return (0,0,None,None,None)
    rng=np.random.default_rng(seed); sums=np.array([sum(x) for x in per]); cnt=np.array([len(x) for x in per])
    mean=sums.sum()/cnt.sum(); bs=[]
    for _ in range(B):
        ix=rng.integers(0,len(per),len(per)); bs.append(sums[ix].sum()/cnt[ix].sum())
    return (int(cnt.sum()),round(float(sums.sum()),2),round(float(mean),4),round(float(np.percentile(bs,2.5)),4),round(float(np.percentile(bs,97.5)),4))
