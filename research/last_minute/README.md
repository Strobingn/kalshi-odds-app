# Last-minute strategy (KXBTC15M)

This folder is the research source for DipHunter **0.3.16**. The app’s card pick is no longer the on-device AI blend. It is the **final-60-second taker rule** from `fine_trades.py` (`fair_p`) + `sim_fine.py` (`taker_bets`, variant **a**).

Bitcoin only. Series: `KXBTC15M`.

## Official settlement (cite these)

Kalshi’s 15-minute Bitcoin contract is an up/down binary on the **CF Benchmarks Bitcoin Real-Time Index (BRTI)**.

- **Kalshi Help — Crypto Markets:** [all crypto contracts settle by averaging 60 seconds of CF Benchmarks Real-Time Indexes](https://help.kalshi.com/en/articles/13823838-crypto-markets), sampled once per second in the last minute before expiration. The official expiration value is that 60-second average.
- **Kalshi contract terms (BTC):** [Underlying is a simple average of BRTI for the 60 seconds prior to the expiration time](https://kalshi-public-docs.s3.amazonaws.com/contract_terms/BTC.pdf). Source Agency: CF Benchmarks. Revisions after expiration are ignored.
- **Strike / YES (UP):** Kalshi’s `floor_strike` on the market object is the BRTI average over the **60 seconds before the window opens**. YES (UP) pays if the **final 60 s** BRTI average is **≥** that strike. Confirmed in the research rerun: `sign(BRTI close-avg − BRTI open-avg)` matched Kalshi’s `result` in 26,824 of 26,830 markets (`full_cf_btc` report).
- **Prices:** Kalshi V2 uses `*_dollars` FixedPointDollars, **up to 4 decimal places**. Integer-cent fields cannot represent sub-cent ticks. See [Fixed-Point Representation](https://docs.kalshi.com/getting_started/fixed_point_migration) and [Create Order (V2)](https://docs.kalshi.com/api-reference/orders/create-order-v2) (`POST /trade-api/v2/portfolio/events/orders`, `price` as `"0.0300"`).
- **Fees:** [Fee rounding](https://docs.kalshi.com/getting_started/fee_rounding) — `trade_fee = ceil_6dp(0.07·C·P·(1−P))`, then the non-direct-member debit is `ceil_cent(C·P + trade_fee)`.

## BRTI approximation in the app

CF Benchmarks publishes BRTI as a once-per-200 ms consolidated order-book index from CME CF constituent exchanges ([BRTI](https://www.cfbenchmarks.com/data/indices/BRTI), [constituent list](https://docs.cfbenchmarks.com/CME%20CF%20Constituent%20Exchanges.pdf)). Public constituents used here: **Coinbase, Kraken, Bitstamp, Gemini** (plus others such as Bullish / LMAX / Crypto.com that do not have simple public ticker APIs).

The app **cannot** subscribe to licensed BRTI. It uses a **live median** of Coinbase / Kraken / Bitstamp / Gemini public tickers (REST, polled) and falls back to the existing Binance/Coinbase spot feed. The card shows which source is live. Per-second vol uses **60 completed Coinbase 1-minute candles** (public `/products/BTC-USD/candles?granularity=60`).

## The rule (ported 1:1 to Kotlin)

Each second in the final minute (`tau` = seconds to close, `0 < tau ≤ 60`):

- `X = ln(spot_now / strike)`
- `obs_mean` = mean of `ln(spot_s / strike)` over seconds of the final minute already observed (`0` when `tau ≥ 60`)
- `sig_s` = per-second vol = `sqrt(mean of squared 1-minute log returns over the prior 60 minutes, min 30 completed minutes)` / `sqrt(60)`

`fair_p` (`fine_trades.py`):

```
sig = k * sig_s
if tau < 60:
    nobs = 60 - tau
    mean = (nobs * obs_mean + tau * X) / 60
    var  = (tau / 60)^2 * sig^2 * max(tau, 1) / 3
else:
    mean = X
    var  = sig^2 * (tau - 40)
P(UP) = Φ(mean / sqrt(var + eta^2))
```

Fitted params (`params.json`, last walk-forward fold train 2026-09-09…09-22): **`k = 1.1`**, **`eta = 0.0001`**, **margin `EV/$ ≥ 0.35`**.

For each side: `p = P(UP)` for UP/YES, `1 − P(UP)` for DOWN/NO. `P` is that side’s live best ask (dollars, 4 dp). Size: largest whole `C` with `all_in_cost(C, P) ≤ $10`:

```
all_in_cost = ceil_cent(C·P + ceil_1e-6(0.07·C·P·(1−P)))
EV/$ = (C·p − cost) / cost
```

Signal when `EV/$ ≥ 0.35`. **First qualifying second per window only.** Cap `C` by contracts actually resting at or below `P` in the live book.

No min-profit-if-win gate. Max stake **$10** (user can pick less). Live orders still need **Approve + REAL MONEY**. No auto-bet.

## Why this replaced the AI pick

Full-history walk-forward of the **shipped AI model** (`wf_btc_full.md`, harness @ 9defbf8):

| slice | bets | W-L | win% | won $ | lost $ | net P&L $ | $/bet [95% CI] |
|---|---|---|---|---|---|---|---|
| App pick as shipped (alert gate) = DipHunter model | 13,903 | 9,007-4,896 | 64.8% | 20,964.00 | 22,875.07 | **−1,911.07** | −0.137 [−0.194, −0.083] |

Brier: model fair_yes **0.1582** vs market mid **0.1561** (model loses after fees; worse than market). Gate: **FAIL**.

The same era on **real BRTI** (`full_cf_btc_report.md`, `SPOT=cf`, `COINS=KXBTC15M`, `$5` stake, variant **a**):

| segment / variant | bets | W-L | win% | won $ | lost $ | net P&L $ | $/bet [95% CI] | gate |
|---|---|---|---|---|---|---|---|---|
| **final60\|a** | **7,696** | **1,290-6,406** | **16.8%** | **54,189** | **31,794** | **+22,395** | **+2.910 [+1.163, +5.114]** | **PASS** |
| final60\|b | 7,361 | 688-6,673 | 9.3% | 57,522 | 33,164 | +24,358 | +3.309 [+1.451, +5.588] | PASS |
| <=2m\|a | 13,787 | 2,233-11,554 | 16.2% | 73,371 | 57,174 | +16,197 | +1.175 [+0.345, +2.176] | FAIL: max day share 0.30 |
| all\|a | 24,279 | 8,908-15,371 | 36.7% | 92,098 | 74,337 | +17,761 | +0.732 [+0.515, +0.966] | PASS |

Dirk’s shipped pick is **final60\|a** (EV/$ ≥ margin, **no** profit-if-win floor). Last-fold train picked margin **0.35** at both $5 and $10 (`params.json`).

Depth-capped (tape size at that print) still **+17,455** on final60\|a. 2-second BRTI latency still **+10,287**.

## Files (no data)

| file | role |
|---|---|
| `fine_trades.py` | Features + `fair_p` |
| `sim_fine.py` | Walk-forward, `taker_bets`, margin grid |
| `common.py` | `all_in_cost`, `size_bet`, folds, gate |
| `params.json` | Last-fold `k`, `eta`, margins |
| `last_fold_params.py` | How `params.json` was written |

Do not add SQLite / npz / pickle dumps here.
