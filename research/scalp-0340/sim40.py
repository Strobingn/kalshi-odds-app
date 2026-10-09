import numpy as np, pandas as pd, itertools, math, sys
from numba import njit
P=pd.read_parquet('prep/panel2.parquet')
P=P[P.result>=0].sort_values(['tk','ts']).reset_index(drop=True)
mk_codes,mk_idx=np.unique(P.tk.values,return_inverse=True)
P['m']=mk_idx
bounds=np.r_[0,np.flatnonzero(np.diff(P.m.values))+1,len(P)].astype(np.int64)
A=dict(t=P.ts.values.astype(np.int64),yb=P.yb.values.astype(np.float64),ya=P.ya.values.astype(np.float64),
 ybq=P.ybq.values.astype(np.float64),yaq=P.yaq.values.astype(np.float64),tau=P.tau.values,
 pf=P.pfair.values,res=P.result.values.astype(np.float64),
 pm9=P.pm9.fillna(0).values,pm15=P.pm15.fillna(0).values,pm30=P.pm30.fillna(0).values)
@njit(cache=True)
def fee(p,C):  # mills per contract, taker, ceil to cent per order
    c=math.ceil(7.0*C*(p/1000.0)*(1.0-p/1000.0)-1e-9)
    return c*10.0/C
@njit(cache=False)
def run(bounds,t,yb,ya,ybq,yaq,tau,pf,res,pmY,fam,X,Smax,tlo,thi,TP,SL,TR,MH,gapexit,settle,C,maxent,slip,tstop,FS,cool):
    out=np.zeros((200000,9)); k=0
    for mi in range(len(bounds)-1):
        s,e=bounds[mi],bounds[mi+1]; ent=0; i=s
        while i<e-1 and ent<maxent:
            # signal at tick i
            sp=ya[i]-yb[i]; side=-1
            if yb[i]<=0 or ya[i]>=1000 or sp>Smax or tau[i]<tlo or tau[i]>thi: i+=1; continue
            if fam==0:   # dip-buy: buy the side that just dropped
                if pmY[i]<=-X: side=1
                elif pmY[i]>=X: side=0
            elif fam==1: # momentum: buy the side that just rose
                if pmY[i]>=X: side=1
                elif pmY[i]<=-X: side=0
            else:        # fair gap: buy side whose ask is X below spot-implied fair (+fee)
                fy=min(max(pf[i],10.0),990.0)
                if pf[i]-ya[i]-fee(ya[i],C)>=X and pf[i]-ya[i]>sp+fee(ya[i],C)+fee(fy,C): side=1
                elif (1000-pf[i])-(1000-yb[i])-fee(1000-yb[i],C)>=X and yb[i]-pf[i]>sp+fee(1000-yb[i],C)+fee(1000-fy,C): side=0
            if side<0: i+=1; continue
            j=i+1
            if t[j]-t[i]>10000: i+=1; continue
            if side==1: a=ya[j]; q=yaq[j]; lim=ya[i]+slip
            else: a=1000-yb[j]; q=ybq[j]; lim=1000-yb[i]+slip
            if a>lim or q<C or a<100 or a>900 or yb[j]<=0 or ya[j]>=1000: i+=1; continue
            ent+=1; fin=fee(a,C); peak=-1.0; jx=-1; reason=0
            # manage
            m=j
            while m<e-1:
                bid = yb[m] if side==1 else 1000-ya[m]
                fair= pf[m] if side==1 else 1000-pf[m]
                if bid>peak: peak=bid
                held=(t[m]-t[j])/1000.0
                ex=0
                if not settle:
                    if TP>0 and bid-a>=TP: ex=1
                    elif SL>0 and a-bid>=SL: ex=2
                    elif TR>0 and peak-bid>=TR and peak>a: ex=3
                    elif MH>0 and held>=MH: ex=4
                    elif tau[m]<=tstop: ex=5
                if gapexit and bid>0 and bid-fee(bid,C)>=fair: ex=6
                if FS>0 and fair<=a-FS: ex=8
                if settle and tau[m]<=tstop and tstop<0: ex=5
                if ex>0:
                    jx=m+1; reason=ex; break
                m+=1
            if jx>0 and jx<e and t[jx]-t[jx-1]<=10000:
                # fill at displayed bid with depth; remainder walks forward
                rem=C; proceeds=0.0; fo=0.0; jj=jx
                while rem>0 and jj<e:
                    bb = yb[jj] if side==1 else 1000-ya[jj]
                    qq = ybq[jj] if side==1 else yaq[jj]
                    if bb>0 and qq>0:
                        f=min(rem,qq); proceeds+=f*bb; fo+=math.ceil(7.0*f*(bb/1000.0)*(1.0-bb/1000.0)-1e-9)*10.0; rem-=f
                    jj+=1
                if rem>0:   # leftover settles
                    v=1000.0 if (res[s]==1)==(side==1) else 0.0
                    proceeds+=rem*v
                xp=proceeds/C; fout=fo/C; hold=(t[min(jj-1,e-1)]-t[j])/1000.0; nxt=jj
            else:
                xp=1000.0 if (res[s]==1)==(side==1) else 0.0; fout=0.0; reason=7; hold=tau[j]; nxt=e
            net=xp-a-fin-fout
            out[k,0]=mi;out[k,1]=j;out[k,2]=side;out[k,3]=a;out[k,4]=xp;out[k,5]=fin+fout;out[k,6]=net;out[k,7]=hold;out[k,8]=reason
            k+=1
            if k>=out.shape[0]: return out[:k]
            i=max(nxt,j+1)
            if cool>0:
                t0=t[min(i,e-1)]
                while i<e-1 and t[i]-t0<cool: i+=1
    return out[:k]
def simulate(fam,Y,X,Smax,tlo,thi,TP,SL,TR,MH,gapexit,settle,C=10,maxent=3,slip=10,tstop=20,FS=0,cool=0):
    r=run(bounds,A['t'],A['yb'],A['ya'],A['ybq'],A['yaq'],A['tau'],A['pf'],A['res'],A[f'pm{Y}'],fam,X,Smax,tlo,thi,TP,SL,TR,MH,gapexit,settle,C,maxent,slip,tstop,FS,cool)
    d=pd.DataFrame(r,columns=['m','j','side','entry','exitp','fees','net','hold','reason'])
    d['tk']=mk_codes[d.m.astype(int)]; d['ts']=A['t'][d.j.astype(int)]; d['tau']=A['tau'][d.j.astype(int)]
    return d
