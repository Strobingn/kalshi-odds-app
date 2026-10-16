import numpy as np, pandas as pd, itertools, time
from sim import *
cut=pd.Timestamp('2026-10-02',tz='UTC').value//10**6
end=pd.Timestamp('2026-10-09',tz='UTC').value//10**6
close=dict(zip(P.tk,P.close))
rows=[]; t0=time.time()
taus=[(120,840),(300,840),(60,300)]
exits=[(tp,sl,tr,mh) for tp in (20,40,60) for sl in (0,30) for tr in (0,20) for mh in (60,180)]
combos=[]
for fam in (0,1):
    for Y,X,S,tw,ex in itertools.product((9,15,30),(30,50,80),(10,20),taus,exits):
        combos.append((fam,Y,X,S,tw[0],tw[1])+ex+(0,0))
for X,S,tw in itertools.product((0,20,40,60,100),(10,20),taus):
    combos.append((2,15,X,S,tw[0],tw[1],0,0,0,0,1,1))      # enter on gap, exit only when selling beats holding, else settle
    combos.append((2,15,X,S,tw[0],tw[1],0,0,0,0,1,0))      # same + time stop at tau<=20s
    for ex in exits: combos.append((2,15,X,S,tw[0],tw[1])+ex+(0,0))
    for ex in exits: combos.append((2,15,X,S,tw[0],tw[1])+ex+(1,0))
print('combos',len(combos))
for c in combos:
    d=simulate(*c)
    if len(d)==0: continue
    d['close']=d.tk.map(close)
    tr=d[d.close<=cut]; te=d[(d.close>cut)&(d.close<=end)]
    rows.append(c+(len(tr),tr.net.mean() if len(tr) else np.nan,(tr.net>0).mean() if len(tr) else np.nan,len(te),te.net.mean() if len(te) else np.nan))
R=pd.DataFrame(rows,columns=['fam','Y','X','S','tlo','thi','TP','SL','TR','MH','gapexit','settle','ntr','mtr','wtr','nte','mte'])
R.to_parquet('grid_results.parquet'); print('done',time.time()-t0)
