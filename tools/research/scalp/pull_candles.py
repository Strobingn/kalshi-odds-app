import json, sys, time, os, threading
from concurrent.futures import ThreadPoolExecutor
from kx import get, ts
SER=sys.argv[1]; DAYS=float(sys.argv[2]); OUT=f"cd_{SER}.jsonl"; MK=f"mk_{SER}.json"
if os.path.exists(MK): mk=json.load(open(MK))
else:
    lo=int(time.time()-DAYS*86400); cur=None; mk=[]
    while True:
        d=get("/markets", series_ticker=SER, status="settled", limit=1000, min_close_ts=lo, cursor=cur)
        ms=d.get("markets") or []
        for m in ms:
            if m.get("result") in ("yes","no"):
                mk.append({"ticker":m["ticker"],"open":ts(m["open_time"]),"close":ts(m["close_time"]),"result":m["result"],"strike":m.get("floor_strike"),"exp":m.get("expiration_value"),"vol":m.get("volume_fp")})
        cur=d.get("cursor")
        if not cur or not ms: break
    json.dump(mk, open(MK,"w"))
print(SER, len(mk), "markets", flush=True)
done=set()
if os.path.exists(OUT):
    for l in open(OUT):
        try: done.add(json.loads(l)["t"])
        except Exception: pass
todo=[m for m in mk if m["ticker"] not in done]
out=open(OUT,"a"); lock=threading.Lock(); n=[0]
def f(x):
    return None if x in (None,"") else float(x)
def one(m):
    try:
        d=get(f"/series/{SER}/markets/{m['ticker']}/candlesticks", start_ts=int(m["open"])-60, end_ts=int(m["close"])+60, period_interval=1)
        cs=(d or {}).get("candlesticks") or []
        rows=[(c["end_period_ts"], f((c.get("yes_bid") or {}).get("close_dollars")), f((c.get("yes_ask") or {}).get("close_dollars")), f(c.get("volume_fp"))) for c in cs]
    except Exception as e:
        rows=None
    with lock:
        if rows is not None: out.write(json.dumps({"t":m["ticker"],"c":rows})+"\n")
        n[0]+=1
        if n[0]%200==0: out.flush(); print(n[0], flush=True)
with ThreadPoolExecutor(3) as ex: list(ex.map(one, todo))
out.close(); print("done", flush=True)
