import json, time, urllib.request, urllib.parse, urllib.error, random
B="https://api.elections.kalshi.com/trade-api/v2"
def get(path, **q):
    url=B+path+("?"+urllib.parse.urlencode({k:v for k,v in q.items() if v is not None}) if q else "")
    for a in range(14):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent":"research/1.0","Accept":"application/json"}), timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (429,502,503,504): time.sleep(0.4+0.3*a+random.random()*0.3); continue
            if e.code==404: return None
            raise
        except Exception as e:
            time.sleep(0.5+0.5*a)
    raise RuntimeError("failed "+url)
def ts(s):
    import datetime as dt
    s=s.replace('Z','+00:00')
    if '.' in s:
        a,b=s.split('.'); frac,tz=b[:-6],b[-6:]; s=a+'.'+(frac+'000000')[:6]+tz
    return dt.datetime.fromisoformat(s).timestamp()
