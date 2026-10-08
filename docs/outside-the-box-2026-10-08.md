# "Outside-the-box" report vs. this repo (2026-10-08)

A third-party report ("Outside-the-Box Ways to Win Kalshi's 15-Minute Bitcoin
Market") lists structural edges on KXBTC15M. This maps each one to what the
repo already has, what it contradicts, and what is new. Nothing here is
evidence of an edge; it is a to-do list with the duplicates removed.

## Agrees with what we measured

| Report says | Repo |
|---|---|
| Taker fee beats the directional edge (6,298 windows: +0.67¢ gross vs 1.55¢ fee) | Same answer from our own tape: −1.01¢/contract at the ask, mostly fee (`docs/tape-study-2026-10-04.md`) |
| No maker fee on KXBTC15M | Confirmed by `fee_probe.py` 2026-10-07 (`docs/kalshi-fees-and-incentives.md`) |
| Settlement is the 60 s CF Benchmarks RTI average; ties pay YES; next strike = previous average | Built in: 1.8.0 subscribes to `cfbenchmarks_value` and uses the running average in the final minute; `endgame_maker.py`, `settlement_study.py` |
| Watch the RTI, not one exchange | 1.8.0 + index recording (`index_*.csv.gz`); Coinbase points the wrong way in ~6.7% of windows |
| Be a maker | `MakerEdge`, "Rest 1¢ better", `maker_sim.py`, flow-fade resting bid (paper) |
| Favorite-longshot bias: 80–95¢ beats 5–20¢ | Tape study: buyers at 20–30¢ lost 10.0% of stake; late-favorite paper ledger exists |
| Kalshi vs Polymarket "arb" is mostly a trap; ladders must be monotone | `xvenue_scan.py` (labels every gap VERIFY RULES), `arb_scan.py` (structural combos with real depth) |
| Options-implied probability as a cross-check | `options_edge.py` (Deribit smile vs KXBTCD) |
| Manipulation is a 5-minute problem, not a 15-minute one | Consistent with our settlement study; no action needed |

## Where the report is out of date or does not apply at our size

- **Volume incentives.** Kalshi filed to end the Volume Incentive Program
  (2026-09-28, effective no earlier than 2026-10-13). Do not plan around it.
- **Liquidity Incentive Program.** Real, but the only crypto series with a
  live program is KXCRYPTOLEAD15M (Coin Race), not KXBTC15M, and a payout
  must reach $1.00 per market and period. At 10-contract size on books
  2,000+ deep that is almost certainly $0.
- **Trimmed averaging.** CF Benchmarks applies it "on certain markets". We
  have not seen it in the KXBTC15M rules; the settlement math in the app
  assumes a plain 60 s mean. Verify in the contract terms before using it.
- **APY on idle cash.** Free money if the balance qualifies, but it is a
  balance decision, not a trading edge, and nothing to build.

## New and testable here

1. **Window-to-window reversal** (arXiv 2608.21888: 15-minute sign reversal,
   flip rate rising with move size). Not tested before. Built:
   `tools/research/reversal_study.py` + workflow "Reversal study". It also
   asks the question that decides whether it is money: does Kalshi's price
   one minute into the next window already price the flip?
2. **Two-sided resting bids** (YES bid + NO bid < $1, no maker fee).
   Built: `tools/research/pair_maker.py` (PR #58 runs it on the recorder's
   data).
3. **Regime filter** (skip 8:30 ET prints, funding hours 00/08/16 UTC, the
   21:00 UTC depth trough). The tape study has an hourly cut; a pre-registered
   skip list evaluated OOS would be cheap on the same data.
4. **Quarter-hour opening imbalance.** `open_study.py` tests the first 90 s
   for mispricing; it does not yet use taker imbalance in those seconds as a
   signal for the rest of the window.

## Endgame maker re-run, 2026-10-08 (6 recording days, 440 BTC markets)

`endgame_maker.py` (resting bid on the side the settlement-aware fair favours,
last 90/60/30 s) now has the 6 days its pre-registration needs, but the
**primary hypothesis is still NOT EVALUABLE: 21 fills < 30** (pooled,
T-60 s / improve / margin 3¢: +$0.37 per fill, 99% CI [−2.08, +1.57]).

- T-90 s loses (pooled join m0.03: −$0.78/fill, 99% CI excludes 0).
- T-60 s join is positive but not significant (+$0.46 to +$0.82/fill).
- **The settlement-aware fair is overconfident late:** fills priced ~10–20¢
  under fair (fair ≈ 75¢), yet those sides won only ~60–67%. This study uses
  Coinbase + an open-time basis, not the CF index the app uses since 1.8.0,
  so check the app's final-minute fair against settled outcomes before
  trusting late picks.
- **Lead, not a claim:** in near-50/50 markets at T-30 s, YES won 64% (45
  markets, 5 days) and NO bids that filled lost $3.1/fill (99% CI excludes
  0). Could be one up-trending week. Re-check when more days are recorded.

Re-run when the recorder has ~3 more days (primary needs ≥ 30 fills).

## Reversal study result, 2026-10-08 (90 days, 8,516 back-to-back KXBTC15M pairs)

**The reversal is real, Kalshi already prices it, and it does not pay.**

| prev move | pairs | flip rate | implied flip @1m | taker $/ct (95% day CI) |
|---|---|---|---|---|
| [0,5) bps | 3,257 | 50.0% | 50.8% | −0.030 [−0.043, −0.016] |
| [5,10) bps | 1,945 | 50.6% | 51.2% | −0.028 [−0.050, −0.009] |
| [10,20) bps | 1,965 | 51.5% | 51.0% | −0.016 [−0.039, +0.005] |
| [20,40) bps | 1,038 | 52.1% | 50.9% | −0.010 [−0.040, +0.020] |
| ≥40 bps | 311 | 54.0% | 52.2% | −0.003 [−0.059, +0.047] |

- The flip rate rises with the size of the previous move exactly as the
  paper says (50.0% → 54.0%), but the price one minute into the next
  window already implies most of it (50.8% → 52.2%).
- Pre-registered rule (bet the flip when |move| ≥ 20 bps): IS 871 bets,
  53.6% flips, +0.3¢/ct; **OOS 478 bets, 50.6% flips, −3.0¢/ct, 99% CI
  [−9.2¢, +3.9¢]. Decision: NO EDGE SHOWN.** The IS flip rate did not hold.
- The maker column (rest 1¢ above the bid, assumed filled, no adverse
  selection) is positive, but it is an upper bound only; the pair-maker
  study measured a 6¢ markout on exactly that kind of fill.
- Implication for the model: `prev_window_return` (Mis_bitcoin's new edge
  model input) should earn ~0 weight, because the mid it is offset from
  already carries this information.
