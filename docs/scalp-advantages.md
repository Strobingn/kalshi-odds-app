# Scalp advantages investigation — maker fills, fee geography, spot lead-lag, regimes

Date: 2026-10-09. Follow-up to `docs/scalping-params.md` (which showed all
2,100 taker combos of the dip-buy scalp lose ≈ −4.6¢/contract/trade). This doc
asks whether *any* execution or conditioning change flips the sign.

Data: same cache — 7,968 settled 15-min markets (2,656 each BTC/ETH/SOL,
2026-08-28 → 2026-09-27, one 1-min candle per minute, 16 per market) plus
Coinbase spot 1-min series. New scripts (read-only, no app code touched):

- `tools/backtest/scalp_maker.py` — maker-entry simulation + fee geography
- `tools/backtest/scalp_regimes.py` — spot lead-lag, time-of-day, quote regimes

## Verified 2026 Kalshi fee schedule

- **Official schedule** (kalshi.com fee-schedule PDF, fetched live 2026-10-09;
  "3rd iteration, October 2024", still the published document):
  *"Trading fees are only charged for orders that are immediately matched with
  orders sitting on the orderbook. Trading fees are not charged for orders
  placed that are not immediately matched and are instead left as resting
  orders on the orderbook."* Taker fee: `round_up(0.07 × C × P × (1−P))`, no
  settlement/processing/membership fee.
  https://kalshi.com/docs/kalshi-fee-schedule.pdf
- **Maker fee is NOT universally zero in 2026.** Third-party trackers of the
  schedule report a maker rate of 0.0175 × C × P × (1−P) (25% of taker) on
  *designated series only* (~1% of contract families); the default maker
  multiplier is 0. A series-specific measurement on **KXBTC15M reports maker
  fee = 0** across 6,298 settled windows.
  https://www.botforkalshi.com/blog/can-you-bet-on-bitcoin
  https://coinstats.app/prediction-markets/apps/kalshi/fees/
  https://blog.predictefy.com/kalshi-fees
- **Action before shipping anything**: confirm the live series multiplier for
  KXBTC15M/KXETH15M/KXSOL15M from the API (`markets` payload fee fields or a
  1-contract probe order), since the schedule can change per series. All
  simulations below assume maker fee = 0 and taker fee = ceil_6dp(0.07·P·(1−P))
  per contract per side (consistent with `scalp_grid.py`).

## TL;DR verdicts

| angle | best net in sim | verdict |
|---|---:|---|
| 1. Maker fills (rest bid entry, maker/taker exit) | **−2.11¢/trade** (best of 2×2,700 combos; 0 positive) | improved ~2.5¢ vs taker, still no-edge |
| 2. Fee geography (enter at mid ≤15¢ / ≥85¢) | −3.78¢ (n=311); +6.9¢ on n=4 = noise | no-edge |
| 3. Spot lead-lag (Coinbase → Kalshi) | −1.35¢ best net (n=230); no minute-scale lead | no-edge at 1-min; needs tick data |
| 4. Time-of-day | dip lift +5.5…+9.9pp, base 80–85% everywhere | no-edge |
| 5. Quote regime (spread tight/wide, early/late window) | late-window dip bounce 97.6% yet −4.91¢/trade | no-edge |

**No approach cleared fees positive in simulation.** The maker entry is the
only angle that materially changed the economics (it removes ~2.4¢ of the
~4.6¢ taker bleed), but the dip signal itself has no measurable alpha, so even
near-zero-friction entry cannot rescue it.

---

## 1. Maker-side fills (`scalp_maker.py`)

**Method.** Same EMA(5) dip trigger as `scalp_grid.py`. At the dip minute,
place a **resting bid at mid − k¢** (k ∈ 1–4), maker-valid only if the price is
below the current ask close. The bid rests up to W ∈ {2,3,5} minutes and fills
if a bar LOW touches it (mid_low = (yes_bid.low + yes_ask.low)/2). Exit mode
**maker**: rest an ask at fill_mid + m¢ (m ∈ 2–6) for T ∈ {3,5,8} min — zero
fee when a bar HIGH touches it; stop-loss (optional 3¢/5¢) and timeout cross
the spread (taker fee). Exit mode **taker**: scalp_grid-style TP/SL/timeout at
bid closes (zero entry fee, taker exit fee). Grid: 2 modes × 5 dips × 4 k ×
5 m × 3 SL × 3 W × 3 T = 5,400 combos. Entry fee = 0 (maker), per contract.

**Fill rates** (dip minutes only; a bid k¢ below mid fills 75–95% of the time
within 2–5 min — these are short-window binary mids, very mean-reverting bar
to bar):

| dip | k | W | placed | filled | fill rate |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 2 | 6,843 | 6,120 | 89.4% |
| 1 | 4 | 2 | 6,843 | 5,120 | 74.8% |
| 5 | 1 | 2 | 6,183 | 5,606 | 90.7% |
| 5 | 4 | 2 | 6,183 | 4,744 | 76.7% |
| 5 | 4 | 5 | 6,183 | 5,394 | 87.2% |

(full 60-row table in the script output)

**Adverse selection (the maker's hidden cost).** Average mid markout after
fill is *positive* (+0.2…+0.4¢ at +1 min — dips do bounce on average), but the
worst-case 2-min markout averages **−4.0 to −4.2¢**: filled trades include a
heavy left tail where the mid keeps falling through the bid (exactly the
toxic-flow pattern makers are compensated for on real exchanges — here the
"compensation" is only the k¢ price improvement).

**Top combos (exit mode MAKER, filled ≥ 200):**

| dip | k | m | sl | W | T | filled | win% | avg ¢ | PF | maker exits | taker exits |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 4 | 2 | – | 2 | 8 | 5,120 | 48.2% | **−2.113** | 0.66 | 4,551 | 569 |
| 1 | 4 | 2 | – | 2 | 5 | 5,120 | 47.1% | −2.120 | 0.65 | 4,434 | 686 |
| 3 | 4 | 2 | – | 2 | 8 | 4,949 | 47.5% | −2.203 | 0.65 | 4,392 | 557 |
| 5 | 4 | 4 | – | 2 | 8 | 4,744 | 52.8% | −2.251 | 0.69 | 3,957 | 787 |

**0 of 2,700 combos positive per mode.** Best maker mode −2.11¢ vs the
taker-everywhere −4.6¢ baseline: the zero-fee entry + maker-exit 89% of the
time saves ~2.5¢/trade, but the signal's gross edge (idealized +0.37¢ from the
previous doc) is far too small. Exit mode taker with maker entry: best
−2.23¢ — same story.

**What 1-min bars can't resolve.** Both the entry fill (bar LOW touch) and the
maker exit (bar HIGH touch) assume favorable intrabar ordering — the sim is
*optimistic*, so live results would likely be worse, not better. True resting
fill quality (fill rate at k=1–2¢, time-to-fill distribution, post-fill markout
at seconds resolution) is only measurable with live paper orders. This is the
one angle where live paper data could still change the answer, because the sim
removes most friction already: the remaining question is purely "does the dip
have any alpha at all," and a paper run of the maker bot directly measures it.

**Implementation sketch (paper-first, engine `signal`/`scalp/`):**
1. Signal unchanged (EMA-5 dip, minutes 2–13); on trigger compute
   `bid_price = clamp01(mid − k¢)` with k=4, W=2 min patience; **skip if
   bid_price ≥ best_ask** (would take) or if spread > 2¢.
2. Place GTC limit buy; on fill (WS `fill` event, not REST poll) immediately
   rest ask at `fill_price + m¢` (m=2…4), T=8 min; cancel-and-market the exit
   on timeout; hard stop −5¢ via market.
3. Log every event: place/cancel/fill ts, quote at each, and post-fill mid
   markouts at +5s/+30s/+60s → adverse-selection dashboard.
4. Go-live gate: ≥300 paper trades, net ≥ +1¢/contract AFTER real fills, PF ≥
   1.2, and median markout +5s ≥ 0 (fills not toxic). Also verify the live
   maker fee = 0 for the series before first order.

## 2. Fee geography

**Distribution of cached mids** (127,488 candle-minutes) and the taker fee a
round trip would pay if entered there:

| mid band | minutes | share | avg round-trip taker fee |
|---|---:|---:|---:|
| ≤15¢ | 23,668 | 18.6% | 0.86¢ |
| 15–35¢ | 18,129 | 14.2% | 2.76¢ |
| 35–65¢ | 42,483 | 33.3% | 3.40¢ |
| 65–85¢ | 18,130 | 14.2% | 2.42¢ |
| ≥85¢ | 25,076 | 19.7% | 0.39¢ |

The fee-drag difference is real: trading only near-extreme mids saves ~3¢ of
fees round trip. **But the dip signal almost never fires there**, and when it
does the spread/penalty dominates: restricted re-run of the best taker combo
(dip5,tp4,sl5,mh7, 0.5¢/side penalty):

| restriction | trades | win% | avg ¢/trade |
|---|---:|---:|---:|
| unrestricted | 6,183 | 42.7% | −4.822 |
| mid ≤15¢ | 311 | 36.7% | −3.778 |
| mid ≥85¢ | 4 | 100.0% | +6.925 (noise) |
| mid in 15–85¢ | 5,868 | 43.0% | −4.886 |

**Verdict: no-edge.** Cheap fees at extremes don't help because (a) a deep
dip below EMA at a 5–15¢ mid usually means the market is trending to 0, not
mean-reverting, and (b) sample sizes at the extremes are tiny. The one
plausible use of fee geography is as a *filter on the maker bot* (don't scalp
when mid ∈ 35–65¢ where fees peak if any leg must take) — worth carrying into
the paper config, not a standalone edge.

## 3. Spot lead-lag (Coinbase BTC-USD vs Kalshi KXBTC15M mid, 39,8xx aligned minutes)

**Timestamp convention.** Coinbase bars are labeled by bar start, Kalshi
candles by `end_ts`; aligning spot ts+60 to Kalshi end_ts maximizes the
contemporaneous correlation (0.188 vs −0.011 for the naive alignment).

**1-minute return cross-correlation (correct alignment):** lag 0 = **0.188**,
all other lags ≈ 0 (±0.01–0.02). There is **no minute-scale lead**: spot and
the Kalshi mid move contemporaneously. Any exploitable lead would have to live
*inside* the minute, which 1-min bars cannot resolve (the prompt's 1s–60s
question needs tick/trade data from both venues).

**Tradable test anyway** — "spot moved ≥ θ bps over last L min → trade Kalshi
in that direction for H min" (entry ask / exit bid, taker fees, all 39,8xx
minutes evaluated):

| L | θ (bps) | H | trades | hit rate | avg gross ¢ | avg net ¢ |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10 | 1 | 1,865 | 54.7% | +0.456 | −1.982 |
| 1 | 20 | 1 | 230 | 57.0% | +0.682 | **−1.346** |
| 2 | 30 | 1 | 227 | 62.1% | −0.956 | −2.771 |
| 5 | 30 | 1 | 816 | 58.3% | −1.629 | −3.218 |

Hit rate rises with θ (momentum is real) but the Kalshi mid **drifts against
the signal by ~2–4¢ over multi-minute holds** — the mid is a strike-distance
transform, not a spot tracker, so "spot went up" does not imply "mid goes up
next minute" beyond the contemporaneous co-movement you can't capture.

**Verdict: no-edge at 1-min resolution; unresolved (needs tick data) for
sub-minute.** If anything were to be paper-tested here it is a *maker* version:
rest an offer 1–2¢ through the Kalshi quote in spot's direction and let the
contemporaneous co-movement fill you — zero-fee entry plus the 0.188
co-movement. But with no measured lead, expected gross capture is ≈0 and
adverse selection would be the whole P&L. Rank this below the dip-bot for
paper priority; only revisit with tick data showing a real seconds-scale lead.

## 4. Time-of-day (UTC hour, all coins)

| hour | all-min +2¢/10min | dip-min rate | lift |
|---|---:|---:|---:|
| best lift | | | 16:00 +9.9pp |
| worst lift | | | 06:00 +5.5pp |
| range of base rate | 80.9%–85.1% | 87.9%–92.5% | |

(full 24-row table in script output)

24/7 crypto books mean every UTC hour is equally populated (~4,032
minutes). Base bounce rate spans only 80.9–85.1%, dip lift +5.5…+9.9pp with no
session structure (no US/EU/Asia volatility concentration worth routing on).

**Verdict: no-edge.** Not worth an hour gate. At most, record trade hour in
the paper logs so a post-hoc session analysis is possible if live data
disagrees with this cache month.

## 5. Quote regime: spread tightness × window position (creative angle)

| regime | minutes | all-min bounce | dip-min bounce | dip share | best-combo trades | avg ¢/trade |
|---|---:|---:|---:|---:|---:|---:|
| spread ≤2¢ | 86,983 | 82.2% | 90.7% | 48% | 5,549 | −4.709 |
| spread >2¢ / n.a. | 8,633 | 86.9% | 88.3% | 43% | 634 | −5.816 |
| early window (min 2–7) | 47,808 | 85.6% | 83.3% | 47% | 5,339 | −4.809 |
| late window (min 8–13) | 47,808 | 79.7% | **97.6%** | 48% | 844 | −4.908 |

Two clean findings, both negative:

1. **Tight spreads don't rescue the trade** (−4.71¢ on 91% of minutes vs
   −5.82¢ wide) — the fee curve, not the spread, is the binding constraint.
2. **The late-window "dip" is an illusion of mean reversion.** Dips in
   minutes 8–13 bounce +2¢ within 10 min 97.6% of the time — but mostly
   because the mid is converging to a settled 0/1 and *any* uptick counts as a
   bounce; the strategy's TP/SL race still loses −4.9¢/trade (844 trades).
   Conditioning on this would *increase* the bleed rate while looking great on
   a naive hit-rate dashboard. Do not gate the feature on the late-window
   bounce rate.

**Verdict: no-edge**, with a concrete warning for any live dashboard: report
net ¢/trade by regime, never raw bounce rates.

---

## What would settle the open questions

1. **Live maker paper run** (the only angle not dead in sim): 300+ resting
   orders at k=1–4¢ with full event logs. Settles: real fill rate, seconds-scale
   adverse selection, whether the series truly charges makers 0.
2. **Tick data from both venues** (Kalshi trade feed + Coinbase aggTrades):
   sub-minute lead-lag. If a 2–10s lead existed, a maker-only scalp resting
   through the Kalshi quote could capture it with zero fees; the 1-min cache
   can neither confirm nor exclude this.
3. **Intrabar path**: any TP/SL race result at 1-min granularity assumes an
   ordering; live paper trades replace the assumption with measurement.

## Repro

```bash
python tools/backtest/scalp_maker.py     # maker grid + fill rates + geography
python tools/backtest/scalp_regimes.py   # lead-lag + time-of-day + regimes
python tools/backtest/scalp_grid.py      # original taker baseline (unchanged)
```
