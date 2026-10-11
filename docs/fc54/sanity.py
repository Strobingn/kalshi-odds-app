import pickle,numpy as np,math,glob
from math import erf
S,strike=pickle.load(open("spot.pkl","rb")); res=pickle.load(open("/workspace/forecast53/results.pkl","rb"))
ok=0;n=0;okm=0;lags=[]
for f in sorted(glob.glob("/workspace/forecast53/ser-*.pkl"))[-4:]:
    ser=pickle.load(open(f,"rb"))
    for tk,rows in ser.items():
        if tk not in res or tk not in strike: continue
        c=tk[2:5]; t0,a=S[c]; close=rows[0][7]; K=strike[tk]
        osec=int((close-900000)//1000-t0); csec=int(close//1000-t0)
        if csec+1>=len(a): continue
        b=math.log(a[osec]/K)
        # spot (basis-adjusted) 60s before close vs result
        s=a[csec-60]*math.exp(-b); n+=1; ok+= (s>K)==(res[tk]==1)
        r=[x for x in rows if close-x[0]>60000]
        if r: okm+= ((r[-1][1]+r[-1][2])/2>0.5)==(res[tk]==1)
        # final settle check: spot avg last 60s vs K
print(n, ok/n, okm/n)
