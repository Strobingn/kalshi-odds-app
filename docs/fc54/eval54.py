import pickle,glob,math,json,sys
import numpy as np
from math import erf
sys.path.insert(0,"/workspace/forecast53")
res=pickle.load(open("/workspace/forecast53/results.pkl","rb"))
S,strike=pickle.load(open("spot.pkl","rb"))
days=sorted(p.split("ser-")[1][:10] for p in glob.glob("/workspace/forecast53/ser-*.pkl"))
HS=[30,60,120,180,300,600,"close"]; MS=[0.0,0.005,0.01,0.02,0.03,0.05]
def fee(c,p): return math.ceil(0.07*c*p*(1-p)*100-1e-9)/100.0
# per-coin sigma per sec: rolling 900s std of 1s log returns
SIG={}
for c,(t0,a) in S.items():
    r=np.diff(np.log(a),prepend=np.log(a[0])); cs=np.cumsum(r*r); w=900
    v=(cs-np.concatenate([np.zeros(w),cs[:-w]]))/w; SIG[c]=np.sqrt(np.maximum(v,1e-12))
Phi=np.vectorize(lambda x:0.5*(1+erf(x/math.sqrt(2))))
def f53(ts,mid,iy,inn,close):
    n=len(ts); X=np.zeros((n,6))
    j60=np.searchsorted(ts,ts-60000); j180=np.searchsorted(ts,ts-180000); ar=np.arange(n)
    X[:,0]=1; X[:,1]=np.where(j60<ar,mid-mid[j60],0); X[:,2]=np.where(j180<ar,mid-mid[j180],0)
    X[:,3]=(iy-inn)/np.maximum(iy+inn,1e-9); tau=np.clip((close-ts)/900000.0,0,1)
    X[:,4]=mid-0.5; X[:,5]=(mid-0.5)*(1-tau); return X
def f54(tk,coin,ts,mid,iy,inn,close):
    t0,a=S[coin]; K=strike.get(tk)
    if K is None: return None
    sec=(ts//1000-t0).astype(int); osec=int((close-900000)//1000-t0)
    if sec.min()<70 or sec.max()>=len(a) or osec<0: return None
    b=math.log(a[osec]/K); s=a[sec]*math.exp(-b)
    tau=np.maximum((close-ts)/1000.0,1.0); z=SIG[coin][sec]*np.sqrt(tau)
    d=np.clip(np.log(s/K)/z,-4,4); fair=Phi(d); phi=np.exp(-d*d/2)/math.sqrt(2*math.pi)
    n=len(ts); X=np.zeros((n,11)); ar=np.arange(n)
    j60=np.searchsorted(ts,ts-60000)
    X[:,0]=1; X[:,1]=np.where(j60<ar,mid-mid[j60],0); X[:,2]=(iy-inn)/np.maximum(iy+inn,1e-9)
    X[:,3]=mid-0.5; X[:,4]=fair-mid; X[:,5]=d/4; X[:,6]=tau/900
    for i,k in enumerate((5,15,30,60)):
        X[:,7+i]=np.clip(phi*np.log(a[sec]/a[sec-k])/z,-0.5,0.5)
    return X
data={}
for d in days:
    ser=pickle.load(open(f"/workspace/forecast53/ser-{d}.pkl","rb")); L=[]
    for tk,rows in ser.items():
        if tk not in res or len(rows)<30: continue
        a=np.array(sorted(rows)); close=a[0,7]; m=a[:,0]<close
        if m.sum()<30: continue
        a=a[m]; ts=a[:,0].astype(np.int64); yb=a[:,1]; ya=a[:,2]; mid=(yb+ya)/2; coin=tk[2:5]
        X4=f54(tk,coin,ts,mid,a[:,5],a[:,6],close)
        if X4 is None: continue
        L.append(dict(tk=tk,coin=coin,ts=ts,yb=yb,ya=ya,ybq=a[:,3],nbq=a[:,4],mid=mid,X53=f53(ts,mid,a[:,5],a[:,6],close),X54=X4,close=close,res=res[tk]))
    data[d]=L
print("markets",sum(len(v) for v in data.values()),flush=True)
def target(mk,H):
    ts,mid=mk["ts"],mk["mid"]
    if H=="close": return mk["res"]-mid,np.ones(len(ts),bool)
    j=np.searchsorted(ts,ts+H*1000); ok=(j<len(ts))&(ts+H*1000<mk["close"])
    y=np.zeros(len(ts)); y[ok]=mid[j[ok]]-mid[ok]; return y,ok
def fit(mks,H,key):
    X=np.vstack([mk[key][target(mk,H)[1]] for mk in mks]); y=np.concatenate([target(mk,H)[0][target(mk,H)[1]] for mk in mks])
    lam=1e-3*np.eye(X.shape[1]); lam[0,0]=0
    return np.linalg.solve(X.T@X+lam*len(y),X.T@y)
def sim(mk,f,H,margin,v53,C=10):
    ts=mk["ts"]; n=len(ts); out=[]; i=0
    Hs=1e9 if H=="close" else H
    while i<n:
        if mk["close"]-ts[i]<60000: break
        mid=mk["mid"][i]; sp=mk["ya"][i]-mk["yb"][i]
        side="YES" if f[i]>0 else "NO"
        ask=mk["ya"][i] if side=="YES" else 1-mk["yb"][i]
        depth=mk["nbq"][i] if side=="YES" else mk["ybq"][i]
        q=min(C,int(depth))
        cost=sp+2*fee(1,ask)+(0.005 if v53 else margin)   # full round trip: spread + entry fee + exit fee
        if 0.40<=mid<=0.60 and sp<=0.02+1e-9 and q>0 and abs(f[i])>cost:
            ef=fee(q,ask); t0=ts[i]; k=i+1; ex=None
            while k<n:
                bid=mk["yb"][k] if side=="YES" else 1-mk["ya"][k]
                sf=f[k] if side=="YES" else -f[k]
                if bid>=0.90 or sf<-0.03 or sf<fee(1,bid) or (ts[k]-t0)/1000>=Hs or mk["close"]-ts[k]<30000: ex=(k,bid); break
                k+=1
            pay=mk["res"] if side=="YES" else 1-mk["res"]
            if ex is None: pnl=q*(pay-ask)-ef; k=n
            else:
                k,bid=ex; bq=mk["ybq"][k] if side=="YES" else mk["nbq"][k]; sold=min(q,int(bq))
                pnl=sold*bid-fee(sold,bid)+(q-sold)*pay-q*ask-ef
            out.append((pnl,q*ask+ef)); i=k+1
        else: i+=1
    return out
WF0=5; rep={}
for coin in ["BTC","ETH","SOL"]:
    R={}  # (ver,H,m,dir)->{day:[trades]}
    stats={}
    for H in HS:
        P53=[];P54=[];A=[]
        for di in range(WF0,len(days)):
            tr=[m for d in days[:di] for m in data[d] if m["coin"]==coin]; te=[m for m in data[days[di]] if m["coin"]==coin]
            if not tr or not te: continue
            w53=fit(tr,H,"X53"); w54=fit(tr,H,"X54")
            for mk in te:
                y,ok=target(mk,H); p53=mk["X53"]@w53; p54=mk["X54"]@w54
                nz=ok&(np.abs(y)>1e-9); P53.append(p53[nz]); P54.append(p54[nz]); A.append(y[nz])
                for sgn,dr in ((1,"ride"),(-1,"fade")):
                    R.setdefault(("fc53",str(H),0.005,dr),{}).setdefault(days[di],[]).extend(sim(mk,sgn*p53,H,0,True))
                    for m in MS:
                        R.setdefault(("fc54",str(H),m,dr),{}).setdefault(days[di],[]).extend(sim(mk,sgn*p54,H,m,False))
        A=np.concatenate(A)
        for ver,P in (("fc53",np.concatenate(P53)),("fc54",np.concatenate(P54))):
            qs=np.quantile(P,[0,.2,.4,.6,.8,1]); cal=[]
            for lo,hi in zip(qs[:-1],qs[1:]):
                mm=(P>=lo)&(P<=hi); cal.append([round(float(P[mm].mean())*100,2),round(float(A[mm].mean())*100,2)])
            big=np.abs(P)>0.03
            stats[f"{ver}|{H}"]=dict(hit=round(float(np.mean(np.sign(P)==np.sign(A))),4),n=int(len(P)),cal=cal,
                corr=round(float(np.corrcoef(P,A)[0,1]),4),hitBig=round(float(np.mean(np.sign(P[big])==np.sign(A[big]))),4) if big.sum() else None,nBig=int(big.sum()))
            print(coin,ver,H,stats[f"{ver}|{H}"]["hit"],stats[f"{ver}|{H}"]["corr"],stats[f"{ver}|{H}"]["hitBig"],stats[f"{ver}|{H}"]["nBig"],flush=True)
    def tot(trs): 
        pn=[t[0] for t in trs]; c=[t[1] for t in trs]
        return dict(n=len(pn),net=round(sum(pn),2),win=round(float(np.mean(np.array(pn)>0)),3) if pn else None,roi=round(sum(pn)/sum(c),4) if c else None)
    grid={"|".join(map(str,k)):tot([t for v in dd.values() for t in v]) for k,dd in R.items()}
    tdays=days[WF0:]
    sel={}
    for ver in ("fc53","fc54"):
        for dr in ("ride","fade"):
            keys=[k for k in R if k[0]==ver and k[3]==dr]; picks=[]; trs=[]
            for i,d in enumerate(tdays):
                if i<3: continue
                # pick by prior test-day P&L; zero-trade configs score 0 (= "don't trade")
                best=max(keys,key=lambda k:(sum(t[0] for x in tdays[:i] for t in R[k].get(x,[])),-k[2] if ver=="fc54" else 0))
                picks.append(f"{best[1]}/{best[2]}"); trs+=R[best].get(d,[])
            final=max(keys,key=lambda k:sum(t[0] for x in tdays for t in R[k].get(x,[])))
            sel[f"{ver}|{dr}"]=dict(oos=tot(trs),picks=picks,final=f"{final[1]}/{final[2]}")
    W={str(H):[round(float(v),6) for v in fit([m for d in days for m in data[d] if m["coin"]==coin],H,"X54")] for H in HS}
    rep[coin]=dict(stats=stats,grid=grid,sel=sel,weights=W)
    print(coin,json.dumps(sel),flush=True)
json.dump(rep,open("report54.json","w"),indent=1)
