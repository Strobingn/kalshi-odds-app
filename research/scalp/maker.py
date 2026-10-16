import numpy as np, pandas as pd, glob, itertools, math, time
from numba import njit
from sim import P, A, bounds, mk_codes, fee
code=pd.Series(np.arange(len(mk_codes)),index=mk_codes)
parts=[]
for f in sorted(glob.glob('prep/tr-*.parquet')):
    x=pd.read_parquet(f); x=x[x.tk.isin(code.index)]
    parts.append(pd.DataFrame({'m':code.loc[x.tk.values].values.astype(np.int32),'ts':x.ts.values,'yp':x.yp.values.astype(np.float32),'cnt':x.cnt.values.astype(np.float32),'tyes':x.tyes.values.astype(np.int8)}))
    del x
T=pd.concat(parts); del parts
T=T.sort_values(['m','ts'],kind='stable').reset_index(drop=True)
tb=np.searchsorted(T.m.values,np.arange(len(mk_codes)+1)).astype(np.int64)
tts=T.ts.values.astype(np.int64); typ=T.yp.values.astype(np.float64); tcnt=T.cnt.values; tyes=T.tyes.values.astype(np.int64)
print('trades',len(T))
@njit(cache=True)
def run(bounds,tb,tts,typ,tcnt,tyes,t,yb,ya,ybq,yaq,tau,pf,res,pmY,sig,X,tlo,thi,TTL,D,SL,MH,C,maxent,tstop,Smax):
    out=np.zeros((200000,10)); k=0
    for mi in range(len(bounds)-1):
        s,e=bounds[mi],bounds[mi+1]; ent=0; i=s; ts0=tb[mi]; ts1=tb[mi+1]
        while i<e-2 and ent<maxent:
            side=-1
            if yb[i]>0 and ya[i]<1000 and tau[i]>=tlo and tau[i]<=thi and ya[i]-yb[i]<=Smax:
                if sig==0:
                    if pf[i]-yb[i]>=X: side=1
                    elif (1000-pf[i])-(1000-ya[i])>=X: side=0
                elif sig==1:
                    if pmY[i]<=-X: side=1
                    elif pmY[i]>=X: side=0
                else:
                    if pmY[i]>=X: side=1
                    elif pmY[i]<=-X: side=0
            if side<0: i+=1; continue
            j=i+1  # placement visible at next book (latency >= 1 tick)
            if t[j]-t[i]>10000 or yb[j]<=0 or ya[j]>=1000: i+=1; continue
            if side==1: L=yb[j]; Q=ybq[j]
            else: L=ya[j]; Q=yaq[j]   # NO bid at 1000-L == YES ask level L
            if (side==1 and (L<100 or L>900)) or (side==0 and (1000-L<100 or 1000-L>900)): i+=1; continue
            tp=t[j]; tend=tp+TTL*1000; filled=0.0; cum=0.0; tf=-1
            # entry fill via trades after placement
            for q in range(ts0,ts1):
                if tts[q]<=tp: continue
                if tts[q]>tend: break
                if side==1 and tyes[q]==0:
                    if typ[q]<L: tf=tts[q]; break
                    if typ[q]==L:
                        cum+=tcnt[q]
                        if cum>=Q+C: tf=tts[q]; break
                if side==0 and tyes[q]==1:
                    if typ[q]>L: tf=tts[q]; break
                    if typ[q]==L:
                        cum+=tcnt[q]
                        if cum>=Q+C: tf=tts[q]; break
            if tf<0:
                ent+=0; 
                # advance to after TTL
                m=j
                while m<e and t[m]<=tend: m+=1
                i=max(m,i+1); continue
            ent+=1
            a = L if side==1 else 1000-L     # entry price on our side
            tgt=a+D                           # resting exit (maker, no fee)
            # exit loop: watch trades & books
            m=j
            while m<e and t[m]<tf: m+=1
            xp=-1.0; fout=0.0; reason=0; tx=tf
            qi=ts0
            while qi<ts1 and tts[qi]<=tf: qi+=1
            while m<e:
                # trades between t[m-1] and t[m] that could fill our resting exit
                while qi<ts1 and tts[qi]<=t[m]:
                    if side==1 and tyes[qi]==1 and typ[qi]>tgt: xp=tgt; reason=1; tx=tts[qi]; break
                    if side==0 and tyes[qi]==0 and typ[qi]<1000-tgt: xp=tgt; reason=1; tx=tts[qi]; break
                    qi+=1
                if xp>=0: break
                bid = yb[m] if side==1 else 1000-ya[m]
                held=(t[m]-tf)/1000.0
                if bid>=tgt+1: xp=tgt; reason=1; tx=t[m]; break   # book crossed our ask
                stop=(SL>0 and a-bid>=SL) or (MH>0 and held>=MH) or tau[m]<=tstop
                if stop and m+1<e and t[m+1]-t[m]<=10000:
                    b2 = yb[m+1] if side==1 else 1000-ya[m+1]
                    q2 = ybq[m+1] if side==1 else yaq[m+1]
                    if b2>0 and q2>=C:
                        xp=b2; fout=fee(b2,C); reason=2 if tau[m]>tstop else 5; tx=t[m+1]; break
                m+=1
            if xp<0:
                xp=1000.0 if (res[s]==1)==(side==1) else 0.0; reason=7; tx=t[e-1]
            out[k,0]=mi;out[k,1]=j;out[k,2]=side;out[k,3]=a;out[k,4]=xp;out[k,5]=fout;out[k,6]=xp-a-fout;out[k,7]=(tx-tf)/1000.0;out[k,8]=reason;out[k,9]=tau[j]
            k+=1
            m2=j
            while m2<e and t[m2]<=tx: m2+=1
            i=max(m2,i+1)
    return out[:k]
def msim(sig,Y,X,tlo,thi,TTL,D,SL,MH,C=10,maxent=3,tstop=20,Smax=20):
    r=run(bounds,tb,tts,typ,tcnt,tyes,A['t'],A['yb'],A['ya'],A['ybq'],A['yaq'],A['tau'],A['pf'],A['res'],A[f'pm{Y}'],sig,X,tlo,thi,TTL,D,SL,MH,C,maxent,tstop,Smax)
    d=pd.DataFrame(r,columns=['m','j','side','entry','exitp','fees','net','hold','reason','tau'])
    d['tk']=mk_codes[d.m.astype(int)]
    return d
if __name__=='__main__':
    cut=pd.Timestamp('2026-10-02',tz='UTC').value//10**6; end=pd.Timestamp('2026-10-09',tz='UTC').value//10**6
    close=dict(zip(P.tk,P.close)); rows=[]; t0=time.time()
    combos=[]
    for sig,X in [(0,0),(0,30),(0,60),(0,100),(1,30),(1,60),(2,30),(2,60)]:
        for (tlo,thi),TTL,D,SL,MH in itertools.product([(120,840),(300,840),(60,300)],(15,45),(10,20,40),(0,30,60),(60,180)):
            combos.append((sig,15,X,tlo,thi,TTL,D,SL,MH))
    print('maker combos',len(combos))
    for c in combos:
        d=msim(*c); 
        if len(d)==0: continue
        d['close']=d.tk.map(close); tr=d[d.close<=cut]; te=d[(d.close>cut)&(d.close<=end)]
        rows.append(c+(len(tr),tr.net.mean(),(tr.net>0).mean(),len(te),te.net.mean()))
    R=pd.DataFrame(rows,columns=['sig','Y','X','tlo','thi','TTL','D','SL','MH','ntr','mtr','wtr','nte','mte'])
    R.to_parquet('maker_results.parquet'); print('done',time.time()-t0)
    g=R[R.ntr>=200]; print('train>0:',(g.mtr>0).sum(),'of',len(g),' median',g.mtr.median(),' best',g.mtr.max())
    print(g.sort_values('mtr',ascending=False).head(5)[['sig','X','tlo','thi','TTL','D','SL','MH','ntr','mtr','wtr']].round(2).to_string())
