import numpy as np, pandas as pd, math
from scipy.stats import norm
P=pd.read_parquet('prep/panel2.parquet'); P=P[(P.result>=0)&(P.yb>0)&(P.ya<1000)]
cut=pd.Timestamp('2026-10-02',tz='UTC').value//10**6; end=pd.Timestamp('2026-10-09',tz='UTC').value//10**6
P['set']=np.where(P.close<=cut,'train',np.where(P.close<=end,'test','x')); P=P[P.set!='x']
P['gap']=P.pfair-P.mid
def fee_c(p,C): return math.ceil(7*C*p*(1-p)-1e-9)  # cents per order, p dollars
print('== fee per contract (cents) and round-trip cost = spread + fee_in + fee_out (taker both legs) ==')
rows=[]
for pc in [10,20,30,40,50,60,70,80,90]:
    p=pc/100
    r={'P':pc}
    for C in (1,10,100): r[f'fee/ct C={C}']=fee_c(p,C)/C
    for C in (1,10,100):
        r[f'RT C={C} @1c spr']=round(1+2*fee_c(p,C)/C,2)
    rows.append(r)
print(pd.DataFrame(rows).to_string(index=False))
print('\n== estimated parameters (train / test) ==')
for st,g in P.groupby('set'):
    for c,h in g.groupby('coin'):
        sig=h.sig.median(); print(st,c,f'sigma/sqrt(s)={sig:.2e} (ann {sig*math.sqrt(365*86400):.0%})', 'median spread(c) by tau:',
              {f'{lo}-{hi}':h[(h.tau>=lo)&(h.tau<hi)].spr.median()/10 for lo,hi in [(600,900),(300,600),(120,300),(0,120)]})
# predictability coefficients: beta_h = cov(dmid_h, gap)/var(gap); fair's share delta_h = -cov(dfair_h,gap)/var(gap)
print('\n== how the gap (fair - mid) closes: share from market moving (beta) vs fair moving (delta) ==')
def fut(df,col,h):
    res=np.full(len(df),np.nan)
    for tk,idx in df.groupby('tk').indices.items():
        t=df.ts.values[idx]; v=df[col].values[idx]; j=np.searchsorted(t,t+h*1000); ok=j<len(t)
        r=np.full(len(idx),np.nan); r[ok]=v[j[ok]]; res[idx]=r
    return res
P=P.reset_index(drop=True)
for h in (15,30,60,120,300):
    P[f'dm{h}']=fut(P,'mid',h)-P.mid; P[f'df{h}']=fut(P,'pfair',h)-P.pfair
P['dset']=P.result*1000-P.mid
sub=P[(P.mid>100)&(P.mid<900)&(P.tau>=300)&(P.tau<=840)]
for st,g in sub.groupby('set'):
    out=[]
    for h in (15,30,60,120,300,'settle'):
        y='dset' if h=='settle' else f'dm{h}'
        d=g.dropna(subset=[y]); gg=d.gap.clip(-300,300); vg=gg.var()
        beta=np.cov(d[y],gg)[0,1]/vg
        delta=np.nan if h=='settle' else -np.cov(d[f'df{h}'].fillna(0),gg)[0,1]/vg
        rho=np.nan
        out.append((h,round(beta,3),round(delta,3) if delta==delta else '', round(d[y].abs().median(),1)))
    print(st, pd.DataFrame(out,columns=['h(s)','beta(mkt->fair)','delta(fair->mkt)','median|dmid| mills']).to_string(index=False))
# dip reversion coefficient
for st,g in sub.groupby('set'):
    d=g.dropna(subset=['dm30','pm15']); b=np.cov(d.dm30,d.pm15)[0,1]/d.pm15.var()
    d2=g.dropna(subset=['dm60','pm30']); b2=np.cov(d2.dm60,d2.pm30)[0,1]/d2.pm30.var()
    print(st,f'dip reversion: E[dmid 30s] = {b:.3f} x move15s ; E[dmid 60s] = {b2:.3f} x move30s  (negative = reversion)')
# gap persistence (OU kappa) from 3s AR(1) on the gap
for st,g in sub.groupby('set'):
    g=g.sort_values(['tk','ts']); lag=g.groupby('tk').gap.shift(1); ok=lag.notna()
    rho=np.corrcoef(g.gap[ok].clip(-300,300),lag[ok].clip(-300,300))[0,1]
    print(st,f'gap AR(1) per ~3s tick rho={rho:.4f} -> half-life {math.log(0.5)/math.log(rho)*3:.0f}s')
