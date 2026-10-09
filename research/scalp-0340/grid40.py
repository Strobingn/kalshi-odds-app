# 0.3.40: re-run the app's scalp variant grid per coin and per ET hour on recorded books.
# Walk-forward: choose on train (<= Oct 1), report on the untouched test block (Oct 2+). Net after BOTH fees.
import pandas as pd, numpy as np, itertools, sys
from sim40 import *
cut=pd.Timestamp('2026-10-02',tz='UTC').value//10**6
close=dict(zip(P.tk,P.close))
def coin(tk): return 'SOL' if 'SOL' in tk else ('ETH' if 'ETH' in tk else 'BTC')
rows=[]; trials=0
grid=list(itertools.product((60,100),((40,40,60),(80,60,100)),(180,300)))
allv=[]
for X,(TP,SL,FS),tlo in grid:
    d=simulate(2,15,X,20,tlo,840,TP,SL,0,0,1,0,maxent=8,tstop=60,FS=FS,cool=30000)
    d['coin']=d.tk.map(coin); d['close']=d.tk.map(close)
    d['hour']=pd.to_datetime(d.ts,unit='ms',utc=True).dt.tz_convert('America/New_York').dt.hour
    d['test']=d.close>cut
    d['vid']=f"g{X//10:02d}-t{TP//10:02d}-s{SL//10:02d}-d{FS//10:02d}-w{tlo:03d}"
    allv.append(d)
D=pd.concat(allv); D['netc']=D.net/10.0  # mills -> cents per contract
trials=len(grid)*3
def ci(x,cl):
    g=pd.DataFrame({'x':x,'c':cl}).groupby('c').x.agg(['sum','count']); rng=np.random.default_rng(0); n=len(g)
    if n<5: return (np.nan,np.nan)
    b=[(lambda s: s['sum'].sum()/s['count'].sum())(g.iloc[rng.integers(0,n,n)]) for _ in range(1000)]
    return tuple(np.percentile(b,[2.5,97.5]))
out=[]
for c in ('BTC','ETH','SOL'):
    tr=D[(D.coin==c)&~D.test].groupby('vid').netc.agg(['mean','count']).sort_values('mean',ascending=False)
    best=tr.index[0]; te=D[(D.coin==c)&D.test&(D.vid==best)]
    lo,hi=ci(te.netc.values,te.close.values)
    out.append(dict(coin=c,best_train=best,train_mean=tr.iloc[0]['mean'],train_n=int(tr.iloc[0]['count']),
        test_mean=te.netc.mean(),test_n=len(te),ci_lo=lo,ci_hi=hi,win=(te.netc>0).mean(),
        rt_per_window=te.groupby('tk').size().mean()))
O=pd.DataFrame(out); print(O.round(2).to_string()); print('trials (variants x coins):',trials)
# per hour for each coin's train-best, test block
H=[]
for r in out:
    te=D[(D.coin==r['coin'])&D.test&(D.vid==r['best_train'])]
    tr=D[(D.coin==r['coin'])&~D.test&(D.vid==r['best_train'])]
    h=pd.DataFrame({'train':tr.groupby('hour').netc.mean(),'train_n':tr.groupby('hour').size(),'test':te.groupby('hour').netc.mean(),'test_n':te.groupby('hour').size()})
    h['coin']=r['coin']; H.append(h.reset_index())
H=pd.concat(H); H.to_csv('hour40.csv',index=False)
# hours positive in train with n>=20 -> do they hold in test?
sel=H[(H.train>0)&(H.train_n>=20)]
print('hours positive in train:',len(sel),' of which positive in test:',int((sel.test>0).sum()),' pooled test mean of those hours (c/ct):',
      round((sel.test*sel.test_n).sum()/max(sel.test_n.sum(),1),2),' n=',int(sel.test_n.sum()))
O.to_csv('oos40.csv',index=False)
