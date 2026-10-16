import sqlite3, pandas as pd, numpy as np
con=sqlite3.connect('file:/workspace/kalshi-history/kalshi15m.sqlite?mode=ro',uri=True)
M=pd.read_sql("select id,ticker,series,close_ts,floor_strike,result from markets where series in ('KXBTC15M','KXETH15M','KXSOL15M') and result in ('yes','no') and floor_strike is not null",con)
print(len(M))
C=pd.read_sql("""select c.market_id,c.end_ts,c.yes_bid_open,c.yes_bid_close,c.yes_ask_open,c.yes_ask_close,c.yes_ask_high,c.yes_bid_low
 from candles c join markets m on m.id=c.market_id where m.series in ('KXBTC15M','KXETH15M','KXSOL15M')""",con)
print(len(C))
M.to_parquet('prep/long_markets.parquet'); C.to_parquet('prep/long_candles.parquet')
