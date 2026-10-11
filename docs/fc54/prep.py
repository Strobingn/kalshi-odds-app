import zipfile,glob,numpy as np,gzip,json,pickle
spot={}
for z in sorted(glob.glob("*.zip")):
    c=z[:3]
    with zipfile.ZipFile(z) as f:
        a=np.loadtxt(f.open(f.namelist()[0]),delimiter=",",usecols=(0,4))
    ts=a[:,0]; ts=np.where(ts>1e14,ts/1000,ts)  # µs → ms
    spot.setdefault(c,[]).append((ts.astype(np.int64)//1000,a[:,1]))
S={}
for c,parts in spot.items():
    t=np.concatenate([p[0] for p in parts]); v=np.concatenate([p[1] for p in parts]); o=np.argsort(t)
    t,v=t[o],v[o]; t0=t[0]; arr=np.full(t[-1]-t0+1,np.nan); arr[t-t0]=v
    # forward fill
    idx=np.where(~np.isnan(arr),np.arange(len(arr)),0); np.maximum.accumulate(idx,out=idx); arr=arr[idx]
    S[c]=(int(t0),arr)
strike={}
for f in glob.glob("/workspace/diphunter-recorder/data/markets-*.jsonl.gz"):
    for line in gzip.open(f,"rt"):
        try: r=json.loads(line)
        except: continue
        if r.get("floor_strike") and r.get("ticker"): strike[r["ticker"]]=float(r["floor_strike"])
pickle.dump((S,strike),open("spot.pkl","wb"))
print({c:(S[c][0],len(S[c][1])) for c in S}, len(strike))
