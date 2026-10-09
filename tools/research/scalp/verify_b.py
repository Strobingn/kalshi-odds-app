"""Second, loop-based implementation of two cells, written without the vectorised helpers, to cross-check scalp_b.py."""
import json, os, sys, math, numpy as np
import scalp_b as B
def fee(p): return 0.07*p*(1-p)
def quotes(z):
    bidp={}; askp={}; lo={}; hi={}
    for t,p,c,s in zip(z['T'],z['P'],z['C'],z['S']):
        sec=int(math.floor(900.0+float(t)))
        if sec<0 or sec>=900: continue
        p=round(float(p),4)
        if s==0: bidp[sec]=p; lo[sec]=min(lo.get(sec,9),p)
        else: askp[sec]=p; hi[sec]=max(hi.get(sec,-9),p)
    def last(d,s):
        for k in range(s,s-11,-1):
            if k in d: return d[k]
        return None
    return (lambda s: last(bidp,s)), (lambda s: last(askp,s)), lo, hi
def b1(z,T,S,H):
    bid,ask,lo,hi=quotes(z); tot=0.0; n=0
    for s0 in range(60,781,5):
        P=ask(s0); b=bid(s0)
        if P is None or b is None or not b<P or P<0.10 or P>0.90: continue
        done=False
        for h in range(1,H+31):
            if s0+h>=900: break
            x=bid(s0+h)
            if x is None: continue
            if h<=H and not (x<=P-S+1e-9 or x>=P+T-1e-9 or h==H): continue
            tot+=x-P-fee(P)-fee(x); n+=1; done=True; break
    return tot,n
def b2(z,X,S,H,through):
    bid,ask,lo,hi=quotes(z); tot=0.0; n=0
    for s0 in range(60,781,5):
        b0=bid(s0); a=ask(s0)
        if b0 is None or a is None or not b0<a or b0<0.10 or b0>0.90: continue
        fs=None
        for w in range(1,21):
            l=lo.get(s0+w)
            if l is not None and ((l<b0-1e-9) if through else (l<=b0+1e-9)): fs=s0+w; break
        if fs is None: continue
        for h in range(1,H+31):
            if fs+h>=900: break
            x=bid(fs+h)
            if h<=H:
                if x is not None and x<=b0-S+1e-9: tot+=x-b0-fee(x); n+=1; break
                u=hi.get(fs+h)
                if u is not None and ((u>b0+X+1e-9) if through else (u>=b0+X-1e-9)): tot+=X; n+=1; break
                if h==H and x is not None: tot+=x-b0-fee(x); n+=1; break
            elif x is not None: tot+=x-b0-fee(x); n+=1; break
    return tot,n
if __name__=="__main__":
    D=sys.argv[1]; N=int(sys.argv[2])
    mk=[m for m in json.load(open(D+"/markets.json")) if os.path.exists(f"{D}/raw/{m['ticker']}.npz")]
    step=max(1,len(mk)//N); mk=mk[::step][:N]
    a=[0.0,0]; b=[0.0,0]; va=[0.0,0]; vb=[0.0,0]
    for m in mk:
        z=np.load(f"{D}/raw/{m['ticker']}.npz")
        t,n=b1(z,0.02,0.02,30); a[0]+=t; a[1]+=n
        t,n=b2(z,0.01,0.02,30,True); b[0]+=t; b[1]+=n
        g=B.grid(z['T'],z['P'],z['C'],z['S'])
        p,_=B.sim_b1(g,0.02,0.02,30); va[0]+=float(np.nansum(p)); va[1]+=int((~np.isnan(p)).sum())
        b0,fs,_=B.entry_rest(g,True); p,_=B.sim_b2(g,b0,fs,0.01,0.02,30,True); vb[0]+=float(np.nansum(p)); vb[1]+=int((~np.isnan(p)).sum())
    print(f"{len(mk)} windows, YES side only")
    print(f"B1 T2 S2 H30   loop: n={a[1]} mean {100*a[0]/a[1]:+.4f}c | vectorised: n={va[1]} mean {100*va[0]/va[1]:+.4f}c")
    print(f"B2 X1 S2 H30 through  loop: n={b[1]} mean {100*b[0]/b[1]:+.4f}c | vectorised: n={vb[1]} mean {100*vb[0]/vb[1]:+.4f}c")
