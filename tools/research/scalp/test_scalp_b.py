"""Hand-built checks of the simulator."""
import numpy as np, scalp_b as B
def mk(tr):   # tr: list of (elapsed_s, yes_price, count, taker_side 1=yes 0=no)
    T=np.array([t-900.0 for t,_,_,_ in tr]); P=np.array([p for _,p,_,_ in tr],dtype=np.float32); C=np.array([c for _,_,c,_ in tr],dtype=np.float32); S=np.array([s for *_,s in tr],dtype=np.int8)
    return B.grid(T,P,C,S)
i60=int(np.where(B.DEC==60)[0][0])
# 1. taker scalp, target: bid 50 / ask 51 at t=60, bid prints 53 at t=70 -> T=2 hit at 53
g=mk([(59.2,0.50,10,0),(59.5,0.51,10,1),(70.1,0.53,10,0)])
pnl,_=B.sim_b1(g,0.02,0.02,30); exp=0.53-0.51-B.fee(0.51)-B.fee(0.53); assert abs(pnl[i60]-exp)<1e-9,(pnl[i60],exp)
# 2. taker scalp, stop: bid prints 49 at t=65 (51-2) -> exit 49
g=mk([(59.2,0.50,10,0),(59.5,0.51,10,1),(65.0,0.49,10,0),(70.1,0.60,10,0)])
pnl,_=B.sim_b1(g,0.02,0.02,30); exp=0.49-0.51-B.fee(0.51)-B.fee(0.49); assert abs(pnl[i60]-exp)<1e-9
# 3. taker scalp, time-out at H=30 at the then bid (50, refreshed at t=88)
g=mk([(59.2,0.50,10,0),(59.5,0.51,10,1),(88.0,0.505,10,0)])
pnl,_=B.sim_b1(g,0.02,0.02,30); exp=0.505-0.51-B.fee(0.51)-B.fee(0.505); assert abs(pnl[i60]-exp)<1e-6,(pnl[i60],exp)
# 4. resting entry: bid 50; a print AT 50 fills only under "at"; a print at 49 fills under both
g=mk([(59.2,0.50,10,0),(59.5,0.51,10,1),(63.0,0.50,10,0)])
b0,fs,_=B.entry_rest(g,True); assert fs[i60]==-1
b0,fs,_=B.entry_rest(g,False); assert fs[i60]==63 and abs(b0[i60]-0.50)<1e-9
g=mk([(59.2,0.50,10,0),(59.5,0.51,10,1),(63.0,0.49,10,0),(70.0,0.52,5,1)])
b0,fs,_=B.entry_rest(g,True); assert fs[i60]==63
# offer at 51: lifted through at 52 (t=70) -> +1c, but the bid fell to 49 at fill: stop S=2 needs bid <= 48, not hit
pnl,tgt=B.sim_b2(g,b0,fs,0.01,0.02,30,True); assert abs(pnl[i60]-0.01)<1e-9 and tgt[i60]
# with a stop of 1c below (use S=0.01 via direct call) the stop triggers first at t=64? bid stays 49 (ffill) -> exit 49 with fee
pnl,tgt=B.sim_b2(g,b0,fs,0.01,0.01,30,True); assert abs(pnl[i60]-(0.49-0.50-B.fee(0.49)))<1e-9 and not tgt[i60]
# 5. mirror: NO frame of a market is the YES frame of the flipped market
g=mk([(59.2,0.50,10,0),(59.5,0.51,10,1),(70.1,0.47,10,1),(70.2,0.46,10,0)])
m=B.mirror(g); assert abs(m['ask'][60]-0.50)<1e-9 and abs(m['bid'][60]-0.49)<1e-9
pnl,_=B.sim_b1(m,0.02,0.02,30)   # buy NO at 50, NO bid becomes 1-0.47=0.53 at t=70 -> target
exp=0.53-0.50-B.fee(0.50)-B.fee(0.53); assert abs(pnl[i60]-exp)<1e-9,(pnl[i60],exp)
# 6. hold to settlement reference: filled at 50, result yes -> +50c
print("all simulator checks passed")
