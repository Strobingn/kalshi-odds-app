import json, time, urllib.request, sys
def get(url):
    for a in range(8):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent":"research/1.0"}), timeout=30) as r: return json.load(r)
        except Exception as e: time.sleep(1+a)
    raise RuntimeError(url)
days=float(sys.argv[1]); end=int(time.time())//60*60; start=end-int(days*86400); out={}
t=start
import datetime as dt
iso=lambda x: dt.datetime.fromtimestamp(x, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
while t<end:
    e=min(t+300*60,end)
    for row in get(f"https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=60&start={iso(t)}&end={iso(e)}"):
        out[int(row[0])]=float(row[4])
    t=e; time.sleep(0.12)
json.dump(sorted(out.items()), open("cb_btc.json","w")); print("bars", len(out), iso(min(out)), iso(max(out)))
