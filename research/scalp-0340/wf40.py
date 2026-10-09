# Rolling weekly walk-forward: for each week from Jan 5 2026, per coin pick the variant with the best mean net/contract
# on ALL prior data (n >= 200), then score that variant on the week. Net after both fees, cents/contract.
import pandas as pd, numpy as np
D=pd.read_parquet('prep/long_trades.parquet')
weeks=sorted(w for w in D.week.unique() if w>=pd.Timestamp('2026-01-05'))
oos=[]; picks=[]
for w in weeks:
    for c in ('BTC','ETH','SOL'):
        prior=D[(D.coin==c)&(D.week<w)]
        if len(prior)==0: continue
        st=prior.groupby('vid').netc.agg(['mean','count']); st=st[st['count']>=200]
        if st.empty: continue
        best=st['mean'].idxmax()
        t=D[(D.coin==c)&(D.week==w)&(D.vid==best)]
        picks.append((w,c,best,st.loc[best,'mean']))
        oos.append(t.assign(pick=best))
O=pd.concat(oos)
def ci(df):
    g=df.groupby('close_ts').netc.agg(['sum','count']); rng=np.random.default_rng(0); n=len(g)
    b=[]
    for _ in range(2000):
        s=g.iloc[rng.integers(0,n,n)]; b.append(s['sum'].sum()/s['count'].sum())
    return np.percentile(b,[2.5,97.5])
W=O.groupby('week').agg(n=('netc','size'),mean_c=('netc','mean'),net_usd=('net',lambda x: x.sum()/100.0)).reset_index()
W['week']=W.week.dt.strftime('%Y-%m-%d')
pd.set_option('display.width',200)
print(W.round(2).to_string(index=False))
lo,hi=ci(O)
print('weeks',len(W),'positive weeks',int((W.mean_c>0).sum()),'frac %.2f'%((W.mean_c>0).mean()))
print('pooled OOS mean %.2f c/ct  95%% window-clustered CI [%.2f, %.2f]  n=%d  win %.0f%%'%(O.netc.mean(),lo,hi,len(O),100*(O.netc>0).mean()))
for c in ('BTC','ETH','SOL'):
    o=O[O.coin==c]; l,h=ci(o); wk=o.groupby('week').netc.mean()
    print(c,'OOS %.2f c/ct CI [%.2f, %.2f] n=%d  positive weeks %d/%d'%(o.netc.mean(),l,h,len(o),(wk>0).sum(),len(wk)))
P=pd.DataFrame(picks,columns=['week','coin','pick','prior_mean'])
print(P.groupby('coin').pick.agg(lambda x: x.value_counts().head(3).to_dict()))
print('final picks (fit on all data):'); print(P.sort_values('week').groupby('coin').tail(1).to_string(index=False))
# full-sample variant ranking for app variants
app=['g06-t04-s04-d06-w180','g06-t04-s04-d06-w300','g06-t08-s06-d10-w180','g06-t08-s06-d10-w300','g10-t04-s04-d06-w180','g10-t04-s04-d06-w300','g10-t08-s06-d10-w180','g10-t08-s06-d10-w300']
print(D[D.vid.isin(app)].groupby(['coin','vid']).netc.agg(['mean','count']).round(2).sort_values('mean',ascending=False).groupby(level=0).head(2))
W.to_csv('wf40_weeks.csv',index=False); P.to_csv('wf40_picks.csv',index=False)
