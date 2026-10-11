"""Join the cloud recorder's 1-second books and Coinbase spot with the complete trade tape.

The recorder's own trades file is not used: it polls 1,000 trades every 5 s without paging, so busy
seconds are cut short. The tape (pull_tape.py) has every trade.

Per window (ticker) one npz in <out>/win/:
  T,P,C,S      the tape (seconds relative to the close, YES price, contracts, 1 = taker bought YES)
  yb,ybq,ya,yaq   YES best bid / size / ask / size at the END of each of the 900 seconds
                  (last snapshot received in or before that second, at most 5 s old; NaN otherwise)
  bage         age in seconds of that snapshot at the end of the second
  spot         Coinbase BTC-USD at the end of seconds -600 .. 899 (last print, at most 10 s old)
and <out>/windows.json: ticker, open, close, strike, result, book coverage.

Run: python3 rec_build.py <recordings dir> <out dir> <tape dir> [<tape dir> ...]
"""
import io, json, os, sys
import numpy as np, pandas as pd
HERE=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,os.path.dirname(HERE))
from recordings import read_gzip_tolerant, list_days
PRE=600
def read(path,cols):
    b=read_gzip_tolerant(open(path,'rb').read()) if path.endswith('.gz') else open(path,'rb').read()
    df=pd.read_csv(io.BytesIO(b),dtype=str,usecols=cols,on_bad_lines='skip')
    return df[df[cols[0]]!=cols[0]]
def last_per_second(sec,val,n,lo=0):
    """val of the last row in each second (rows time-sorted); NaN where a second has no row."""
    out=np.full(n,np.nan); i=sec-lo; ok=(i>=0)&(i<n); out[i[ok]]=val[ok]; return out
def ffill(a,maxage):
    n=len(a); idx=np.where(~np.isnan(a),np.arange(n),-1); idx=np.maximum.accumulate(idx)
    age=np.arange(n)-idx
    return np.where((idx>=0)&(age<=maxage),a[np.clip(idx,0,None)],np.nan), np.where(idx>=0,age,np.inf)
if __name__=="__main__":
    REC,OUT=sys.argv[1],sys.argv[2]; TAPES=sys.argv[3:]
    os.makedirs(os.path.join(OUT,'win'),exist_ok=True)
    tape={}
    for d in TAPES:
        for m in json.load(open(os.path.join(d,'markets.json'))):
            p=os.path.join(d,'raw',m['ticker']+'.npz')
            if os.path.exists(p): tape[m['ticker']]=(p,m)
    days=list_days(REC); books=[]; spots=[]
    for day in days:
        p=os.path.join(REC,f'book_{day}.csv.gz')
        if os.path.exists(p):
            df=read(p,['ts_ms','ticker','strike','close_ms','yes_bid','yes_bid_qty','yes_ask','yes_ask_qty'])
            df=df[df.ticker.str.startswith('KXBTC15M-')]
            for c in df.columns:
                if c!='ticker': df[c]=pd.to_numeric(df[c],errors='coerce')
            books.append(df)
        p=os.path.join(REC,f'spot_{day}.csv.gz')
        if os.path.exists(p):
            df=read(p,['ts_ms','product','price']); df=df[df['product']=='BTC-USD']
            spots.append(pd.DataFrame({'ts':pd.to_numeric(df.ts_ms,errors='coerce'),'px':pd.to_numeric(df.price,errors='coerce')}).dropna())
        print(day,'loaded',flush=True)
    book=pd.concat(books).dropna(subset=['ts_ms','close_ms']).sort_values('ts_ms',kind='stable')
    spot=pd.concat(spots).sort_values('ts',kind='stable')
    s_ts=spot.ts.values.astype(np.int64); s_px=spot.px.values
    wins=[]
    for tk,g in book.groupby('ticker',sort=False):
        if tk not in tape: continue
        path,m=tape[tk]; close=int(m['close']); op=close-900
        if abs(int(g.close_ms.iloc[0])//1000-close)>1: continue
        # a snapshot received during second s is known at the END of second s
        sec=((g.ts_ms.values.astype(np.int64)-op*1000)//1000).astype(np.int64)
        arr={}
        for k,c in (('yb','yes_bid'),('ybq','yes_bid_qty'),('ya','yes_ask'),('yaq','yes_ask_qty')):
            arr[k]=last_per_second(sec,g[c].values.astype(np.float64),900)
        has=np.full(900,np.nan); has[np.clip(sec[(sec>=0)&(sec<900)],0,899)]=1.0
        _,age=ffill(has,5)
        out={}
        idx=np.where(~np.isnan(has),np.arange(900),-1); idx=np.maximum.accumulate(idx); ok=(idx>=0)&((np.arange(900)-idx)<=5)
        for k in arr: out[k]=np.where(ok,arr[k][np.clip(idx,0,None)],np.nan)     # one snapshot row at a time: a missing side stays missing
        # spot at the end of seconds -PRE..899
        ends=(op+np.arange(-PRE,900)+1)*1000
        j=np.searchsorted(s_ts,ends,side='left')-1
        sp=np.where((j>=0)&((ends-s_ts[np.clip(j,0,None)])<=10_000),s_px[np.clip(j,0,None)],np.nan)
        z=np.load(path)
        np.savez_compressed(os.path.join(OUT,'win',tk+'.npz'),T=z['T'],P=z['P'],C=z['C'],S=z['S'],bage=age.astype(np.float32),spot=sp,**out)
        wins.append(dict(ticker=tk,open=op,close=close,strike=float(m['strike']) if m.get('strike') else None,result=m['result'],
                         book_secs=int(ok.sum()),spot_secs=int((~np.isnan(sp[PRE:])).sum())))
    wins.sort(key=lambda w:w['open']); json.dump(wins,open(os.path.join(OUT,'windows.json'),'w'))
    full=[w for w in wins if w['book_secs']>=880 and w['spot_secs']>=880]
    print(len(wins),'windows with tape and book;',len(full),'with at least 880 s of both',flush=True)
