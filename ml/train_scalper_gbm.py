import pandas as pd, numpy as np, lightgbm as lgb, json
from sklearn.isotonic import IsotonicRegression
P=pd.read_parquet('/workspace/scalp-research/prep/panel2.parquet')
P=P[(P.coin=='BTC')&P.result.isin([0,1])].copy()
P=P.sort_values(['tk','ts'])
# on-device features
P['imb']=(P.ybq-P.yaq)/(P.ybq+P.yaq).clip(lower=1)
P['mid_p']=P.mid/1000; P['spr_p']=P.spr/1000; P['pf']=P.pfair/1000; P['dm60_p']=P.dm60.fillna(0)/1000
P['z']=P.z.clip(-8,8); P['tau_m']=P.tau/60
P=P[(P.yb>0)&(P.ya<1000)]
P['b']=(P.ts//15000); P=P.drop_duplicates(['tk','b'])
F=['z','pf','tau_m','mid_p','spr_p','imb','dm60_p']
day=pd.to_datetime(P.close,unit='ms').dt.tz_localize('UTC').dt.tz_convert('America/New_York').dt.date.astype(str)
P['day']=day
days=sorted(P.day.unique()); print(days)
test=days[-3:]; cal=days[-6:-3]
tr=P[~P.day.isin(test+cal)]; ca=P[P.day.isin(cal)]; te=P[P.day.isin(test)]
print(len(tr),len(ca),len(te), tr.tk.nunique(),ca.tk.nunique(),te.tk.nunique())
m=lgb.LGBMClassifier(n_estimators=200,learning_rate=0.05,num_leaves=15,min_child_samples=200,subsample=0.8,subsample_freq=1,colsample_bytree=0.9,reg_lambda=1.0,verbose=-1)
m.fit(tr[F],tr.result)
iso=IsotonicRegression(out_of_bounds='clip',y_min=0.001,y_max=0.999).fit(m.predict_proba(ca[F])[:,1],ca.result)
def brier(p,y): return float(np.mean((p-y)**2))
pt=iso.predict(m.predict_proba(te[F])[:,1]); y=te.result.values
r={'model':brier(pt,y),'market_mid':brier(te.mid_p.values,y),'fair':brier(te.pf.values,y),'n_test':len(te),'test_days':test}
# high-confidence check
hc=np.maximum(pt,1-pt)>0.95; r['n_conf95']=int(hc.sum()); r['acc_conf95']=float(((pt>0.5)==(y==1))[hc].mean()) if hc.any() else None
print(r)
# refit on all for export? keep train-only model + calibrator (honest)
bst=m.booster_.dump_model()
def conv(n):
    if 'leaf_value' in n: return {'v':n['leaf_value']}
    return {'f':n['split_feature'],'t':n['threshold'],'l':conv(n['left_child']),'r':conv(n['right_child']),'d':bool(n.get('default_left',True))}
out={'features':F,'init':0.0,'trees':[conv(t['tree_structure']) for t in bst['tree_info']],
     'iso_x':iso.X_thresholds_.tolist(),'iso_y':iso.y_thresholds_.tolist(),'metrics':r,'trained_on':'BTC 15m books+spot '+days[0]+'..'+days[-1]}
json.dump(out,open('btc15_gbm.json','w'),separators=(',',':'))
# parity samples
s=te.sample(20,random_state=1)
json.dump({'x':s[F].values.tolist(),'p':iso.predict(m.predict_proba(s[F])[:,1]).tolist()},open('parity.json','w'))
