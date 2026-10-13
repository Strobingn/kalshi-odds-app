# 0.3.57 sf-family backtest: 20 deterministic variants on spot-shock lag + late favourite. Walk-forward: grid chosen on TRAIN (first 10 days) by train net (n>=20), scored on TEST (last 6). Taker fills at displayed book, depth-capped, fees both legs.
exec(open("btcore.py").read())
import collections
# per-coin median sigma on train seconds (regime filter constant, shipped into the app)
MED={}
for c in SIG:
    v=[]
    for d in TRAIN:
        for mk in M[d]:
            if mk["coin"]==c: v.extend(SIG[c][mk["sec"]][::10])
    MED[c]=float(np.median(v))
print("median sigma/s",MED,flush=True)
def fair_side(mk,i,side,f=None):
    f=mk["fair"][i] if f is None else f
    return f if side=="YES" else 1-f
def brti_fair(mk,i):
    """P(YES) when settlement = mean of the final 60 s of spot: observed part + Gaussian remainder."""
    tau=mk["tau"][i]; K=None
    sp=mk["sp"]; sec=mk["sec"][i]; b=mk["b"]; S=sp[sec]*math.exp(-b); sg=SIG[mk["coin"]][sec]
    Kv=mk["K"]
    if tau>=60:
        mean=S; sd=S*sg*math.sqrt((tau-60)+20.0)
    else:
        m=int(round(60-tau)); obs=sp[sec-m+1:sec+1]*math.exp(-b) if m>0 else np.array([])
        r=60-len(obs); mean=(obs.sum()+r*S)/60.0; sd=S*sg*math.sqrt(max(r,1e-9)/3.0)*r/60.0
    if sd<=1e-12: return 1.0 if mean>Kv else 0.0
    return 0.5*(1+erf((mean-Kv)/sd/math.sqrt(2)))
for d in days:
    for mk in M[d]: mk["K"]=strike[mk["tk"]]
def shock(mk,p):
    out=[]; n=len(mk["ts"]); i=0; sp=mk["sp"]; X=p.get("X",5); Y=p.get("Y",5); lag=p.get("lag",0.05); mh=p.get("mh",90)
    conv=p.get("conv",0.01); tlo,thi=p.get("tw",(60,870)); coins=p.get("coins"); sig=SIG[mk["coin"]]
    if coins and mk["coin"] not in coins: return out
    while i<n:
        t=mk["tau"][i]
        if tlo<=t<=thi:
            sec=mk["sec"][i]; r=math.log(sp[sec]/sp[sec-Y])*1e4
            ok=abs(r)>=X
            if ok and "vlo" in p: ok=sig[sec]/MED[mk["coin"]]<=p["vlo"]
            if ok and "vhi" in p: ok=sig[sec]/MED[mk["coin"]]>=p["vhi"]
            if ok and p.get("momo"): r30=math.log(sp[sec-Y]/sp[sec-30])*1e4; ok=r30*r>0 and abs(r30)>=p["momo"]
            if ok and p.get("multi"):
                W=p["multi"]; cnt=0
                for s2 in range(sec-W,sec):
                    r2=math.log(sp[s2]/sp[s2-Y])*1e4
                    if abs(r2)>=X and r2*r>0: cnt+=1
                ok=cnt>=Y+1   # an earlier, distinct shock (more than the current Y-second run)
            if ok:
                side="YES" if r>0 else "NO"
                f=fair_side(mk,i,side); ask=side_px(mk,i,side)[0]
                if f-ask>=lag and 0.05<=ask<=0.95:
                    if p.get("settle"): ex=lambda k: False
                    elif p.get("favhold"):
                        ex=lambda k,side=side: (not (mk["tau"][k]<=75 and fair_side(mk,k,side)>=0.9)) and side_px(mk,k,side)[2]>=fair_side(mk,k,side)-conv
                    else: ex=lambda k,side=side: side_px(mk,k,side)[2]>=fair_side(mk,k,side)-conv
                    pn,k=taker2(mk,i,side,ex,mh,p.get("settle") or p.get("favhold"))
                    if pn is not None: out.append(pn); i=k; continue
        i+=1
    return out
DELAY=int(sys.argv[1]) if len(sys.argv)>1 else 0
def fill_at(mk,i):
    """first book tick >= DELAY s after the signal tick (DELAY 0 = signal tick)."""
    if DELAY==0: return i
    j=int(np.searchsorted(mk["ts"],mk["ts"][i]+DELAY*1000))
    return j if j<len(mk["ts"]) and mk["tau"][j]>=1 else None
def sell_walk(mk,k,side,q):
    """sell q from tick k on, depth-capped at each later tick's bid with fee; remainder settles only past the last tick."""
    n=len(mk["ts"]); rem=q; got=0.0
    while rem>0 and k<n:
        _,_,bid,bq=side_px(mk,k,side); s=min(rem,int(bq))
        if s>0 and bid>0: got+=s*bid-fee(s,bid); rem-=s
        k+=1
    return got+rem*settle_val(mk,side),k
def taker2(mk,i,side,ex,mh,hold_ok):
    i=fill_at(mk,i)
    if i is None: return None,len(mk["ts"])
    ask,aq,_,_=side_px(mk,i,side); q=min(C,int(aq))
    if q<=0 or ask>0.99: return None,i+1
    cost=q*ask+fee(q,ask); n=len(mk["ts"]); k=i+1; pay=settle_val(mk,side)
    while k<n:
        held=(mk["ts"][k]-mk["ts"][i])/1000
        if hold_ok and mk["tau"][k]<=75 and fair_side(mk,k,side)>=0.9 and not ex(k): k+=1; continue  # favhold: ride to settle
        if mk["tau"][k]<30 or held>=mh or ex(k): break
        k+=1
    if k>=n: return q*pay-cost,n
    if hold_ok and mk["tau"][k]<30 and fair_side(mk,k,side)>=0.9: return q*pay-cost,n
    v,k2=sell_walk(mk,k,side,q)
    return v-cost,k2
def latefav(mk,p):
    tmin=p.get("tmin",20); tmax=p.get("tmax",60); fmin=p.get("fmin",0.9); gap=p.get("gap",0.05); amax=p.get("amax",0.97)
    coins=p.get("coins")
    if coins and mk["coin"] not in coins: return []
    for i in range(len(mk["ts"])):
        if tmin<=mk["tau"][i]<=tmax:
            fy=brti_fair(mk,i) if p.get("brti") else mk["fair"][i]
            for side in ("YES","NO"):
                f=fy if side=="YES" else 1-fy; ask,aq,_,_=side_px(mk,i,side)
                if f>=fmin and f-ask>=gap and ask<=amax:
                    j=fill_at(mk,i)
                    if j is None: return []
                    ask,aq,_,_=side_px(mk,j,side); q=min(C,int(aq))
                    if q>0 and ask<=0.99: return [q*settle_val(mk,side)-q*ask-fee(q,ask)]
                    return []
    return []
def combo(mk,p): return shock(mk,dict(favhold=True,**p))+latefav(mk,{})
V=collections.OrderedDict()
G=lambda **kw:[dict(zip(kw,v)) for v in itertools.product(*kw.values())]
V["sf01 Shock base (5bp/5s, lag 5¢, 90s)"]=(shock,[dict()])
V["sf02 Shock fast (3s)"]=(shock,G(Y=[3],X=[4,6],lag=[0.04,0.06],mh=[60]))
V["sf03 Shock big move"]=(shock,G(X=[10,15],Y=[10,15],mh=[60,120]))
V["sf04 Shock wide lag"]=(shock,G(lag=[0.08,0.10]))
V["sf05 Shock quick exit"]=(shock,G(mh=[20,30,45]))
V["sf06 Shock long hold"]=(shock,G(mh=[180,300]))
V["sf07 Shock hold to close"]=(shock,G(settle=[True],mh=[900]))
V["sf08 Shock tight catch-up"]=(shock,G(conv=[0.0,0.02,0.03]))
V["sf09 Shock BTC only"]=(shock,G(coins=[("BTC",)],X=[5,8],lag=[0.03,0.05]))
V["sf10 Shock ETH+SOL"]=(shock,G(coins=[("ETH","SOL")],X=[5,8],lag=[0.03,0.05]))
V["sf11 Shock calm regime"]=(shock,G(vlo=[0.8,1.0]))
V["sf12 Shock hot regime"]=(shock,G(vhi=[1.0,1.25]))
V["sf13 Shock + 30s momentum"]=(shock,G(momo=[2,5]))
V["sf14 Multi-shock"]=(shock,G(multi=[30,60]))
V["sf15 Shock mid-window"]=(shock,G(tw=[(180,720)]))
V["sf16 Late fav base (60s, ≥90%, 5¢)"]=(latefav,[dict()])
V["sf17 Late fav BRTI-60s fair"]=(latefav,G(brti=[True],fmin=[0.9,0.95],gap=[0.03,0.05]))
V["sf18 Late fav wide window"]=(latefav,G(tmax=[90,120],fmin=[0.85,0.9],gap=[0.05,0.08]))
V["sf19 Late fav strict"]=(latefav,G(fmin=[0.95],gap=[0.03,0.05],amax=[0.95]))
V["sf20 Combo shock→fav hold + late fav"]=(combo,[dict()])
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
json.dump(dict(train=TRAIN,test=TEST,median_sigma=MED,rep=rep),open(f"report59_d{DELAY}.json","w"),indent=1)
