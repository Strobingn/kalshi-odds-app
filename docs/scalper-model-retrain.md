## Scalper model retrain

Test days (fitted and tuned on neither model): 2026-10-16 … 2026-10-22. New model: trained 2026-09-23..2026-10-11, threshold tuned on 2026-10-12..2026-10-15. Shipped model: trained 2026-09-18..2026-09-28, tuned on 2026-09-29..2026-10-02.

Cents per order posted, front of the queue (rest at the bid 20 s, +1¢ offer, 4¢ stop, 120 s):

| Orders | n | mean | 95% (by day) | days up |
| --- | ---: | ---: | --- | ---: |
| every order | 135,908 | +0.058¢ | [-0.01, +0.13] | 5/7 |
| shipped model's picks (θ -0.034¢) | 93,929 | +0.260¢ | [+0.21, +0.31] | 7/7 |
| new model's picks (θ +0.088¢) | 79,465 | +0.314¢ | [+0.27, +0.36] | 7/7 |

**Promote: yes.**

### Training (train_calibrated.py)
```
rows: train 376,272, valid 76,322, test 135,908; candidates 51; label: made money (> 0c), 87.6% of train rows
feature selection on VALID (profit lost when a feature is shuffled):
  51 features: VALID total $+125.9 with 177 trees; most profit lost when shuffled: spread +7.7, from_hi +7.2, dist50 +6.2, vol30 +3.8, from_lo +2.2, nchg120 +2.0
  38 features: VALID total $+125.6 with 160 trees; most profit lost when shuffled: dist50 +9.7, spread +7.8, from_hi +4.7, vol30 +4.3, nchg60 +2.2, hour +2.2
  31 features: VALID total $+127.7 with 181 trees; most profit lost when shuffled: dist50 +12.2, spread +9.1, from_hi +6.2, hour +5.7, nchg120 +5.1, vol30 +4.3
  28 features: VALID total $+127.5 with 171 trees; most profit lost when shuffled: spread +9.2, dist50 +9.0, from_hi +8.2, vol30 +6.4, hour +5.6, nchg120 +5.2
  26 features: VALID total $+128.1 with 182 trees; most profit lost when shuffled: dist50 +9.2, spread +9.1, vol30 +7.9, from_hi +7.6, nchg120 +7.1, hour +7.0
  25 features: VALID total $+126.2 with 145 trees; most profit lost when shuffled: dist50 +8.8, spread +7.8, from_hi +7.3, vol30 +6.6, hour +4.9, nchg120 +4.7
final: 25 features, 171 trees, max depth 5
  VALID top 100%: n= 76322 mean +0.028c total     +21.0$
  VALID top  85%: n= 66515 mean +0.134c total     +89.3$
  VALID top  70%: n= 57473 mean +0.206c total    +118.2$
  VALID top  50%: n= 42034 mean +0.315c total    +132.2$
  VALID top  30%: n= 29882 mean +0.382c total    +114.3$
  VALID top  20%: n= 18284 mean +0.477c total     +87.3$
  VALID top  10%: n= 10594 mean +0.557c total     +59.0$
theta +0.0877c (top 50% of VALID)
TEST: take everything +0.058c (n=135908); model picks n=79465 (58.5%) +0.314c 95% [+0.27,+0.36] days+ 7/7
TEST calibration: Brier raw 0.1154 -> calibrated 0.1154; expected calibration error 0.0045
TEST deciles (calibrated chance -> won / cents): 0.71->0.71/-0.75c; 0.81->0.81/-0.39c; 0.83->0.83/-0.19c; 0.85->0.85/-0.02c; 0.86->0.87/+0.07c; 0.88->0.88/+0.15c; 0.89->0.89/+0.23c; 0.90->0.90/+0.33c; 0.91->0.91/+0.40c; 0.93->0.93/+0.54c
gain: spread, vol30, nchg60, nchg120, from_hi, dm5, hour, dist50, bid, dm10
exported trees 171, nodes 6861, max |trees - lightgbm raw| on 300 rows: 0.00e+00
model json bytes 358192
```
### Queue penalty
```
check on [np.str_('2026-10-19'), np.str_('2026-10-20'), np.str_('2026-10-21'), np.str_('2026-10-22')] : every model pick vs only orders with prediction - penalty >= 0
      0 ahead: every pick +0.27c n=39868 | queue-aware +0.26c n=41406
    100 ahead: every pick -0.00c n=39868 | queue-aware +0.22c n=15598
    250 ahead: every pick -0.25c n=39868 | queue-aware +0.22c n=4970
    500 ahead: every pick -0.56c n=39868 | queue-aware +0.67c n=83
   1000 ahead: every pick -0.96c n=39868 | queue-aware takes 0 orders
   2000 ahead: every pick -1.38c n=39868 | queue-aware takes 0 orders
   5000 ahead: every pick -1.81c n=39868 | queue-aware takes 0 orders
parity fixture: KXBTC15M-26OCT220630-30 6060 trades 124 rows
```
