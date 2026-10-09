import zipfile,glob,io,numpy as np,pandas as pd
for s in ['BTC','ETH','SOL']:
    parts=[]
    for f in sorted(glob.glob(f'spot/{s}USDT-1s-*.zip')):
        z=zipfile.ZipFile(f); a=np.loadtxt(io.TextIOWrapper(z.open(z.namelist()[0])),delimiter=',',usecols=(0,4))
        parts.append(a)
    a=np.vstack(parts); t=(a[:,0]//1000000).astype(np.int64)  # sec
    df=pd.DataFrame({'t':t,'px':a[:,1]}).drop_duplicates('t').set_index('t')
    full=np.arange(df.index.min(),df.index.max()+1); df=df.reindex(full).ffill()
    df.reset_index(names='t').to_parquet(f'prep/spot_{s}.parquet'); print(s,len(df),df.index.min(),df.index.max())
