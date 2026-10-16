# 0.3.40 long-history scalp walk-forward (Dec 2025 -> Sep 2026) on 1-minute Kalshi candles + Coinbase 1m spot.
# Conservative fills (calibrated on the Sep 25+ 3s-book overlap, calib_long.py): decide on bar-close quotes,
# fill on the next bar: buy at max(ask_close, next ask_open) + 0.5c, sell at min(bid_close, next bid_open) - 0.5c.
# 10 contracts, displayed depth unknown (assumed available — noted as a limitation). Taker fee ceil(0.07*C*P*(1-P)) cents both legs.
import pandas as pd, numpy as np, math, itertools
from numba import njit
M=pd.read_parquet('prep/long_markets.parquet'); C=pd.read_parquet('prep/long_candles.parquet'); S=pd.read_parquet('prep/long_spot.parquet')
M['coin']=np.where(M.series.str.contains('SOL'),'SOL',np.where(M.series.str.contains('ETH'),'ETH','BTC'))
C=C.merge(M[['id','ticker','coin','close_ts','floor_strike','result']],left_on='market_id',right_on='id').sort_values(['market_id','end_ts'])
C['tau']=C.close_ts-C.end_ts
C=C[(C.tau>0)&(C.tau<=900)]
# spot close for the bar ending at end_ts (Coinbase bar starting end_ts-60), trailing 60-bar sigma per sqrt(s)
S=S.sort_values(['coin','t']); S['r']=np.log(S.cl).groupby(S.coin).diff()
S['sig']=np.sqrt((S.r**2).groupby(S.coin).transform(lambda x: x.rolling(60,min_periods=30).mean())/60.0)
S['end_ts']=S.t+60
C=C.merge(S[['coin','end_ts','cl','sig']],on=['coin','end_ts'],how='inner')
C=C.sort_values(['market_id','end_ts']).reset_index(drop=True)
from scipy.stats import norm
te=np.where(C.tau>=60,C.tau-40,C.tau/3.0)
z=np.log(C.cl/C.floor_strike)/(C.sig*np.sqrt(te))
C['fair']=norm.cdf(z)*1000
C=C.dropna(subset=['fair','yes_bid_close','yes_ask_close'])
g=C.groupby('market_id')
C['bo_n']=g.yes_bid_open.shift(-1); C['ao_n']=g.yes_ask_open.shift(-1); C['t_n']=g.end_ts.shift(-1)
for k in ['yes_bid_close','yes_ask_close','bo_n','ao_n']: C[k]=C[k]*1000
C['res']=(C.result=='yes').astype(float)
mk,idx=np.unique(C.market_id.values,return_inverse=True)
bounds=np.r_[0,np.flatnonzero(np.diff(idx))+1,len(C)].astype(np.int64)
A=[C[k].values.astype(np.float64) for k in ['yes_bid_close','yes_ask_close','bo_n','ao_n','tau','fair','res']]
A.append((C.t_n-C.end_ts).fillna(9999).values.astype(np.float64))
print('bars',len(C),'markets',len(mk))

@njit(cache=False)
def fee(p,Cn): return math.ceil(7.0*Cn*(p/1000.0)*(1.0-p/1000.0)-1e-9)*10.0/Cn
@njit(cache=False)
def run(bounds,yb,ya,bon,aon,tau,pf,res,dtn,X,TP,SL,FS,tlo,thi,SLIP,ADV,Cn,maxent,tstop):
    out=np.zeros((400000,5)); k=0
    for mi in range(len(bounds)-1):
        s,e=bounds[mi],bounds[mi+1]; i=s; ent=0
        while i<e-1 and ent<maxent:
            sp=ya[i]-yb[i]
            if yb[i]<=0 or ya[i]>=1000 or sp>20 or tau[i]<tlo or tau[i]>thi or dtn[i]>60: i+=1; continue
            side=-1
            fy=min(max(pf[i],10.0),990.0)
            if pf[i]-ya[i]-fee(ya[i],Cn)>=X and pf[i]-ya[i]>sp+fee(ya[i],Cn)+fee(fy,Cn): side=1
            elif yb[i]-pf[i]-fee(1000-yb[i],Cn)>=X and yb[i]-pf[i]>sp+fee(1000-yb[i],Cn)+fee(1000-fy,Cn): side=0
            if side<0: i+=1; continue
            if side==1: a=max(ya[i],aon[i])+ADV; lim=ya[i]+SLIP
            else: a=1000-min(yb[i],bon[i])+ADV; lim=1000-yb[i]+SLIP
            if a>lim or a<100 or a>900 or math.isnan(a): i+=1; continue
            ent+=1; fin=fee(a,Cn); j=i+1; xp=-1.0; jx=e-1
            m=j
            while m<e:
                bid= yb[m] if side==1 else 1000-ya[m]
                fair= pf[m] if side==1 else 1000-pf[m]
                ex=False
                if tau[m]<=tstop: ex=True
                elif bid>0 and bid-a>=TP: ex=True
                elif bid>0 and bid<=a-SL: ex=True
                elif bid>0 and bid-fee(bid,Cn)>=fair: ex=True
                elif fair<=a-FS: ex=True
                if ex and m<e-1 and dtn[m]<=60:
                    nb = bon[m] if side==1 else 1000-aon[m]
                    px=min(bid,nb)-ADV
                    if px>0 and not math.isnan(px): xp=px; jx=m+1; break
                m+=1
            if xp<0:
                xp=1000.0 if (res[s]==1.0)==(side==1) else 0.0; fo=0.0; jx=e
            else: fo=fee(xp,Cn)
            out[k,0]=mi; out[k,1]=xp-a-fin-fo; out[k,2]=tau[i]; out[k,3]=a; out[k,4]=jx-i
            k+=1
            if k>=out.shape[0]: return out[:k]
            i=jx+1  # re-entry after exit (>= 30 s cooldown at 1-minute resolution)
    return out[:k]

grid=list(itertools.product((40,60,100,140),((30,30,60),(40,40,60),(80,60,100),(120,80,150)),(180,300,480)))
mkt=C.groupby('market_id').agg(coin=('coin','first'),close_ts=('close_ts','first')).loc[mk].reset_index()
rows=[]
for X,(TP,SL,FS),tlo in grid:
    r=run(bounds,*A,float(X),float(TP),float(SL),float(FS),float(tlo),840.0,10.0,5.0,10,8,60.0)
    d=pd.DataFrame(r,columns=['mi','net','tau','entry','bars']); d['vid']=f"g{X//10:02d}-t{TP//10:02d}-s{SL//10:02d}-d{FS//10:02d}-w{tlo:03d}"
    rows.append(d)
D=pd.concat(rows); D['coin']=mkt.coin.values[D.mi.astype(int)]; D['close_ts']=mkt.close_ts.values[D.mi.astype(int)]
D['netc']=D.net/10.0
D['week']=pd.to_datetime(D.close_ts,unit='s').dt.tz_localize('UTC').dt.tz_convert('America/New_York').dt.to_period('W-SUN').dt.start_time
D.to_parquet('prep/long_trades.parquet')
print('variants',len(grid),'trials',len(grid)*3,'trades',len(D))
