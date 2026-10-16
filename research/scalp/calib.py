import numpy as np, pandas as pd
from scipy.stats import norm
P=pd.read_parquet('prep/panel.parquet')
P=P[(P.result>=0)&(P.yb>0)&(P.ya<1000)]
cut=pd.Timestamp('2026-10-02',tz='UTC').value//10**6
P['train']=P.close<=cut
tr=P[P.train]
y=tr.result.values
print('train rows',len(tr),'markets',tr.tk.nunique(),' test markets',P[~P.train].tk.nunique())
print('brier mid', np.mean((tr.mid/1000-y)**2))
for k in [0.6,0.7,0.8,0.9,1.0,1.2]:
    p=norm.cdf(tr.z/k); print('volx',k,'brier',round(np.mean((p-y)**2),5))
for lo,hi in [(600,900),(300,600),(120,300),(30,120),(0,30)]:
    s=tr[(tr.tau>=lo)&(tr.tau<hi)]; yy=s.result.values
    print(f'tau {lo}-{hi}: brier mid {np.mean((s.mid/1000-yy)**2):.4f} fair {np.mean((s.pfair/1000-yy)**2):.4f} spread med {s.spr.median()}')
