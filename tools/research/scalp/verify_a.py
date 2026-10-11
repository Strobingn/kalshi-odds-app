"""Independent recomputation of two Part A rows with pandas (different code path from scalp_a.py)."""
import json, pandas as pd, numpy as np
mk=pd.DataFrame(json.load(open('mk_KXBTC15M.json'))).drop_duplicates('ticker').set_index('ticker')
rows=[]
for l in open('cd_KXBTC15M.jsonl'):
    o=json.loads(l)
    for e,yb,ya,v in o['c']: rows.append((o['t'],e,yb,ya))
q=pd.DataFrame(rows,columns=['t','e','yb','ya']).drop_duplicates(['t','e']).dropna()
q['open']=q['t'].map(mk['open']); q['m']=((q['e']-q['open'])/60).round().astype(int)
q['day']=pd.to_datetime(q['open'],unit='s').dt.strftime('%Y-%m-%d')
fee=lambda p: 0.07*p*(1-p)
for k in (1,3):
    a=q.rename(columns={'yb':'yb0','ya':'ya0'}); b=q[['t','m','yb','ya']].copy(); b['m']=b['m']-k
    j=a.merge(b,on=['t','m']); p=q[['t','m','yb','ya']].copy(); p['m']=p['m']+1; p=p.rename(columns={'yb':'pb','ya':'pa'})
    j=j.merge(p,on=['t','m'])              # scalp_a requires the previous minute's quote too
    j=j[(j.m>=2)&(j.m<=11)&(j.yb0>0)&(j.yb0<=j.ya0)&(j.ya0<1)]
    up=(j.yb0+j.ya0)/2>=0.5
    ask=np.where(up,j.ya0,1-j.yb0); xb=np.clip(np.where(up,j.yb,1-j.ya),0,1)
    ok=(ask>=0.05)&(ask<=0.95); pnl=(xb-ask-fee(ask)-fee(xb))[ok]; day=j.day[ok]
    oos=day>='2026-09-23'
    print(f"ALWAYS_FAV hold {k}m: in sample n={int((~oos).sum())} {100*pnl[~oos].mean():+.2f}c | out of sample n={int(oos.sum())} {100*pnl[oos].mean():+.2f}c")
