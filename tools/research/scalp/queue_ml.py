"""How far back in the queue can the ML-picked resting scalp be and still pay?
Resting scalp = join the best bid (20 s); if filled, offer +1c; stop 4c below; 120 s time-out (sell at the bid, fee).
Queue model: Q contracts are ahead of us on each leg when we join. Prints at our price use up the queue; a print
through our price fills us at once. Cancels ahead of us are ignored (the queue never shrinks on its own).
TEST days only. Selection = the R_SCALP_AT model's rule, fixed on VALID before TEST was scored."""
import json, os, numpy as np, random, sys
TAPE=sys.argv[1] if len(sys.argv)>1 else 'tape'
import scalp_b as B
d=np.load('ml_data.npz',allow_pickle=True); feats=[str(f) for f in d['feats']]
Y=d['Y']; mkt=d['mkt'].astype(int); sec=d['sec'].astype(int); frame=d['frame'].astype(int); mday=d['mday']; tick=d['tickers']
days=sorted(set(mday)); TE=set(days[15:]); day=mday[mkt]
ok=~np.isnan(Y[:,4]); c=np.isin(day,list(TE))&ok
pt=np.load('ml_pred_R_SCALP_AT.npy')[:,0]; theta=json.load(open('ml_results.json'))['R_SCALP_AT']['theta']
bid=d['X'][:,feats.index('bid')].astype(np.float64)
rows=np.where(c)[0]; assert len(rows)==len(pt)
QS=(0,100,250,500,1000,2000,5000)
mk={m['ticker']:m for m in json.load(open(os.path.join(TAPE,'markets.json')))}
out={q:[] for q in QS}   # (day, pnl, selected)
bym={}
for r,p in zip(rows,pt): bym.setdefault(mkt[r],[]).append((r,p))
for n,(mi,lst) in enumerate(bym.items()):
    t=str(tick[mi]); z=np.load(os.path.join(TAPE,"raw",t+".npz")); res=1.0 if mk[t]['result']=='yes' else 0.0
    el=900.0+z['T']; P0=np.round(z['P'].astype(np.float64),4); C=z['C'].astype(np.float64); S0=z['S'].astype(int)
    g0=B.grid(z['T'],z['P'],z['C'],z['S'])
    fr={0:(P0,S0,g0,res),1:(1-P0,1-S0,B.mirror(g0),1-res)}
    cache={}
    for r,p in lst:
        f=frame[r]; s=sec[r]; P,Sd,g,rs=fr[f]; b0=round(float(bid[r]),4); a0=b0+0.01
        if f not in cache:
            sell=Sd==0; buy=Sd==1          # sell: takers hitting bids of this side ; buy: takers lifting offers
            cache[f]=(el[sell],P[sell],C[sell],el[buy],P[buy],C[buy])
        ts,ps,cs,tb,pb,cbuy=cache[f]
        i0=np.searchsorted(ts,s+1.0,'left'); i1=np.searchsorted(ts,s+21.0,'left')      # seconds s+1 .. s+20 inclusive
        pe=ps[i0:i1]; ce=cs[i0:i1]; te=ts[i0:i1]
        atq=np.cumsum(np.where(pe<=b0+1e-9,ce,0.0)); thr=pe<b0-1e-9
        for Q in QS:
            hit=np.where(thr|(atq>Q))[0]
            if len(hit)==0: out[Q].append((day[r],0.0,p>=theta,0)); continue
            fs=int(np.floor(te[hit[0]]))
            # exit leg, second by second (stop first, then target, then time-out), as in scalp_b.sim_b2
            j0=np.searchsorted(tb,fs+1.0,'left'); j1=np.searchsorted(tb,fs+121.0,'left')
            px=pb[j0:j1]; cx=cbuy[j0:j1]; tx=tb[j0:j1]
            cum=np.cumsum(np.where(px>=a0-1e-9,cx,0.0)); th2=px>a0+1e-9
            h=np.where(th2|(cum>Q))[0]; t_tgt=int(np.floor(tx[h[0]]))-fs if len(h) else 10**9
            pnl=None
            for hh in range(1,151):
                if fs+hh>=900: break
                b=g['bid'][fs+hh]
                if hh<=120:
                    if not np.isnan(b) and b<=b0-0.04+1e-9: pnl=b-b0-B.fee(b); break
                    if t_tgt<=hh: pnl=0.01; break
                    if hh==120 and not np.isnan(b): pnl=b-b0-B.fee(b); break
                elif not np.isnan(b): pnl=b-b0-B.fee(b); break
            if pnl is None: pnl=rs-b0
            out[Q].append((day[r],pnl,p>=theta,1))
    if (n+1)%100==0: print(n+1,flush=True)
def ci(xs):
    by={}
    for dd,v in xs: a=by.setdefault(dd,[0.0,0]); a[0]+=v; a[1]+=1
    ks=list(by); rnd=random.Random(3); o=[]
    for _ in range(3000):
        s=n=0.0
        for k in rnd.choices(ks,k=len(ks)): s+=by[k][0]; n+=by[k][1]
        o.append(100*s/n)
    o.sort(); return o[75],o[2924],sum(1 for k in ks if by[k][0]>0),len(ks)
print(f"\nTEST days, cents per order posted. theta={theta:+.3f}c")
print(f"{'contracts ahead':>16s} | all orders: filled%, c/order [95%] | ML-picked orders: n, filled%, c/order [95%], positive days")
for Q in QS:
    xs=out[Q]; a=[(dd,v) for dd,v,s,f in xs]; b=[(dd,v) for dd,v,s,f in xs if s]
    la,ha,_,_=ci(a); lb,hb,pdy,nd=ci(b)
    print(f"{Q:16d} | {100*np.mean([f for *_,f in xs]):5.1f}% {100*np.mean([v for _,v in a]):+6.2f} [{la:+.2f},{ha:+.2f}] | {len(b):6d} {100*np.mean([f for dd,v,s,f in xs if s]):5.1f}% {100*np.mean([v for _,v in b]):+6.2f} [{lb:+.2f},{hb:+.2f}] {pdy}/{nd}")
json.dump({str(Q):[(str(a),float(b),bool(c_),int(f)) for a,b,c_,f in out[Q]] for Q in QS},open('queue_ml_out.json','w'))
