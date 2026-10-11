"""Finish the app's scalper model after train_compact.py and queue_ml.py:
  1. fit the queue penalty on the first three test days and check it on the other four,
  2. add it to scalper_model.json (copy to app/src/main/assets/),
  3. write scalper_parity.json (copy to app/src/test/resources/): one recorded window's trades with the
     feature rows and model outputs the app must reproduce (ScalperParityTest).
Run in the working folder:  python3 -m export_app_model tape"""
import json, os, sys, random, numpy as np
import build_ml as M, scalp_b as B
TAPE=sys.argv[1] if len(sys.argv)>1 else 'tape'
q=json.load(open('queue_ml_out.json')); pt=np.load('compact_pred_test.npy')[:,0]; meta=json.load(open('compact_meta.json')); theta=meta['theta']; FEATS=meta['feats']
d=np.load('ml_data.npz',allow_pickle=True); Y=d['Y']; mkt=d['mkt'].astype(int); mday=d['mday']; days=sorted(set(mday)); TE=set(days[15:]); day=mday[mkt]
c=np.isin(day,list(TE))&~np.isnan(Y[:,4]); rows=np.where(c)[0]
bym={}
for i,r in enumerate(rows): bym.setdefault(mkt[r],[]).append(i)
order=[i for mi,lst in bym.items() for i in lst]; p=pt[order]
QS=['0','100','250','500','1000','2000','5000']
dd=np.array([x[0] for x in q['0']]); tdays=sorted(set(dd)); A=np.isin(dd,tdays[:3]); Bm=~A
v={Q:np.array([x[1] for x in q[Q]])*100 for Q in QS}
assert float(np.max(np.abs(v['0']/100-Y[rows[order],4].astype(float))))<1e-6, "queue sim and dataset are not aligned"
sel=p>=theta
pen={Q:float(v['0'][A&sel].mean()-v[Q][A&sel].mean()) for Q in QS}
print("queue penalty (cents), fitted on",tdays[:3],":",{k:round(x,3) for k,x in pen.items()})
print("check on",tdays[3:],": every model pick vs only orders with prediction - penalty >= 0")
for Q in QS:
    s1=Bm&sel; s2=Bm&(p-pen[Q]>=0)
    print(f"  {Q:>5s} ahead: every pick {v[Q][s1].mean():+.2f}c n={int(s1.sum())} | queue-aware "+(f"{v[Q][s2].mean():+.2f}c n={int(s2.sum())}" if s2.sum()>50 else f"takes {int(s2.sum())} orders"))
model=json.load(open('scalper_model.json'))
model['queue_penalty']=[[float(k),round(float(x),4)] for k,x in sorted(pen.items(),key=lambda kv:float(kv[0]))]
json.dump(model,open('scalper_model.json','w'),separators=(',',':'))
trees=model['trees']
def ev(x):
    s=0.0
    for t in trees:
        i=0
        while t['f'][i]>=0: i=t['l'][i] if float(np.float32(x[t['f'][i]]))<=t['t'][i] else t['r'][i]
        s+=t['v'][i]
    return s
mk=[m for m in json.load(open(os.path.join(TAPE,'markets.json')))]; mk.sort(key=lambda m:m["open"])
cands=sorted((os.path.getsize(os.path.join(TAPE,'raw',m['ticker']+'.npz')),m['ticker'],m) for m in mk[-600:]); m=cands[len(cands)//10][2]
z=np.load(os.path.join(TAPE,'raw',m['ticker']+'.npz')); el=900.0+z['T']; keep=(el>=0)&(el<420); first=z['T']<-480
g0=B.grid(z['T'][first],z['P'][first],z['C'][first],z['S'][first]); my,mn,cnt,hasy,hasn=M.extras(z['T'][first],z['P'][first],z['C'][first],z['S'][first]); sy=M.since(hasy); sn=M.since(hasn)
M.FEATS=None; out=[]
for side,g,ex,sign,res in (("YES",g0,(my,mn,cnt,sn,sy),1.0,1.0),("NO",B.mirror(g0),(mn,my,cnt,sy,sn),-1.0,0.0)):
    X,Yy,secs=M.frame_rows(g,ex,res,sign,int(m['open']),float(m['strike'])); ix=[M.FEATS.index(f) for f in FEATS]
    for x,s in zip(X,secs):
        if s>400: continue
        xf=np.nan_to_num(x[ix].astype(np.float32),nan=0.0); out.append(dict(side=side,sec=int(s),x=[float(a) for a in xf],pred=ev(xf)))
fx=dict(ticker=m['ticker'],features=FEATS,trades=[[int(np.floor(e*1000)),float(pp),float(cc),int(ss)] for e,pp,cc,ss in zip(el[keep],np.round(z['P'].astype(np.float64),4)[keep],z['C'].astype(np.float64)[keep],z['S'][keep].astype(int))],rows=out)
json.dump(fx,open('scalper_parity.json','w'),separators=(',',':'))
print("parity fixture:",m['ticker'],len(fx['trades']),"trades",len(out),"rows")
