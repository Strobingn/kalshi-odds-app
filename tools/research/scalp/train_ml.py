"""ML scalping study (SCALP_ML_DESIGN.md): one LightGBM per target, threshold on VALID, one score on TEST."""
import numpy as np, lightgbm as lgb, json, sys, random
d=np.load('ml_data.npz',allow_pickle=True)
X=d['X']; Y=d['Y'].astype(np.float64)*100.0; mkt=d['mkt'].astype(int); mday=d['mday']; feats=[str(f) for f in d['feats']]
day=mday[mkt]; days=sorted(set(mday))
TR=set(days[:11]); VA=set(days[11:15]); TE=set(days[15:])
print(f"days {len(days)}: train {min(TR)}..{max(TR)} ({len(TR)}), valid {min(VA)}..{max(VA)} ({len(VA)}), test {min(TE)}..{max(TE)} ({len(TE)}); rows {len(X)}, features {len(feats)}")
itr=np.isin(day,list(TR)); iva=np.isin(day,list(VA)); ite=np.isin(day,list(TE))
NAMES=["T_HOLD","T_SCALP","R_SCALP_THR","R_HOLD_THR","R_SCALP_AT","R_HOLD_AT"]
PARAMS=dict(objective="regression",learning_rate=0.03,num_leaves=31,min_data_in_leaf=500,feature_fraction=0.7,bagging_fraction=0.7,bagging_freq=1,lambda_l2=10.0,verbose=-1,seed=7,num_threads=2)
QS=(0.50,0.70,0.80,0.90,0.95,0.98,0.99)
def dayci(vals,dd,B=4000,seed=5):
    by={}
    for v,x in zip(vals,dd): a=by.setdefault(x,[0.0,0]); a[0]+=v; a[1]+=1
    ks=list(by); rnd=random.Random(seed); out=[]
    for _ in range(B):
        s=n=0.0
        for k in rnd.choices(ks,k=len(ks)): s+=by[k][0]; n+=by[k][1]
        out.append(s/n if n else 0.0)
    out.sort(); return out[int(.025*B)],out[int(.975*B)],out[int(.005*B)],out[int(.995*B)],sum(1 for k in ks if by[k][0]>0),len(ks)
res={}
for j,name in enumerate(NAMES):
    y=Y[:,j]; ok=~np.isnan(y)
    a=itr&ok; b=iva&ok; c=ite&ok
    dtr=lgb.Dataset(X[a],y[a],feature_name=feats); dva=lgb.Dataset(X[b],y[b],reference=dtr)
    m=lgb.train(PARAMS,dtr,num_boost_round=2000,valid_sets=[dva],callbacks=[lgb.early_stopping(100,verbose=False)])
    pv=m.predict(X[b],num_iteration=m.best_iteration); pt=m.predict(X[c],num_iteration=m.best_iteration)
    yv=y[b]; yt=y[c]; dt_=day[c]
    best=None
    for q in QS:
        th=np.quantile(pv,q); sel=pv>=th
        if sel.sum()<300: continue
        tot=yv[sel].sum()
        if best is None or tot>best[0]: best=(tot,q,th,int(sel.sum()),yv[sel].mean())
    print(f"\n=== {name}: trees {m.best_iteration}; act-on-everything: train {y[a].mean():+.2f}c valid {yv.mean():+.2f}c test {yt.mean():+.2f}c (test rows {len(yt)})")
    corr=np.corrcoef(pt,yt)[0,1]
    order=np.argsort(pt); dec=np.array_split(order,10)
    print("   TEST by prediction decile (lowest -> highest), cents per action: "+" ".join(f"{yt[ix].mean():+.2f}" for ix in dec)+f"   corr {corr:+.3f}")
    imp=sorted(zip(m.feature_importance('gain'),feats),reverse=True)[:8]; tg=sum(i for i,_ in imp) or 1
    print("   top features by gain: "+", ".join(f"{f}" for i,f in imp))
    if best is None or best[0]<=0:
        print(f"   POLICY: do not trade (no threshold had a positive VALID total; best VALID mean {best[4] if best else float('nan'):+.2f}c)")
        # still show what the top-5% rule would have done on TEST, as context
        th=np.quantile(pv,0.95); sel=pt>=th
        if sel.sum()>50:
            lo,hi,lo9,hi9,pd_,nd=dayci(yt[sel],dt_[sel]); print(f"   context: top 5% by VALID cut on TEST: n={int(sel.sum())} {yt[sel].mean():+.2f}c [{lo:+.2f},{hi:+.2f}]")
        res[name]=dict(policy="none"); continue
    tot,q,th,nv,mv=best; sel=pt>=th
    lo,hi,lo9,hi9,pd_,nd=dayci(yt[sel],dt_[sel])
    print(f"   POLICY: act when prediction >= {th:+.2f}c (top {int(round((1-q)*100))}% of VALID). VALID: n={nv} {mv:+.2f}c per action")
    print(f"   TEST : n={int(sel.sum())} ({sel.mean()*100:.1f}% of rows)  {yt[sel].mean():+.2f}c per action  95% [{lo:+.2f},{hi:+.2f}]  99% [{lo9:+.2f},{hi9:+.2f}]  positive days {pd_}/{nd}  winners {100*(yt[sel]>0).mean():.1f}%")
    res[name]=dict(policy="act",q=q,theta=float(th),valid_n=nv,valid_mean=float(mv),test_n=int(sel.sum()),test_mean=float(yt[sel].mean()),ci95=[lo,hi],ci99=[lo9,hi9],posdays=[pd_,nd],base_test=float(yt.mean()),trees=int(m.best_iteration))
    m.save_model(f"ml_{name}.txt"); np.save(f"ml_pred_{name}.npy",np.column_stack([pt,yt]))
json.dump(res,open('ml_results.json','w'),indent=1)
