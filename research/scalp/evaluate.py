import numpy as np, pandas as pd
import sim; from sim import simulate, P
import maker
cut=pd.Timestamp('2026-10-02',tz='UTC').value//10**6; end=pd.Timestamp('2026-10-09',tz='UTC').value//10**6
close=dict(zip(P.tk,P.close))
R=pd.read_parquet('grid_results.parquet'); M=pd.read_parquet('maker_results.parquet')
rng=np.random.default_rng(7)
def boot(d,B=4000):
    w=d.close.values; u,inv=np.unique(w,return_inverse=True)
    s=np.bincount(inv,weights=d.net.values); n=np.bincount(inv)
    idx=rng.integers(0,len(u),(B,len(u)))
    m=s[idx].sum(1)/n[idx].sum(1); return np.percentile(m,[2.5,97.5])
def summ(d,label):
    lo,hi=boot(d) if len(d)>20 else (np.nan,np.nan)
    return dict(set=label,n=len(d),windows=d.close.nunique(),win=round((d.net>0).mean(),3),avg_net_c=round(d.net.mean()/10,2),
                ci_lo=round(lo/10,2),ci_hi=round(hi/10,2),total_usd_C10=round(d.net.sum()*10/1000,2),fees_c=round(d.fees.mean()/10,2),hold_s=round(d.hold.median(),0))
picks={}
for f in (0,1,2):
    g=R[(R.fam==f)&(R.ntr>=200)].sort_values('mtr',ascending=False).iloc[0]
    picks[['dip-buy MR','momentum','fair-gap taker (grid)'][f]]=('t',tuple(int(x) for x in g[['fam','Y','X','S','tlo','thi','TP','SL','TR','MH','gapexit','settle']].values),{})
picks['DERIVED fair-gap scalp (locked)']=('t',(2,15,100,20,300,840,0,0,0,0,1,0),{'FS':100})
g=M[M.ntr>=200].sort_values('mtr',ascending=False).iloc[0]
picks['maker both legs (queue-aware)']=('m',tuple(int(x) for x in g[['sig','Y','X','tlo','thi','TTL','D','SL','MH']].values),{})
rows=[]; keep={}
for name,(kind,c,kw) in picks.items():
    d=simulate(*c,**kw) if kind=='t' else maker.msim(*c,**kw)
    d['close']=d.tk.map(close)
    tr=d[d.close<=cut]; te=d[(d.close>cut)&(d.close<=end)]
    for lab,x in (('train',tr),('TEST',te)):
        r=summ(x,lab); r['strategy']=name; r['params']=str(c)+(str(kw) if kw else ''); rows.append(r)
    keep[name]=te
pd.set_option('display.width',300); pd.set_option('display.max_colwidth',60)
S=pd.DataFrame(rows); print(S[['strategy','set','n','windows','win','avg_net_c','ci_lo','ci_hi','total_usd_C10','fees_c','hold_s','params']].to_string(index=False))
te=keep['DERIVED fair-gap scalp (locked)']; te=te.copy()
te['coin']=te.tk.str[2:5]; te['tau_b']=pd.cut(te.tau,[300,450,600,840]); te['px_b']=pd.cut(te.entry,[100,300,500,700,900])
te['reason']=te.reason.map({5:'time stop',6:'gap closed (sell>hold)',8:'fair turned down',7:'settled'})
for col in ('coin','tau_b','px_b','reason'):
    print('\nDERIVED rule, TEST by',col)
    print(te.groupby(col,observed=True).apply(lambda x: pd.Series(summ(x,'TEST'))).drop(columns='set').to_string())
S.to_csv('oos_summary.csv',index=False)
