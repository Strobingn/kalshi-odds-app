"""Dataset for the ML scalping study (SCALP_ML_DESIGN.md). One row per (window, decision second, side)."""
import json, os, sys, math, datetime as dt
import numpy as np
import scalp_b as B
DEC=B.DEC; NAN=np.nan
LAGS=(5,10,20,30,60,120,300); WINS=(5,10,30,60,120,300)
cb=dict((int(a),b) for a,b in json.load(open('cb_btc.json')))
_sig={}
def sigma_at(m_end):
    if m_end in _sig: return _sig[m_end]
    cl=[cb.get(m_end-60-60*i) for i in range(30,-1,-1)]; cl=[c for c in cl if c]
    out=None
    if len(cl)>=11:
        r=np.diff(np.log(np.array(cl))); v=r.var(ddof=1)
        out=math.sqrt(v) if v>0 else None
    _sig[m_end]=out; return out
def phi(x): return 0.5*(1+math.erf(x/math.sqrt(2)))
def extras(T,P,C,S):
    sec=np.floor(900.0+T).astype(np.int64); ok=(sec>=0)&(sec<900); sec=sec[ok]; C=C[ok].astype(np.float64); S=S[ok]
    my=np.zeros(900); mn=np.zeros(900); cnt=np.zeros(900)
    np.maximum.at(my,sec[S==1],C[S==1]); np.maximum.at(mn,sec[S==0],C[S==0]); np.add.at(cnt,sec,1.0)
    hasy=np.zeros(900,bool); hasn=np.zeros(900,bool); hasy[sec[S==1]]=True; hasn[sec[S==0]]=True
    return my,mn,cnt,hasy,hasn
def since(has):
    idx=np.where(has,np.arange(900),-1); idx=np.maximum.accumulate(idx)
    return np.where(idx>=0,np.arange(900)-idx,900).astype(np.float64)
def rollmax(a,w):
    pad=np.concatenate([np.zeros(w-1),a]); return np.lib.stride_tricks.sliding_window_view(pad,w).max(axis=1)
def rollsum(a,w):
    c=np.concatenate([[0.0],np.cumsum(a)]); i=np.arange(1,901); return c[i]-c[np.maximum(i-w,0)]
FEATS=None
def frame_rows(g,ex,res,sign,op,K):
    global FEATS
    my,mn,cnt,sb,sa=ex      # in this frame: my = largest print buying this side, sb/sa = seconds since bid/ask print
    bid=g['bid']; ask=g['ask']; mid=(bid+ask)/2
    f={}
    f['ask']=ask[DEC]; f['bid']=bid[DEC]; f['mid']=mid[DEC]; f['spread']=(ask-bid)[DEC]; f['tte']=(900-DEC).astype(float)
    for k in LAGS: f[f'dm{k}']=mid[DEC]-mid[DEC-k] if True else None
    for k in LAGS:
        if k>60: f[f'dm{k}'][DEC-k<0]=NAN
    hi=np.fmax.accumulate(mid); lo=np.fmin.accumulate(mid)
    f['from_hi']=mid[DEC]-hi[DEC]; f['from_lo']=mid[DEC]-lo[DEC]
    first=mid[~np.isnan(mid)][0] if (~np.isnan(mid)).any() else NAN
    f['from_first']=mid[DEC]-first
    d1=np.diff(mid,prepend=mid[0]); d1=np.where(np.isnan(d1),0.0,d1)
    for w in (60,300):
        s1=rollsum(d1,w); s2=rollsum(d1*d1,w); f[f'vol{w}']=np.sqrt(np.maximum(s2/w-(s1/w)**2,0.0))[DEC]
    f['nchg60']=rollsum((d1!=0).astype(float),60)[DEC]
    vy=g['vy']; vn=g['vn']
    for w in WINS:
        by=rollsum(vy,w)[DEC]; bn=rollsum(vn,w)[DEC]; tot=by+bn
        f[f'imb{w}']=np.where(tot>0,(by-bn)/np.maximum(tot,1e-9),0.0); f[f'lvol{w}']=np.log1p(tot)
    cy=np.cumsum(vy)[DEC]; cn=np.cumsum(vn)[DEC]; ct=cy+cn
    f['imb_all']=np.where(ct>0,(cy-cn)/np.maximum(ct,1e-9),0.0); f['lvol_all']=np.log1p(ct)
    f['lcnt30']=np.log1p(rollsum(cnt,30)[DEC])
    for w in (30,120):
        f[f'lmaxbuy{w}']=np.log1p(rollmax(my,w)[DEC]); f[f'lmaxsell{w}']=np.log1p(rollmax(mn,w)[DEC])
    f['stale_bid']=sb[DEC]; f['stale_ask']=sa[DEC]
    # spot (last completed Coinbase minute)
    n=len(DEC); dist=np.full(n,NAN); r1=np.full(n,NAN); r3=np.full(n,NAN); r5=np.full(n,NAN); sg=np.full(n,NAN); z=np.full(n,NAN); fm=np.full(n,NAN); age=np.full(n,NAN)
    for i,s in enumerate(DEC):
        now=op+int(s); m_end=now-(now%60); S0=cb.get(m_end-60)
        if not S0 or not K: continue
        age[i]=now-m_end; dist[i]=sign*(S0/K-1)*1e4
        for arr,k in ((r1,1),(r3,3),(r5,5)):
            p=cb.get(m_end-60-60*k)
            if p: arr[i]=sign*math.log(S0/p)*1e4
        sig=sigma_at(m_end)
        if sig:
            tte=900-int(s); sg[i]=sig*1e4; z[i]=sign*math.log(S0/K)/(sig*math.sqrt(tte/60.0))
            sd=math.sqrt(sig*sig*((max(tte,60)-60)+20)/60+(0.5e-4)**2); fy=phi(math.log(S0/K)/sd)
            fm[i]=(fy if sign>0 else 1-fy)-f['mid'][i]
    f['spot_dist_bp']=dist; f['spot_r1']=r1; f['spot_r3']=r3; f['spot_r5']=r5; f['spot_sigma']=sg; f['spot_z']=z; f['fair_minus_mid']=fm; f['spot_age']=age
    t0=dt.datetime.fromtimestamp(op,dt.timezone.utc); f['hour']=np.full(n,float(t0.hour)); f['weekend']=np.full(n,1.0 if t0.weekday()>=5 else 0.0)
    if FEATS is None: FEATS=list(f.keys())
    X=np.column_stack([f[k] for k in FEATS]).astype(np.float32)
    # ---- targets ----
    P=g['ask'][DEC]; b0q=g['bid'][DEC]
    live=(~np.isnan(P))&(~np.isnan(b0q))&(b0q<P)&(P>=0.10)&(P<=0.90)
    t_hold=np.where(live,res-P-B.fee(P),NAN)
    ts,_=B.sim_b1(g,0.04,0.04,120)
    ts=np.where(live&np.isnan(ts),res-P-B.fee(P),ts)        # never closed before the end: it settles
    Y=[t_hold,ts]
    for through in (True,False):
        b0,fs,lv=B.entry_rest(g,through)
        rs,_=B.sim_b2(g,b0,fs,0.01,0.04,120,through)
        rs=np.where((fs>=0)&np.isnan(rs),res-b0,rs)           # filled, never closed: it settles
        rs=np.where(lv,np.where(fs>=0,rs,0.0),NAN)
        rh=np.where(lv,np.where(fs>=0,res-b0,0.0),NAN)
        Y+=[rs,rh]
    Y=np.column_stack(Y).astype(np.float32)     # T_HOLD,T_SCALP,R_SCALP_THR,R_HOLD_THR,R_SCALP_AT,R_HOLD_AT
    keep=live|~np.isnan(Y[:,2])
    return X[keep],Y[keep],DEC[keep]
def one(args):
    path,result,op,K,mi=args
    try:
        z=np.load(path); g0=B.grid(z['T'],z['P'],z['C'],z['S']); my,mn,cnt,hasy,hasn=extras(z['T'],z['P'],z['C'],z['S'])
        sy=since(hasy); sn=since(hasn)   # seconds since last taker-YES print (ask) / taker-NO print (bid)
        r=1.0 if result=='yes' else 0.0
        xa,ya,sa=frame_rows(g0,(my,mn,cnt,sn,sy),r,+1.0,op,K)
        xb,yb,sb=frame_rows(B.mirror(g0),(mn,my,cnt,sy,sn),1.0-r,-1.0,op,K)
        X=np.vstack([xa,xb]); Y=np.vstack([ya,yb]); s=np.concatenate([sa,sb]); fr=np.concatenate([np.zeros(len(sa)),np.ones(len(sb))])
        return mi,X,Y,s,fr,FEATS
    except Exception as e:
        return mi,None,str(e)[:200],None,None,None
if __name__=="__main__":
    D=sys.argv[1]; OUT=sys.argv[2]; NP=int(sys.argv[3]) if len(sys.argv)>3 else 2
    mk=[m for m in json.load(open(os.path.join(D,"markets.json"))) if os.path.exists(os.path.join(D,"raw",m["ticker"]+".npz"))]
    mk.sort(key=lambda m:m["open"])
    jobs=[(os.path.join(D,"raw",m["ticker"]+".npz"),m["result"],int(m["open"]),float(m["strike"]) if m.get("strike") else None,i) for i,m in enumerate(mk)]
    from multiprocessing import Pool
    Xs=[];Ys=[];Ss=[];Fs=[];Ms=[];feats=None;errs=0
    with Pool(NP) as pool:
        for k,(mi,X,Y,s,fr,ft) in enumerate(pool.imap(one,jobs,chunksize=8)):
            if X is None: errs+=1; print("ERR",mi,Y,flush=True); continue
            feats=ft; Xs.append(X);Ys.append(Y);Ss.append(s);Fs.append(fr);Ms.append(np.full(len(s),mi))
            if (k+1)%400==0: print(k+1,flush=True)
    days=np.array([dt.datetime.fromtimestamp(m["open"],dt.timezone.utc).strftime("%Y-%m-%d") for m in mk])
    np.savez_compressed(OUT,X=np.vstack(Xs),Y=np.vstack(Ys),sec=np.concatenate(Ss),frame=np.concatenate(Fs),mkt=np.concatenate(Ms),mday=days,feats=np.array(feats),tickers=np.array([m["ticker"] for m in mk]))
    print("rows",sum(len(x) for x in Xs),"features",len(feats),"errors",errs,flush=True)
