# 0.3.59 new paper scalpers, all at 2 s entry delay (bt59 core: delayed taker fill, walking exits, fees). Train first 10 days / test last 6.
exec(open("bt59core.py").read())
from datetime import datetime,timezone
G=lambda **kw:[dict(zip(kw,v)) for v in itertools.product(*kw.values())]
def latefav2(mk,p):
    h=p.get("hours")
    if h:
        hr=datetime.fromtimestamp(mk["ts"][0]/1000,timezone.utc).hour
        if (h=="us")!=(13<=hr<21): return []
    return latefav(mk,p)
# cross-coin: window index by close
WIN={}
for d in days:
    for mk in M[d]: WIN.setdefault(mk["tk"].split("-")[1],{})[mk["coin"]]=mk
T0={c:S[c][0] for c in S}
def mv(c,sec_abs,Y):  # bp move of coin c over Y s ending at absolute sec
    t0,sp=S[c]; i=sec_abs-t0
    if i-Y<0 or i>=len(sp): return None
    return math.log(sp[i]/sp[i-Y])*1e4
def laggard(mk,p):
    out=[]; n=len(mk["ts"]); i=0; c=mk["coin"]; others=[o for o in ("BTC","ETH","SOL") if o!=c]; t0=S[c][0]
    while i<n:
        if 60<=mk["tau"][i]<=870:
            sa=mk["sec"][i]+t0; ro=[mv(o,sa,p["Y"]) for o in others]; rc=mv(c,sa,p["Y"])
            if None not in ro and rc is not None and all(abs(r)>=p["X"] for r in ro) and ro[0]*ro[1]>0 and abs(rc)<p["X"]/2:
                side="YES" if ro[0]>0 else "NO"; ask=side_px(mk,i,side)[0]
                if 0.10<=ask<=0.90:
                    j=fill_at(mk,i)
                    if j is not None:
                        e=side_px(mk,j,side)[0]
                        pn,k=taker2(mk,i,side,lambda k,side=side,e=e: side_px(mk,k,side)[2]>=e+p["tp"],p["mh"],False)
                        if pn is not None: out.append(pn); i=k; continue
        i+=1
    return out
def fade_maker(mk,p):
    """after a shock, rest a bid on the side that just got dumped (overshoot), placed 2 s later at its bid; filled only if its ask
    trades THROUGH (<= px-1c) within ttl; exit resting ask px+tp (filled when bid >= tgt+1c, maker fee 0) else taker walk at mh / tau<45."""
    out=[]; n=len(mk["ts"]); i=0; sp=mk["sp"]
    while i<n:
        if 120<=mk["tau"][i]<=870:
            sec=mk["sec"][i]; r=math.log(sp[sec]/sp[sec-5])*1e4
            if abs(r)>=p["X"]:
                side="NO" if r>0 else "YES"   # fade: buy the side that fell
                j=fill_at(mk,i)
                if j is None: break
                _,_,bid,_=side_px(mk,j,side); px=round(bid,2)
                if 0.05<=px<=0.95:
                    k=j+1; filled=None
                    while k<n and (mk["ts"][k]-mk["ts"][j])/1000<=p["ttl"] and mk["tau"][k]>60:
                        if side_px(mk,k,side)[0]<=px-0.01+1e-9: filled=k; break
                        k+=1
                    if filled is None: i=k; continue
                    tgt=px+p["tp"]; k=filled+1; pn=None
                    while k<n:
                        _,_,b2,_=side_px(mk,k,side)
                        if b2>=tgt+0.01-1e-9: pn=C*(tgt-px); break
                        if mk["tau"][k]<45 or (mk["ts"][k]-mk["ts"][filled])/1000>=p["mh"]:
                            v,k=sell_walk(mk,k,side,C); pn=v-C*px; break
                        k+=1
                    if pn is None: pn=C*(settle_val(mk,side)-px); k=n
                    out.append(pn); i=k; continue
        i+=1
    return out
def imbalance(mk,p):
    out=[]; n=len(mk["ts"]); i=0
    while i<n:
        if 120<=mk["tau"][i]<=870:
            yb,ybq,ya,yaq=mk["yb"][i],mk["ybq"][i],mk["ya"][i],mk["yaq"][i]; tot=ybq+yaq
            if tot>0 and ya-yb<=0.02:
                im=ybq/tot   # heavy YES bid (vs NO bid size behind YES ask) → YES up
                side="YES" if im>=p["r"] else ("NO" if im<=1-p["r"] else None)
                if side and 0.10<=side_px(mk,i,side)[0]<=0.90:
                    pn,k=taker2(mk,i,side,lambda k: False,p["mh"],False)
                    if pn is not None: out.append(pn); i=k; continue
        i+=1
    return out
V=collections.OrderedDict()
V["sf21 Late fav ≥97% fair"]=(latefav2,G(fmin=[0.97],gap=[0.02,0.03],amax=[0.97]))
V["sf22 Late fav strict ≤40s"]=(latefav2,G(fmin=[0.95],gap=[0.03,0.05],tmax=[40],amax=[0.95]))
V["sf23 Late fav strict 10–30s"]=(latefav2,G(fmin=[0.95],gap=[0.03,0.05],tmin=[10],tmax=[30],amax=[0.95]))
V["sf24 Late fav strict 60s-avg fair"]=(latefav2,G(brti=[True],fmin=[0.95],gap=[0.03,0.05],amax=[0.95]))
V["sf25 Late fav strict BTC"]=(latefav2,G(coins=[("BTC",)],fmin=[0.95],gap=[0.03,0.05],amax=[0.95]))
V["sf26 Late fav strict ETH+SOL"]=(latefav2,G(coins=[("ETH","SOL")],fmin=[0.95],gap=[0.03,0.05],amax=[0.95]))
V["sf27 Cross-coin laggard"]=(laggard,G(X=[5,8],Y=[5,10],mh=[30,60],tp=[0.02,0.04]))
V["sf28 Overshoot fade (maker)"]=(fade_maker,G(X=[8,12],ttl=[20,60],tp=[0.02,0.04],mh=[60,120]))
V["sf29 Final-60s average edge"]=(latefav2,G(brti=[True],fmin=[0.6,0.75],gap=[0.05,0.08],tmin=[5],amax=[0.95]))
V["sf30 Book imbalance"]=(imbalance,G(r=[0.75,0.85],mh=[30,90]))
V["sf31 Late fav strict by hours"]=(latefav2,G(hours=["us","nonus"],fmin=[0.95],gap=[0.05],amax=[0.95]))
rep={}
for name,(fn,grid) in V.items():
    best=None
    for p in grid:
        per=run(fn,p,TRAIN); n=sum(len(x) for x in per); tot=sum(sum(x) for x in per)
        sc=tot if n>=20 else -1e9
        if best is None or sc>best[0]: best=(sc,p,n,tot)
    oos=ci(run(fn,best[1],TEST))
    rep[name]=dict(params={k:(list(v) if isinstance(v,tuple) else v) for k,v in best[1].items()},train_n=best[2],train_net=round(float(best[3]),2),oos_n=oos[0],oos_net=oos[1],oos_per_trade=oos[2],ci_lo=oos[3],ci_hi=oos[4])
    print(name,rep[name],flush=True)
json.dump(dict(delay_s=2,train=TRAIN,test=TEST,rep=rep),open("report60_btc.json","w"),indent=1)
