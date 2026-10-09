# 0.3.40 multi-strategy scalp research (dip-hunter, momentum-sniper, extreme-reversion) on the long 1m history.
# Same conservative fills as long40.py. Look-back = previous 1-minute bar (app uses 60 s).
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
C['bp']=g.yes_bid_close.shift(1); C['ap']=g.yes_ask_close.shift(1); C['fp']=g.fair.shift(1); C['dtp']=(C.end_ts-g.end_ts.shift(1)).fillna(9999)
C['bo_n']=g.yes_bid_open.shift(-1); C['ao_n']=g.yes_ask_open.shift(-1); C['t_n']=g.end_ts.shift(-1)
for k in ['yes_bid_close','yes_ask_close','bo_n','ao_n','bp','ap']: C[k]=C[k]*1000
C['res']=(C.result=='yes').astype(float)
mk,idx=np.unique(C.market_id.values,return_inverse=True)
bounds=np.r_[0,np.flatnonzero(np.diff(idx))+1,len(C)].astype(np.int64)
A=[C[k].values.astype(np.float64) for k in ['yes_bid_close','yes_ask_close','bo_n','ao_n','tau','fair','res']]
A.append((C.t_n-C.end_ts).fillna(9999).values.astype(np.float64))
for k in ['bp','ap','fp','dtp']: A.append(C[k].fillna(-1).values.astype(np.float64))

print('bars',len(C),'markets',len(mk))
@njit(cache=False)
def fee(p,Cn): return math.ceil(7.0*Cn*(p/1000.0)*(1.0-p/1000.0)-1e-9)*10.0/Cn
@njit(cache=False)
def run(bounds,yb,ya,bon,aon,tau,pf,res,dtn,bp,ap,fp,dtp,ST,X,TP,SL,FS,tlo,thi,SLIP,ADV,Cn,maxent,tstop):
    # ST 0 dip-hunter, 1 momentum-sniper, 2 extreme-reversion. Units: mills (1000 = $1). Same rules as the app.
    out=np.zeros((600000,5)); k=0
    for mi in range(len(bounds)-1):
        s,e=bounds[mi],bounds[mi+1]; i=s; ent=0
        while i<e-1 and ent<maxent:
            sp=ya[i]-yb[i]
            if yb[i]<=0 or ya[i]>=1000 or sp>20 or tau[i]<tlo or tau[i]>thi or dtn[i]>60: i+=1; continue
            side=-1; best=-1e9
            for sd in range(2):
                ask = ya[i] if sd==1 else 1000-yb[i]
                bid = yb[i] if sd==1 else 1000-ya[i]
                fair = pf[i] if sd==1 else 1000-pf[i]
                mv=-1.0
                if ST==2:
                    if ask>=20 and ask<=150 and fair-ask>=X: mv=fair-ask
                else:
                    if dtp[i]>60 or dtp[i]<0 or ap[i]<0 or bp[i]<0: continue
                    pa = ap[i] if sd==1 else 1000-bp[i]
                    pb = bp[i] if sd==1 else 1000-ap[i]
                    pfair = fp[i] if sd==1 else 1000-fp[i]
                    if ask<100 or ask>900: continue
                    if ST==0:
                        if pa-ask>=X and fair>=ask: mv=(pa-ask)*0.5
                    else:
                        if bid-pb>=X and fair-pfair>=X/2 and ask<=fair+20: mv=(bid-pb)*0.5
                if mv<0: continue
                cost=sp+fee(ask,Cn)+fee(min(max(ask+mv,10.0),990.0),Cn)
                if mv>cost and mv-cost>best: best=mv-cost; side=sd
            if side<0: i+=1; continue
            if side==1: a=max(ya[i],aon[i])+ADV; lim=ya[i]+SLIP
            else: a=1000-min(yb[i],bon[i])+ADV; lim=1000-yb[i]+SLIP
            if a>lim or a<15 or a>900 or math.isnan(a): i+=1; continue
            ent+=1; fin=fee(a,Cn); xp=-1.0; jx=e-1
            m=i+1
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
            i=jx+1
    return out[:k]

SPEC={'dip':(0,(50,80,120),((40,40,80),(60,50,100),(80,60,100)),(180,300)),
      'momo':(1,(40,60,90),((40,30,60),(60,40,80),(80,60,100)),(180,300)),
      'xrev':(2,(50,80,120),((50,40,50),(80,50,60),(120,80,100)),(120,300))}
mkt=C.groupby('market_id').agg(coin=('coin','first'),close_ts=('close_ts','first')).loc[mk].reset_index()
rows=[]
for code,(st,xs,exits,ws) in SPEC.items():
    for X in xs:
        for TP,SL,FS in exits:
            for w in ws:
                r=run(bounds,*A,st,float(X),float(TP),float(SL),float(FS),float(w),840.0,10.0,5.0,10,12,60.0)
                d=pd.DataFrame(r,columns=['mi','net','tau','entry','bars'])
                d['strat']=code; d['vid']=f"{code}-g{X//10:02d}-t{TP//10:02d}-s{SL//10:02d}-d{FS//10:02d}-w{w:03d}"
                rows.append(d)
D=pd.concat(rows); D['coin']=mkt.coin.values[D.mi.astype(int)]; D['close_ts']=mkt.close_ts.values[D.mi.astype(int)]
D['netc']=D.net/10.0
D['week']=pd.to_datetime(D.close_ts,unit='s').dt.tz_localize('UTC').dt.tz_convert('America/New_York').dt.to_period('W-SUN').dt.start_time
D.to_parquet('prep/multi_trades.parquet')
print('variants',D.vid.nunique(),'trials',D.vid.nunique()*3,'trades',len(D))
print(D.groupby(['strat','coin']).netc.agg(['mean','count']).round(2))
