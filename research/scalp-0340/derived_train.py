import pandas as pd, numpy as np, itertools
from sim import *
cut=pd.Timestamp('2026-10-02',tz='UTC').value//10**6
close=dict(zip(P.tk,P.close)); rows=[]
for X,FS,tw,S in itertools.product((60,100,150,200),(0,50,100),[(300,840),(120,840)],(10,20)):
    d=simulate(2,15,X,S,tw[0],tw[1],0,0,0,0,1,0,FS=FS)   # scalp: gap-closed exit, fair-turn stop, time stop tau<=20s
    d=d[d.tk.map(close)<=cut]
    rows.append((X,FS,tw,S,len(d),d.net.mean(),(d.net>0).mean(),d.hold.median()))
R=pd.DataFrame(rows,columns=['X','FS','tw','S','n','mean','win','hold_med']); print(R.sort_values('mean',ascending=False).round(2).head(12).to_string()); print('combos',len(R))
