import numpy as np, pandas as pd
P=pd.read_parquet('prep/panel.parquet')
P=P[(P.result>=0)].copy()
cut=pd.Timestamp('2026-10-02',tz='UTC').value//10**6
P['train']=P.close<=cut
P['gap']=P.pfair-P.mid
P=P.sort_values(['tk','ts']).reset_index(drop=True)
# future values via per-market asof
def fut(col,h):
    res=np.full(len(P),np.nan)
    for tk,idx in P.groupby('tk').indices.items():
        t=P.ts.values[idx]; v=P[col].values[idx]
        j=np.searchsorted(t,t+h*1000)
        ok=j<len(t)
        r=np.full(len(idx),np.nan); r[ok]=v[j[ok]]; res[idx]=r
    return res
def past(col,h):
    res=np.full(len(P),np.nan)
    for tk,idx in P.groupby('tk').indices.items():
        t=P.ts.values[idx]; v=P[col].values[idx]
        j=np.searchsorted(t,t-h*1000,side='right')-1
        ok=j>=0
        r=np.full(len(idx),np.nan); r[ok]=v[j[ok]]; res[idx]=r
    return res
for h in [15,30,60,120]:
    P[f'dm{h}']=fut('mid',h)-P.mid
for y in [9,15,30,60]:
    P[f'pm{y}']=P.mid-past('mid',y)
P['dpf15']=P.pfair-past('pfair',15)
P.to_parquet('prep/panel2.parquet')
tr=P[P.train&(P.ya<1000)&(P.yb>0)&(P.mid>100)&(P.mid<900)]
print('rows',len(tr))
import numpy.linalg as la
for h in [15,30,60,120]:
    d=tr.dropna(subset=[f'dm{h}','pm15','gap'])
    X=np.c_[np.ones(len(d)),d.gap.clip(-300,300),d.pm15,d.dpf15.fillna(0)]
    b=la.lstsq(X,d[f'dm{h}'].values,rcond=None)[0]
    print(f'h={h}s  E[dmid]= {b[0]:.2f} + {b[1]:.3f}*gap + {b[2]:.3f}*pastmove15s + {b[3]:.3f}*fairmove15s   sd(dmid)={d[f"dm{h}"].std():.1f} mills')
# binned
d=tr.dropna(subset=['dm30','pm15'])
d['gb']=pd.cut(d.gap,[-1000,-100,-50,-30,-15,0,15,30,50,100,1000])
print(d.groupby('gb',observed=True).agg(n=('dm30','size'),dm30=('dm30','mean'),dm60=('dm60','mean'),dm120=('dm120','mean'),spr=('spr','median')).round(1))
d['db']=pd.cut(d.pm15,[-1000,-80,-50,-30,-15,-5,5,15,30,50,80,1000])
print(d.groupby('db',observed=True).agg(n=('dm30','size'),dm15=('dm15','mean'),dm30=('dm30','mean'),dm60=('dm60','mean'),spr=('spr','median')).round(1))
