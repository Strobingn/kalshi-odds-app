import pandas as pd, glob
d='/workspace/edge-research/data/'
files={'BTC':[d+'coinbase_BTC-USD_1m.csv',d+'cb_BTC_p2.csv',d+'cb_BTC_p3.csv'],'ETH':sorted(glob.glob(d+'cb_ETH_p*.csv')),'SOL':sorted(glob.glob(d+'cb_SOL_p*.csv'))}
out=[]
for c,fs in files.items():
    x=pd.concat([pd.read_csv(f,header=None,names=['t','lo','hi','op','cl','v']) for f in fs]).drop_duplicates('t').sort_values('t')
    x['coin']=c; out.append(x[['coin','t','cl']])
    print(c,len(x),pd.to_datetime(x.t.min(),unit='s'),pd.to_datetime(x.t.max(),unit='s'), 'gaps>5m',(x.t.diff()>300).sum())
pd.concat(out).to_parquet('prep/long_spot.parquet')
