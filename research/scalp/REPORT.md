# 15m crypto scalp research (KXBTC15M/KXETH15M/KXSOL15M), 2026-10-09
Data: diphunter-recorder 3s books + 52.1M trades (Sep 25 to Oct 9), markets/results. Spot: Binance 1s klines BTC/ETH/SOL-USDT
(public data.binance.vision) as a CF RTI proxy. Strike in spot units = Binance 60s average before open, which removes the USDT basis.
Split: train = windows closing <= 2026-10-02 00:00Z (1,596 mkts). TEST = closes Oct 2 00:00Z to Oct 9 00:00Z (1,892 mkts), held out and evaluated once per locked pick.
Simulation: taker entry at the NEXT book's ask (>= 1 tick / 3s latency), limit = signal ask + 1c, size C=10 needs displayed top-level depth;
exit at the next book's bid with depth walk; fee = ceil(7*C*P*(1-P)) cents per order on both legs; maker fee 0 (series fee_type=quadratic, multiplier 1, checked via public API).
Maker sim: queue-aware (queue ahead = displayed qty at our level; fills need cum taker volume >= queue + C at our price, or a trade-through).
Features use only data at or before decision time (spot = last fully closed 1s bar; vol = trailing 900s).
CI = 95% bootstrap clustered by 15-min window (all 3 coins in a window resample together).
Parameter combos tried: 4,092 taker + 864 maker + 48 derived-rule = 5,004 (multiple-testing caution).
See oos_summary.csv and the scripts: panel.py, predict.py, sim.py, grid.py, maker.py, derived_train.py, mathparams.py, evaluate.py.

## 1. Math (derivation)
Contract fair value from spot: p = Φ(z), z = ln(S/K) / (σ·√τeff). Here τeff = τ − 40 s, because settlement uses the 60 s average and Var(mean of the last 60 s) = σ²·20 s.
The contract's diffusive move over h seconds is about φ(z)·√(h/τeff). At z = 0 with 7.5 min left, that is a 1σ move of about 10¢ in 30 s.
The moves are large, but they have no direction you can predict.

Round-trip cost per contract (taker both legs) = spread + f(P_in) + f(P_out), with f = ceil(0.07·C·P·(1−P)) cents per order:

| P | fee/ct C=1 | C=10 | C=100 | RT C=1 | RT C=10 | RT C=100 (1¢ spread) |
|---|---|---|---|---|---|---|
| 10/90¢ | 1.0 | 0.7 | 0.63 | 3.0 | 2.4 | 2.26 |
| 20/80¢ | 2.0 | 1.2 | 1.12 | 5.0 | 3.4 | 3.24 |
| 30/70¢ | 2.0 | 1.5 | 1.47 | 5.0 | 4.0 | 3.94 |
| 40/60¢ | 2.0 | 1.7 | 1.68 | 5.0 | 4.4 | 4.36 |
| 50¢ | 2.0 | 1.8 | 1.75 | 5.0 | 4.6 | 4.50 |

Optional stopping: if the mid is a martingale, then for ANY stopping rule (take-profit, stop-loss, trailing stop, "exit when it turns down"),
E[bid_exit] − ask_entry = −spread. So the expected net is −(spread + fees). Exit rules change the win rate, not the mean.
A scalp can only pay from a PREDICTABLE drift: E[Δmid_h | signal] > spread + f_in + f_out.

Linear predictability, estimated on data (5–14 min left, mids 10–90¢):
- Gap signal g = fair − mid. E[Δmid_h] = β_h·g. Train / test:

| h | β train | β test | δ train (share of the gap that closes by FAIR moving toward the market) | median abs(Δmid) |
|---|---|---|---|---|
| 15 s | 0.019 | 0.030 | 0.11 | 3.0¢ |
| 30 s | 0.030 | 0.049 | 0.15 | 5.0¢ |
| 60 s | 0.047 | 0.078 | 0.22 | 7.0¢ |
| 120 s | 0.099 | 0.120 | 0.31 | 10.5¢ |
| 300 s | 0.117 | 0.193 | 0.59 | 19¢ |
| settle | 0.177 | 0.129 | – | 37¢ |

  The gap has a half-life of about 40 s (AR(1) ρ = 0.95 per 3 s tick). Mostly it closes by the spot fair converging to the market, not the reverse.
  Only about 15% of a spot-implied "mispricing" is real, which matches the prior finding that the market beats the spot model.
- Dip signal (move over the last 15 s, m15): E[Δmid_30s] = b·m15 with b = +0.001 (train), −0.013 (test). That is essentially zero mean reversion.
- Breakeven (C=10, 50¢, 1¢ spread, RT 4.6¢):
  - dip-buy needs a dip of RT/abs(b), which is 350¢ or more. Impossible.
  - gap scalp exiting at 2 min needs g ≥ RT/β_120, about 40¢.
  - gap entry held to settlement needs 0.15·g ≥ half-spread + f_in, about 2.3¢, so g ≥ 15¢.
  - Gaps that large are rare and are mostly the proxy model being wrong.
- Derived rule: enter only when fair − ask − f_in ≥ X (X picked on train = 10¢). Exit when bid − f_out ≥ fair, i.e. selling beats holding.
  Turn-down exit when fair ≤ entry − 10¢. Time stop at 20 s left.

## 2. Walk-forward results (per contract, after both fees, C=10; CI = window-clustered 95% bootstrap)
(see oos_summary.csv; per-coin/tau/price breakdown for the derived rule in evaluate.py output)

| strategy (picked on train only) | set | round trips | windows | win | avg net ¢/ct | 95% CI | total $ (10 ct) | fees ¢/ct | med hold s |
|---|---|---|---|---|---|---|---|---|---|
| dip-buy mean reversion (30¢/30s drop, TP4/SL3/trail2, 1–5 min left) | train | 2705 | 415 | 29% | −3.70 | [−4.03, −3.37] | −1000 | 2.57 | 10 |
| | TEST | 3073 | 511 | 29% | −3.81 | [−4.29, −3.35] | −1172 | 2.55 | 11 |
| momentum (3¢/15s rise, TP6, 3 min hold) | train | 4640 | 531 | 66% | −3.15 | [−3.65, −2.63] | −1464 | 2.90 | 91 |
| | TEST | 5200 | 628 | 63% | −3.72 | [−4.28, −3.18] | −1932 | 2.83 | 106 |
| maker both legs, queue-aware (gap ≥10¢ entry bid, ask +4¢) | train | 2072 | 482 | 80% | −1.03 | [−1.64, −0.47] | −214 | 0.26 | 40 |
| | TEST | 2504 | 568 | 78% | −1.50 | [−2.02, −0.98] | −375 | 0.27 | 44 |
| fair-gap entry, hold unless bid beats fair (not a scalp, settles) | train | 817 | 409 | 46% | −1.06 | [−3.06, +0.87] | −87 | 2.48 | 273 |
| | TEST | 1035 | 478 | 48% | +0.03 | [−1.79, +1.88] | +3 | 2.41 | 304 |
| **DERIVED fair-gap SCALP (shipped as paper)** | train | 850 | 409 | 44% | −1.11 | [−2.79, +0.71] | −94 | 2.59 | 212 |
| | **TEST** | **1068** | 478 | 45% | **−0.40** | **[−2.04, +1.22]** | −43 | 2.51 | 243 |

Derived rule TEST breakdown:
- by coin: BTC +0.34¢ (n=575, CI [−1.77, +2.63]); ETH −1.64¢ (308, [−4.32, +1.20]); SOL −0.64¢ (185, [−4.18, +2.96]).
- by time-left: 5–7.5 min −1.20¢; 7.5–10 min −0.65¢; 10–14 min +0.11¢. All CIs straddle 0.
- by entry price: 10–30¢ +1.03¢; 30–50¢ −3.04¢; 50–70¢ −1.13¢; 70–90¢ +1.40¢. All CIs straddle 0.
- by exit: gap closed (take-profit) +8.2¢ (n=291); fair turned down −19.8¢ (493); time stop +27.8¢ (230); settled +10.3¢ (54).

Out of 5,004 combos, 0 had a positive mean in-sample (train). The best fair-gap taker variants come out at about −1¢/ct in-sample and about 0 out-of-sample.
Their CIs are 3–4¢ wide, so nothing survives. Even a CI lower bound of 0 would not be credible after 5,004 tries.

## Verdict
No 15-minute scalp survives fees out of sample. A round trip costs 4.0–4.6¢/contract at 30–70¢: about 1¢ spread plus two taker fees of about 1.5–1.8¢.
Moves over 30 s have a median size of about 5¢, but their predictable part is about 0. Mean reversion after dips is 0.0–1.3% of the dip.
Only 3–12% of a spot-vs-contract gap closes toward fair within 2 minutes. Maker-both-sides cuts fees to about 0.3¢ but gets adversely selected (−1.0 to −1.5¢).
The least-bad candidate is the derived fair-gap scalp: −0.40¢/ct OOS, CI [−2.04, +1.22]. It ships as PAPER only to collect real-time evidence.
