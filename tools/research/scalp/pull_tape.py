import json, sys, time, os, threading, random, urllib.request, urllib.error, urllib.parse
from concurrent.futures import ThreadPoolExecutor
import numpy as np
from kx import ts
API="https://api.elections.kalshi.com/trade-api/v2"
def get(url):
    # 429s: short waits first (the limit is per second), growing to 5 s so a throttled runner keeps going
    for a in range(200):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent":"research/1.0","Accept":"application/json"}), timeout=40) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            time.sleep(min(0.05*1.25**a,5.0)+random.random()*0.15 if e.code==429 else min(0.5+a*0.3,10.0))
        except Exception:
            time.sleep(min(0.5+a*0.3,10.0))
    raise RuntimeError(url)
DIR=sys.argv[1]; DAYS=float(sys.argv[2]); W=int(sys.argv[3]) if len(sys.argv)>3 else 8
lo=int(time.time()-DAYS*86400); cur=None; mk=[]
while True:
    d=get(f"{API}/markets?series_ticker=KXBTC15M&status=settled&limit=1000&min_close_ts={lo}"+(f"&cursor={cur}" if cur else ""))
    ms=d.get("markets") or []
    for m in ms:
        if m.get("result") in ("yes","no"):
            mk.append({"ticker":m["ticker"],"open":ts(m["open_time"]),"close":ts(m["close_time"]),"result":m["result"],"strike":m.get("floor_strike"),"exp":m.get("expiration_value")})
    cur=d.get("cursor")
    if not cur or not ms: break
mk.sort(key=lambda m:-m["close"]); json.dump(mk, open(os.path.join(DIR,"markets.json"),"w")); print(len(mk),"markets",flush=True)
n=[0]; lock=threading.Lock()
def one(m):
    path=os.path.join(DIR,"raw",m["ticker"]+".npz")
    if not os.path.exists(path):
        T,P,C,Sd=[],[],[],[]; cur=None
        while True:
            d=get(f"{API}/markets/trades?ticker={m['ticker']}&limit=1000"+(f"&cursor={cur}" if cur else ""))
            tr=d.get("trades") or []
            for x in tr:
                T.append(ts(x["created_time"])-m["close"]); P.append(float(x["yes_price_dollars"])); C.append(float(x["count_fp"]))
                Sd.append(1 if x.get("taker_outcome_side", x.get("taker_side"))=="yes" else 0)
            cur=d.get("cursor")
            if not cur or not tr: break
        o=np.argsort(np.array(T),kind="stable")
        tmp=path+".tmp.npz"
        np.savez_compressed(tmp,T=np.array(T,dtype=np.float64)[o],P=np.array(P,dtype=np.float32)[o],C=np.array(C,dtype=np.float32)[o],S=np.array(Sd,dtype=np.int8)[o])
        os.replace(tmp,path)
    with lock:
        n[0]+=1
        if n[0]%50==0: print(n[0],time.strftime("%H:%M:%S"),flush=True)
def safe(m):
    try: one(m); return 0
    except Exception as e:
        print("FAILED",m["ticker"],str(e)[:120],flush=True); return 1
with ThreadPoolExecutor(W) as ex: bad=sum(ex.map(safe,mk))
print("done",len(mk)-bad,"of",len(mk),flush=True)
