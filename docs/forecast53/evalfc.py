import pickle,glob,math,json,sys
import numpy as np
res=pickle.load(open("results.pkl","rb"))
days=sorted(p[4:14] for p in glob.glob("ser-*.pkl"))
HS=[60,120,180,300,480,600,"close"]
def fee(c,p): return math.ceil(0.07*c*p*(1-p)*100-1e-9)/100.0
def feats(ts,mid,iy,inn,close):
    n=len(ts); X=np.zeros((n,6))
    j60=np.searchsorted(ts,ts-60000); j180=np.searchsorted(ts,ts-180000)
    has60=(ts-ts[np.minimum(j60,n-1)])>=45000
    m60=np.where(j60<np.arange(n), mid-mid[j60], 0.0)
    m180=np.where(j180<np.arange(n), mid-mid[j180], 0.0)
    imb=(iy-inn)/np.maximum(iy+inn,1e-9)
    tau=np.clip((close-ts)/900000.0,0,1)
    X[:,0]=1; X[:,1]=m60; X[:,2]=m180; X[:,3]=imb; X[:,4]=mid-0.5; X[:,5]=(mid-0.5)*(1-tau)
    return X,tau
data={}  # day -> list of markets dicts
for d in days:
    ser=pickle.load(open(f"ser-{d}.pkl","rb")); L=[]
    for tk,rows in ser.items():
        if tk not in res or len(rows)<30: continue
        a=np.array(sorted(rows))
        ts=a[:,0]; yb=a[:,1]; ya=a[:,2]; mid=(yb+ya)/2
        close=a[0,7]; m=ts<close
        if m.sum()<30: continue
        ts,yb,ya,mid=ts[m],yb[m],ya[m],mid[m]
        X,tau=feats(ts,mid,a[m,5],a[m,6],close)
        L.append(dict(tk=tk,coin=tk[2:5],ts=ts,yb=yb,ya=ya,ybq=a[m,3],nbq=a[m,4],mid=mid,X=X,tau=tau,close=close,res=res[tk]))
    data[d]=L
def target(mk,H):
    ts,mid=mk["ts"],mk["mid"]
    if H=="close": return mk["res"]-mid, np.ones(len(ts),bool)
    j=np.searchsorted(ts,ts+H*1000); ok=(j<len(ts))&(ts+H*1000<mk["close"])
    y=np.zeros(len(ts)); y[ok]=mid[j[ok]]-mid[ok]; return y,ok
def fit(mks,H):
    Xs=[];ys=[]
    for mk in mks:
        y,ok=target(mk,H); Xs.append(mk["X"][ok]); ys.append(y[ok])
    X=np.vstack(Xs); y=np.concatenate(ys)
    lam=1e-3*np.eye(X.shape[1]); lam[0,0]=0
    return np.linalg.solve(X.T@X+lam*len(y),X.T@y)
def sim(mk,w,H,sign,C=10):
    """sign=+1 ride, -1 fade. Forecast f(t)=sign*X@w is predicted YES-mid move; trade the side it favours."""
    f=sign*(mk["X"]@w); ts=mk["ts"]; n=len(ts); trades=[]; i=0
    Hs=(mk["close"]-ts[0])/1000 if H=="close" else H
    while i<n:
        if mk["close"]-ts[i]<60000: break
        mid=mk["mid"][i]; spread=mk["ya"][i]-mk["yb"][i]
        side="YES" if f[i]>0 else "NO"
        ask=mk["ya"][i] if side=="YES" else 1-mk["yb"][i]
        depth=mk["nbq"][i] if side=="YES" else mk["ybq"][i]
        move=abs(f[i])
        q=min(C,int(depth))
        if 0.40<=mid<=0.60 and spread<=0.02+1e-9 and q>0 and move> spread+2*fee(1,ask)+0.005:
            ef=fee(q,ask); t0=ts[i]; k=i+1; ex=None
            while k<n:
                bid=mk["yb"][k] if side=="YES" else 1-mk["ya"][k]
                sf=(f[k] if side=="YES" else -f[k])
                held=(ts[k]-t0)/1000
                if bid>=0.90 or sf< -0.03 or sf< fee(1,bid) or held>=Hs or mk["close"]-ts[k]<30000:
                    ex=(k,bid); break
                k+=1
            if ex is None:
                pay=mk["res"] if side=="YES" else 1-mk["res"]; pnl=q*(pay-ask)-ef; k=n
            else:
                k,bid=ex; bq=mk["ybq"][k] if side=="YES" else mk["nbq"][k]
                sold=min(q,int(bq)); pay=mk["res"] if side=="YES" else 1-mk["res"]
                pnl=sold*bid-fee(sold,bid)+(q-sold)*pay-q*ask-ef
            trades.append((mk["tk"],pnl,q*ask+ef)); i=k+1
        else: i+=1
    return trades
out={}
WF0=5
for coin in ["BTC","ETH","SOL"]:
    out[coin]={}
    for H in HS:
        rec={"hit":[], "pred":[], "act":[], "ride":[], "fade":[], "rideDay":{}, "fadeDay":{}}
        for di in range(WF0,len(days)):
            train=[m for d in days[:di] for m in data[d] if m["coin"]==coin]
            test=[m for m in data[days[di]] if m["coin"]==coin]
            if not train or not test: continue
            w=fit(train,H)
            for mk in test:
                y,ok=target(mk,H); p=(mk["X"]@w)[ok]; y=y[ok]
                nz=np.abs(y)>1e-9
                rec["pred"].append(p[nz]); rec["act"].append(y[nz])
                for s,key in ((1,"ride"),(-1,"fade")):
                    tr=sim(mk,w,H,s); rec[key]+=tr
                    rec[key+"Day"][days[di]]=rec[key+"Day"].get(days[di],0)+sum(t[1] for t in tr)
        P=np.concatenate(rec["pred"]); A=np.concatenate(rec["act"])
        hit=float(np.mean(np.sign(P)==np.sign(A)))
        # calibration: quintiles of predicted move vs realised move (cents)
        qs=np.quantile(P,[0,.2,.4,.6,.8,1]); cal=[]
        for a,b in zip(qs[:-1],qs[1:]):
            m=(P>=a)&(P<=b); cal.append((round(float(P[m].mean())*100,2),round(float(A[m].mean())*100,2),round(float(np.mean(A[m]>0)),3)))
        def summ(tr):
            if not tr: return dict(n=0)
            pn=np.array([t[1] for t in tr]); cost=np.array([t[2] for t in tr])
            return dict(n=len(tr),net=round(float(pn.sum()),2),perTrade=round(float(pn.mean()),4),win=round(float(np.mean(pn>0)),3),roi=round(float(pn.sum()/cost.sum()),4))
        out[coin][str(H)]=dict(hit=round(hit,4),n=int(len(P)),cal=cal,ride=summ(rec["ride"]),fade=summ(rec["fade"]),rideDay=rec["rideDay"],fadeDay=rec["fadeDay"])
        print(coin,H,out[coin][str(H)]["hit"],out[coin][str(H)]["ride"],out[coin][str(H)]["fade"],flush=True)
# walk-forward horizon selection per coin: on each test day pick the horizon with best cumulative ride P&L on prior test days
sel={}
for coin in out:
    for key in ("ride","fade"):
        tot=0.0; picks=[]
        tdays=sorted(out[coin]["60"][key+"Day"].keys())
        for i,d in enumerate(tdays):
            if i<3: continue
            best=max(HS,key=lambda H: sum(out[coin][str(H)][key+"Day"].get(x,0) for x in tdays[:i]))
            tot+=out[coin][str(best)][key+"Day"].get(d,0); picks.append(str(best))
        final=max(HS,key=lambda H: sum(out[coin][str(H)][key+"Day"].values()))
        sel[f"{coin}:{key}"]=dict(oosSelectedNet=round(tot,2),picks=picks,finalPick=str(final))
out["selection"]=sel
# final weights per coin/horizon fit on ALL days (for the app)
W={coin:{str(H):[round(float(v),6) for v in fit([m for d in days for m in data[d] if m["coin"]==coin],H)] for H in HS} for coin in ["BTC","ETH","SOL"]}
out["weights"]=W
json.dump(out,open("report.json","w"),indent=1); print(json.dumps(sel,indent=1))
