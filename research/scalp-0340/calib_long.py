import pandas as pd, numpy as np
P=pd.read_parquet('prep/panel2.parquet',columns=['tk','ts','yb','ya'])
M=pd.read_parquet('prep/long_markets.parquet'); C=pd.read_parquet('prep/long_candles.parquet')
C=C.merge(M[['id','ticker']],left_on='market_id',right_on='id')
C=C[C.ticker.isin(set(P.tk))]
C['t_ms']=C.end_ts*1000+3000  # decision at bar end; earliest fill ≥ 3 s later
P=P.sort_values('ts'); C=C.sort_values('t_ms')
J=pd.merge_asof(C,P.rename(columns={'tk':'ticker'}),left_on='t_ms',right_on='ts',by='ticker',direction='forward',tolerance=10000).dropna(subset=['ya','yb'])
J=J[(J.ya>0)&(J.ya<1000)&(J.yb>0)]
cand_buy=np.maximum(J.yes_ask_close,J.yes_ask_open)*1000  # same-bar proxy check
ask_adv=J.ya-J.yes_ask_close*1000; bid_adv=J.yes_bid_close*1000-J.yb
print('n',len(J))
print('book ask - candle ask_close (mills): mean %.2f median %.1f p75 %.1f'%(ask_adv.mean(),ask_adv.median(),ask_adv.quantile(.75)))
print('candle bid_close - book bid (mills): mean %.2f median %.1f p75 %.1f'%(bid_adv.mean(),bid_adv.median(),bid_adv.quantile(.75)))
print('book spread mean %.2f | candle close spread mean %.2f'%((J.ya-J.yb).mean(),((J.yes_ask_close-J.yes_bid_close)*1000).mean()))
