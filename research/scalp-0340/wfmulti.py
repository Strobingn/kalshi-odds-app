# Rolling weekly walk-forward per strategy (dip / momo / xrev): each week from Jan 5 2026, per coin pick the variant with the
# best mean net/contract on ALL prior data (n >= 200), score it on that week. Net after both fees, cents/contract.
import pandas as pd, numpy as np
D=pd.read_parquet('prep/multi_trades.parquet')
weeks=sorted(w for w in D.week.unique() if w>=pd.Timestamp('2026-01-05'))
def ci(df):
    g=df.groupby('close_ts').netc.agg(['sum','count']); rng=np.random.default_rng(0); n=len(g); b=[]
    for _ in range(1000):
        s=g.iloc[rng.integers(0,n,n)]; b.append(s['sum'].sum()/s['count'].sum())
    return np.percentile(b,[2.5,97.5])
out=[]
for st in ('dip','momo','xrev'):
    E=D[D.strat==st]; oos=[]; picks=[]
    stats_by_week={}
    for c in ('BTC','ETH','SOL'):
        Ec=E[E.coin==c]
        agg=Ec.groupby(['week','vid']).netc.agg(['sum','count']).reset_index()
        for w in weeks:
            pr=agg[agg.week<w].groupby('vid')[['sum','count']].sum(); pr=pr[pr['count']>=200]
            if pr.empty: continue
            m=pr['sum']/pr['count']; best=m.idxmax()
            picks.append((w,c,best,m[best]))
            oos.append(Ec[(Ec.week==w)&(Ec.vid==best)])
    O=pd.concat(oos); W=O.groupby('week').netc.mean()
    lo,hi=ci(O)
    print(f"{st}: weeks {len(W)} positive {(W>0).sum()} ({(W>0).mean():.2f}) pooled OOS {O.netc.mean():.2f} c/ct CI [{lo:.2f},{hi:.2f}] n={len(O)} win {(O.netc>0).mean()*100:.0f}%")
    for c in ('BTC','ETH','SOL'):
        o=O[O.coin==c]; l,h=ci(o); wk=o.groupby('week').netc.mean()
        print(f"   {c} OOS {o.netc.mean():.2f} CI [{l:.2f},{h:.2f}] n={len(o)} positive weeks {(wk>0).sum()}/{len(wk)}")
    P=pd.DataFrame(picks,columns=['week','coin','pick','prior'])
    fin=P.sort_values('week').groupby('coin').tail(1); print(fin.to_string(index=False))
    pooled=E.groupby('vid').netc.mean().sort_values(ascending=False); print('   pooled best:',pooled.head(3).round(2).to_dict())
    W.rename(st).to_csv(f'wfmulti_{st}_weeks.csv')
