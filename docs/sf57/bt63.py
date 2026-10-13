exec(open("bt61core.py").read())
from collections import deque
def simple(mk,p):
    X,TP,SL=p["x"],p["tp"],0.15
    n=len(mk["ts"]); out=[]; win={"YES":deque(),"NO":deque()}; sums={"YES":0.0,"NO":0.0}; i=0
    busy_until=-1
    for i in range(n):
        t=mk["ts"][i]
        for sd in ("YES","NO"):
            a=side_px(mk,i,sd)[0]; win[sd].append((t,a)); sums[sd]+=a
            while win[sd][0][0]<t-300000: sums[sd]-=win[sd].popleft()[1]
        if i<=busy_until or mk["tau"][i]<120: continue
        if t-mk["ts"][0]<240000: continue
        for sd in ("YES","NO"):
            a=side_px(mk,i,sd)[0]; avg=sums[sd]/len(win[sd])
            if not (0.05<=a<=0.95) or a>avg-X+1e-9: continue
            j=fill_at(mk,i)
            if j is None: break
            ask,aq,_,_=side_px(mk,j,sd); q=min(10,int(aq))
            if q<=0 or ask>0.95: break
            cost=q*ask+fee(q,ask); val=None
            for k in range(j+1,n):
                b=side_px(mk,k,sd)[2]
                if b>=ask+TP-1e-9 or b<=ask-SL+1e-9 or mk["tau"][k]<60:
                    v,kk=sell_walk(mk,k,sd,q); val=v; busy_until=max(k,kk if isinstance(kk,int) else k); break
            if val is None: val=q*settle_val(mk,sd); busy_until=n
            out.append(val-cost); break
    return out
V={"sp01":dict(x=0.05,tp=0.08),"sp02":dict(x=0.05,tp=0.15),"sp03":dict(x=0.10,tp=0.08),"sp04":dict(x=0.10,tp=0.15)}
res={}
for k,p in V.items():
    tr=run(simple,p,TRAIN); te=run(simple,p,TEST)
    res[k]=dict(p=p,train=(sum(len(x) for x in tr),round(float(sum(sum(x) for x in tr)),2)),test=ci(te))
    print(k,res[k],flush=True)
json.dump(res,open("report63_btc.json","w"),indent=1)
