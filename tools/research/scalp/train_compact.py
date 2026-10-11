"""Compact scalper model for the app: only features the app can rebuild from the live trade prints with a
60-second lookback. Same split and protocol as SCALP_ML_DESIGN.md; target R_SCALP_AT (resting in, +1c offer,
4c stop, 120 s). Threshold = the VALID quantile with the highest VALID total P&L ("take everything the model
expects to pay"), from top 100/85/70/50/30/20/10 %. Scored once on TEST."""
import numpy as np, lightgbm as lgb, json, random
d=np.load('ml_data.npz',allow_pickle=True)
allf=[str(f) for f in d['feats']]
FEATS=["mid","spread","tte","dm5","dm10","dm20","dm30","dm60","vol60","nchg60","imb5","lvol5","imb10","lvol10","imb30","lvol30","imb60","lvol60","lcnt30","lmaxbuy30","lmaxsell30","stale_bid","stale_ask"]
X=np.nan_to_num(d['X'][:,[allf.index(f) for f in FEATS]].astype(np.float32),nan=0.0)
Y=d['Y'].astype(np.float64)*100.0; mkt=d['mkt'].astype(int); mday=d['mday']; day=mday[mkt]; days=sorted(set(mday))
TR=set(days[:11]); VA=set(days[11:15]); TE=set(days[15:])
y=Y[:,4]; ok=~np.isnan(y)
a=np.isin(day,list(TR))&ok; b=np.isin(day,list(VA))&ok; c=np.isin(day,list(TE))&ok
P=dict(objective="regression",learning_rate=0.03,num_leaves=31,min_data_in_leaf=500,feature_fraction=0.7,bagging_fraction=0.7,bagging_freq=1,lambda_l2=10.0,verbose=-1,seed=7,num_threads=2,use_missing=False)
dtr=lgb.Dataset(X[a],y[a],feature_name=FEATS); dva=lgb.Dataset(X[b],y[b],reference=dtr)
m=lgb.train(P,dtr,num_boost_round=2000,valid_sets=[dva],callbacks=[lgb.early_stopping(100,verbose=False)])
pv=m.predict(X[b],num_iteration=m.best_iteration); pt=m.predict(X[c],num_iteration=m.best_iteration); yv=y[b]; yt=y[c]
best=None
for q in (0.0,0.15,0.30,0.50,0.70,0.80,0.90):
    th=np.quantile(pv,q) if q>0 else -1e9; sel=pv>=th
    tot=yv[sel].sum(); print(f"  VALID top {int(round((1-q)*100)):3d}%: n={int(sel.sum()):6d} mean {yv[sel].mean():+.3f}c total {tot/100:+9.1f}$ per contract-lot")
    if best is None or tot>best[0]: best=(tot,q,float(th))
tot,q,theta=best; sel=pt>=theta
def ci(vals,dd,B=4000):
    by={}
    for v,x in zip(vals,dd): z=by.setdefault(x,[0.0,0]); z[0]+=v; z[1]+=1
    ks=list(by); rnd=random.Random(5); o=[]
    for _ in range(B):
        s=n=0.0
        for k in rnd.choices(ks,k=len(ks)): s+=by[k][0]; n+=by[k][1]
        o.append(s/n)
    o.sort(); return o[int(.025*B)],o[int(.975*B)],o[int(.005*B)],o[int(.995*B)],sum(1 for k in ks if by[k][0]>0),len(ks)
lo,hi,lo9,hi9,pdy,nd=ci(yt[sel],day[c][sel])
print(f"trees {m.best_iteration}; theta {theta:+.4f}c (top {int(round((1-q)*100))}% of VALID)")
print(f"TEST: take everything {yt.mean():+.3f}c (n={len(yt)}); model picks n={int(sel.sum())} ({100*sel.mean():.1f}%) {yt[sel].mean():+.3f}c 95% [{lo:+.2f},{hi:+.2f}] 99% [{lo9:+.2f},{hi9:+.2f}] days+ {pdy}/{nd}; total picks {yt[sel].sum()/100:+.0f} vs everything {yt.sum()/100:+.0f}")
order=np.argsort(pt); print("TEST deciles:"," ".join(f"{yt[ix].mean():+.2f}" for ix in np.array_split(order,10)))
imp=sorted(zip(m.feature_importance('gain'),FEATS),reverse=True); print("gain:",", ".join(f for _,f in imp[:8]))
# ---- export ----
dump=m.dump_model(num_iteration=m.best_iteration)
def flat(node,out):
    i=len(out); out.append(None)
    if 'leaf_value' in node and 'split_feature' not in node:
        out[i]=dict(leaf=node['leaf_value'])
    else:
        assert node['decision_type']=='<=', node['decision_type']
        l=flat(node['left_child'],out); r=flat(node['right_child'],out)
        out[i]=dict(f=node['split_feature'],t=node['threshold'],l=l,r=r)
    return i
trees=[]
for t in dump['tree_info']:
    nodes=[]; flat(t['tree_structure'],nodes)
    trees.append(dict(f=[n.get('f',-1) for n in nodes],t=[n.get('t',0.0) for n in nodes],l=[n.get('l',-1) for n in nodes],r=[n.get('r',-1) for n in nodes],v=[n.get('leaf',0.0) for n in nodes]))
model=dict(version=1,name="scalper_rest_plus1c",features=FEATS,theta_cents=theta,target="cents per order posted: rest at the best bid 20 s, offer +1c, stop 4c, 120 s",
           trained=f"{min(TR)}..{max(TR)}",valid=f"{min(VA)}..{max(VA)}",test=f"{min(TE)}..{max(TE)}",
           test_front_of_queue_cents=round(float(yt[sel].mean()),4),test_take_everything_cents=round(float(yt.mean()),4),trees=trees)
json.dump(model,open('scalper_model.json','w'),separators=(',',':'))
# own evaluator check
def ev(x):
    s=0.0
    for t in trees:
        i=0
        while t['f'][i]>=0: i=t['l'][i] if float(np.float32(x[t['f'][i]]))<=t['t'][i] else t['r'][i]
        s+=t['v'][i]
    return s
idx=np.where(c)[0][::997][:300]
mx=max(abs(ev(X[i])-m.predict(X[i:i+1],num_iteration=m.best_iteration)[0]) for i in idx)
print("exported trees",len(trees),"nodes",sum(len(t['f']) for t in trees),"max |own evaluator - lightgbm| on 300 rows:",mx)
np.save('compact_pred_test.npy',np.column_stack([pt,yt])); json.dump(dict(theta=theta,feats=FEATS),open('compact_meta.json','w'))
import os; print("model json bytes",os.path.getsize('scalper_model.json'))
