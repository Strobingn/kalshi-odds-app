import numpy as np, pandas as pd, glob
from scipy.stats import norm
mk=pd.read_parquet('prep/markets.parquet').set_index('tk')
ob=pd.concat([pd.read_parquet(f) for f in sorted(glob.glob('prep/ob-*.parquet'))]).sort_values(['tk','ts']).reset_index(drop=True)
ob=ob[ob.tk.isin(mk.index)]
ob['coin']=ob.tk.str[2:5]
ob['close']=ob.tk.map(mk['close']); ob['open']=ob.tk.map(mk['open']); ob['strike']=ob.tk.map(mk['strike']); ob['result']=ob.tk.map(mk['result'])
ob['tau']=(ob.close-ob.ts)/1000.0
ob=ob[(ob.tau>0)&(ob.ts>=ob.open)]
spot={c:pd.read_parquet(f'prep/spot_{c}.parquet') for c in ['BTC','ETH','SOL']}
out=[]
for c,g in ob.groupby('coin'):
    sp=spot[c]; t0=sp.t.values[0]; px=sp.px.values; n=len(px)
    lr=np.diff(np.log(px),prepend=np.log(px[0]))
    # trailing 900s realized vol per sqrt(sec), known at second s (uses returns up to s)
    cs=np.cumsum(lr**2); w=900
    rv=np.sqrt((cs-np.concatenate([np.zeros(w),cs[:-w]]))/w)
    cpx=np.cumsum(px)
    g=g.copy()
    s=(g.ts.values//1000)-1 - t0   # last fully-closed 1s bar before decision time
    ok=(s>=w)&(s<n); g=g[ok]; s=s[ok]
    S=px[s]; sig=np.maximum(rv[s],1e-6)
    o=(g.open.values//1000)-t0
    Kb=(cpx[o-1]-cpx[o-61])/60.0     # binance avg of 60s before open (proxy strike, removes USDT basis)
    tau=g.tau.values
    # final value = avg of last 60s before close
    te=np.where(tau>=60,tau-40.0,tau/3.0*(tau/60.0)**2)  # effective variance horizon (sec)
    c_=(g.close.values//1000)-t0
    known=np.zeros(len(g))
    m=tau<60
    if m.any():
        st=c_[m]-60; k=np.clip((60-tau[m]).astype(int),0,60)
        kn=np.where(k>0,(cpx[np.clip(st+k-1,0,n-1)]-cpx[st-1])/np.maximum(k,1),0)
        known[m]=kn
    mean=np.where(m,((60-tau)*known+tau*S)/60.0,S)
    z=np.log(mean/Kb)/(sig*np.sqrt(np.maximum(te,1e-3)))
    g['S']=S;g['Kb']=Kb;g['sig']=sig;g['z']=z;g['pfair']=norm.cdf(z)*1000
    out.append(g)
P=pd.concat(out).sort_values(['tk','ts']).reset_index(drop=True)
P['mid']=(P.yb+P.ya)/2; P['spr']=P.ya-P.yb
P.to_parquet('prep/panel.parquet'); print(len(P), P.tk.nunique())
print(P[['tau','yb','ya','spr','sig','z','pfair','mid']].describe().T)
