import gzip, orjson, glob, os, sys, datetime as dt
import numpy as np, pandas as pd
from multiprocessing import Pool
D='/workspace/diphunter-recorder/data'; O='/workspace/scalp-research/prep'
def iso_ms(s):
    # 2026-09-30T23:59:58.016937Z
    return int(dt.datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()*1000)
def do_ob(f):
    out=O+'/ob-'+f[-19:-9]+'.parquet'
    if os.path.exists(out): return out
    rows=[]
    with gzip.open(f,'rb') as g:
        for line in g:
            try: r=orjson.loads(line)
            except Exception: continue
            y=r.get('y') or []; n=r.get('n') or []
            yb=y[-1][0] if y else 0; ybq=y[-1][1] if y else 0
            nb=n[-1][0] if n else 0; nbq=n[-1][1] if n else 0
            # depth within 2c of best
            yd=sum(q for p,q in y if p>=yb-20) if y else 0
            nd=sum(q for p,q in n if p>=nb-20) if n else 0
            rows.append((r['ts'],r['tk'],yb,ybq,1000-nb if n else 1000,nbq,yd,nd,r.get('lat',0)))
    df=pd.DataFrame(rows,columns=['ts','tk','yb','ybq','ya','yaq','ydep','ndep','lat'])
    df.to_parquet(out); return out
def do_tr(f):
    out=O+'/tr-'+f[-19:-9]+'.parquet'
    if os.path.exists(out): return out
    rows=[]
    with gzip.open(f,'rb') as g:
        for line in g:
            try: r=orjson.loads(line)
            except Exception: continue
            rows.append((iso_ms(r['created_time']),r['ticker'],int(round(float(r['yes_price_dollars'])*1000)),float(r['count_fp']),1 if r['taker_side']=='yes' else 0, r['trade_id']))
    df=pd.DataFrame(rows,columns=['ts','tk','yp','cnt','tyes','id']).drop_duplicates('id').drop(columns='id')
    df.to_parquet(out); return out
def do_mk():
    rows={};res={}
    for f in sorted(glob.glob(D+'/markets-*.jsonl.gz')):
        with gzip.open(f,'rb') as g:
            for line in g:
                r=orjson.loads(line)
                tk=r.get('ticker')
                if r.get('type')=='market' and 'open_time' in r:
                    old=rows.get(tk); fs=r.get('floor_strike') or (old[1] if old else None)
                    rows[tk]=(r['series'],fs,iso_ms(r['open_time']),iso_ms(r['close_time']))
                elif r.get('type')=='result' and r.get('result') in ('yes','no'):
                    res[tk]=(1 if r['result']=='yes' else 0, float(r.get('expiration_value') or 'nan'))
                    if r.get('floor_strike') and tk in rows: rows[tk]=(rows[tk][0],r['floor_strike'])+rows[tk][2:]
    df=pd.DataFrame([(k,)+v+res.get(k,(-1,np.nan)) for k,v in rows.items()],columns=['tk','series','strike','open','close','result','expv'])
    df.to_parquet(O+'/markets.parquet'); print(len(df), (df.result>=0).sum())
if __name__=='__main__':
    pass
    with Pool(3) as p:
        print(p.map(do_ob,sorted(glob.glob(D+'/orderbook-*.jsonl.gz'))))
        print(p.map(do_tr,sorted(glob.glob(D+'/trades-*.jsonl.gz'))))
