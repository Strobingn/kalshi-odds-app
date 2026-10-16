# Open 50 AI: deterministic GBM (depth 3, numpy) predicting side bid move over next 60 s. Trained once on recorded BTC books.
exec(open("btcore.py").read())
MEDB=2.654e-5
def ret(c,absec,y):
    t0,a=S[c]; i=absec-t0
    if i-y<0 or i>=len(a): return 0.0
    return math.log(a[i]/a[i-y])*1e4
X=[];Y=[];G=[]
for d in days:
  for mk in M[d]:
    if mk["coin"]!="BTC": continue
    t0=S["BTC"][0]; ts=mk["ts"]; n=len(ts); m0=None
    for i in range(n):
        el=900-mk["tau"][i]
        if el<0: continue
        mid=(mk["ya"][i]+mk["yb"][i])/2
        if m0 is None: m0=mid
        if el<5 or el>240 or (i%3): continue
        j=int(np.searchsorted(ts,ts[i]+60000)); j=min(j,n-1)
        if (ts[j]-ts[i])<30000: continue
        ab=int(ts[i]//1000); sec=mk["sec"][i]
        sg=SIG["BTC"][sec]/MEDB; bs=math.log(mk["s"][i]/strike[mk["tk"]])
        tq=mk["ybq"][i]+mk["yaq"][i]; im=(mk["ybq"][i]-mk["yaq"][i])/tq if tq>0 else 0
        for sd,g in (("YES",1),("NO",-1)):
            ask,_,bid,_=side_px(mk,i,sd); bid2=side_px(mk,j,sd)[2]
            if not(0.05<=ask<=0.95): continue
            X.append([g*ret("BTC",ab,1),g*ret("BTC",ab,5),g*ret("BTC",ab,15),g*ret("BTC",ab,60),sg,g*bs*1e4,g*ret("ETH",ab,15),g*ret("SOL",ab,15),g*im,ask,ask-bid,g*(mid-m0),el])
            Y.append(bid2-bid); G.append(d)
X=np.array(X);Y=np.array(Y);G=np.array(G); tr=np.isin(G,TRAIN); print("samples",len(Y),tr.sum(),flush=True)
EDG=[np.unique(np.quantile(X[tr][:,f],np.linspace(0.05,0.95,15))) for f in range(X.shape[1])]
def fit_tree(Xb,r,depth):
    def node(idx,dp):
        v=float(r[idx].mean())
        if dp==0 or len(idx)<200: return ("L",round(v,5))
        best=None; tot=r[idx].sum(); nn=len(idx)
        for f in range(Xb.shape[1]):
            for t in EDG[f]:
                m=Xb[idx,f]<=t; nl=m.sum()
                if nl<100 or nn-nl<100: continue
                sl=r[idx][m].sum(); gain=sl*sl/nl+(tot-sl)**2/(nn-nl)
                if best is None or gain>best[0]+1e-12: best=(gain,f,float(t),m)
        if best is None: return ("L",round(v,5))
        _,f,t,m=best
        return ("S",f,round(t,6),node(idx[m],dp-1),node(idx[~m],dp-1))
    return node(np.arange(len(r)),depth)
def pt(t,x):
    while t[0]=="S": t=t[3] if x[t[1]]<=t[2] else t[4]
    return t[1]
LR=0.1; trees=[]; base=float(Y[tr].mean()); F=np.full(len(Y),base)
Xt=X[tr]; Yt=Y[tr]
rng=np.random.default_rng(0); sub=rng.choice(len(Yt),min(60000,len(Yt)),replace=False); sub.sort()
Xs=Xt[sub]; Ys=Yt[sub]; Fs=np.full(len(Ys),base)
for k in range(60):
    t=fit_tree(Xs,Ys-Fs,3); t=( lambda tt: tt)(t); trees.append(t)
    Fs+=LR*np.array([pt(t,x) for x in Xs])
P=base+LR*np.array([[pt(t,x) for t in trees] for x in X]).sum(1)
te=~tr
def fee1(p): return math.ceil(0.07*10*p*(1-p)*100-1e-9)/1000
net=Y-X[:,10]-np.array([2*fee1(a) for a in X[:,9]])
predn=P-X[:,10]-np.array([2*fee1(a) for a in X[:,9]])
best=None
for th in np.arange(0,0.06,0.005):
    m=tr&(predn>th)&(X[:,12]<=30)
    if m.sum()>=30:
        v=net[m].mean()
        if best is None or v>best[0]: best=(v,th)
th=best[1] if best else 0.03
m=te&(predn>th)&(X[:,12]<=30); print("thr",th,"test n",m.sum(),"mean",net[m].mean() if m.sum() else None)
def ser(t): return {"l":t[1]} if t[0]=="L" else {"f":t[1],"t":t[2],"a":ser(t[3]),"b":ser(t[4])}
json.dump(dict(version="o50ai-v1",base=base,lr=LR,threshold=round(float(th),4),trees=[ser(t) for t in trees]),open("o50ai_model.json","w"))
