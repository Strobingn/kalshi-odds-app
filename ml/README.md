# DipHunter offline edge trainer

Trains a compact model on settled Kalshi **BTC / ETH / SOL 15-minute**
markets plus Coinbase public spot candles. The default is a
**market-anchored offset logistic**:

```
logit P(YES) = logit(clip(mid, 0.001, 0.999)) + b + Σ w_i · (x_i − mean_i) / std_i
```

`logit(mid)` is a fixed offset, not a learned feature. With `w = 0, b = 0` the
model is exactly the Kalshi mid, so it cannot do worse than the market by
construction; every weight has to earn its place out of sample. L2 (toward 0,
i.e. toward the market) Newton / IRLS, run to convergence. Only
`dist_to_strike_vol`, `market_mid`, `cross_asset` and `digital_fair` get
weights by default (`--features`); the rest export as 0 because the app
can't rebuild them the same way.

Data: **every decision minute** (elapsed minutes 1–13) of **every** settled
market in the `--days` window (≈100k rows for 30 days). Spot is only what had
closed by the decision candle (no look-ahead).

Validation is walk-forward and time-ordered, with fold boundaries on market
close times. It reports hold-out Brier / log-loss of the model **and** of the
market mid (`beats_market` needs both), plus a simulated P&L that picks the
side by **expected value at the ask** — the app's `EvSide` rule:

```
ev_yes = p − yes_ask − fee(yes_ask)
ev_no  = (1 − p) − no_ask − fee(no_ask)        # no_ask = 1 − yes_bid
bet the larger if it is > 3¢ (--ev-margin), else skip
```

One bet per market (first qualifying minute) at the candle-close ask, fee
`0.07·P·(1−P)` per contract.

The export is a few dozen floats. The phone only **infers** it — it does not
train the heavy 0.3.0 stack.

## One command

```bash
python3 ml/train_edge.py
```

Writes `ml/edge_model.json` (`"kind": "offset_logistic"`). Import that file in
the app: **Data → Import model**.

Options:

```bash
python3 ml/train_edge.py --days 30 --out ml/edge_model.json
python3 ml/train_edge.py --cache tools/backtest/cache   # train on the backtest pull, no network
python3 ml/train_edge.py --kind logistic                # legacy model, blended 0.35 with the mid
python3 ml/train_edge.py --l2 0.1 --features dist_to_strike_vol,digital_fair
python3 ml/train_edge.py --fixture   # no network; synthetic walk-forward
```

`--max-markets 0` (default) pages every settled market in the window at
≤ 10 Kalshi reads/s. Synthetic / fixture runs always write
`beats_market: false`.

Python 3.10+ standard library only (no pip packages).

GitHub Actions: **Actions → Train edge model → Run workflow**, or the weekly
Monday cron. The JSON + `edge_model_manifest.json` are uploaded as an artifact
and published on the rolling `mis-bitcoin-edge-model` release (this branch's app
reads that tag; `edge-model-latest` belongs to the main app). In the app:
**Data → Get latest model**.

## Features (order is the Android contract)

| # | Name | Meaning |
|---|------|---------|
| 0 | dist_to_strike_vol | ln(S/K) / (σ √T) |
| 1 | tte_frac | seconds-to-expiry / 900 |
| 2 | market_mid | YES mid 0–1 |
| 3 | imbalance | book imbalance [-1,1] |
| 4 | spread | ask − bid |
| 5 | momentum | mid now − mid window start |
| 6 | realized_vol | recent mid std |
| 7 | cross_asset | 5-minute spot return (app `spotReturn5m`) |
| 8 | time_of_day | minutes since midnight / 1440 |
| 9 | digital_fair | vol digital P(S_T > K) |

## Honest numbers

The script prints hold-out `model_brier` vs `market_brier` (and log-loss). If
the model does not beat the market on both, the app does not activate it. The
app also hides “edge” flags until |model − market| > fee + margin, and the
ticket side is whichever side has EV > 3¢ at the ask — often neither.

## Promotion gate (this branch)

Two gates must both pass before a model may influence live sizing:

1. `beats_market` — out-of-sample Brier **and** log-loss below the market mid
   on the walk-forward holdout (same as before).
2. `promotion_eligible` — NEW: at least 20 EV-at-ask simulated trades and the
   **market-block bootstrap 90% P&L CI excluding zero**
   (`boot_ci_low > 0`). The bootstrap resamples whole markets (their minutes
   share one settlement), not minutes, so a single lucky window cannot
   promote a coin flip.

Reference numbers from a live 1-day pull (288 markets, 3,744 minutes):
model Brier 0.1652 vs market 0.1656, ECE 0.0200 vs 0.0279, sim P&L +$1.95 over
145 bets, but bootstrap CI [-0.031, +0.188] with P(loss) = 0.12 —
`beats_market: true`, `promotion_eligible: false`. The model is honest and
stays advisory. That is the intended behavior: a scoring win with a P&L CI
straddling zero is not money.

The manifest also publishes `model_calibration_error` /
`market_calibration_error` (ECE) and `boot_p_of_loss`. On the phone,
`ModelActivation.decide` refuses activation when `promotion_eligible` is
false even if `beats_market` is true; manifests without the flag (older
trainers) are judged on scores only.

## 270-day training, L2 sweep, recency check (this branch)

- **`--days 270` is the workflow default** now. Kalshi's historical tier
  reaches back to Dec 2025 for KXBTC15M; the trainer pages both tiers, so a
  270-day window is ~30k settled markets / ~400k decision minutes — roughly
  100x the 1-day fit. If a run times out, dispatch the workflow with a
  smaller `days`.
- **`--sweep-l2`**: L2 is selected by holdout log-loss over a fixed grid
  (0.5 → 0.005). Selection happens inside the walk-forward folds, so the
  published gate numbers are never chosen on their own holdout. Log-loss,
  not Brier, is the selection score: clustered minutes reward memorization
  and log-loss punishes overconfidence.
- **Recency metrics**: `recent_model_brier` / `recent_market_brier` score
  the most recent 25% of the holdout separately. Crypto regimes drift; a
  full-holdout win that goes stale on recent data shows up here before
  promotion.
- **Margin curve**: `margin_curve` in the printed metrics shows EV-at-ask
  P&L per bet at margins 0¢–12¢. The app fires at 3¢; if a higher margin
  shows materially better P&L per bet with enough trades, raising the
  threshold is the cheapest improvement: fewer, better bets. Diagnostic
  only — the promotion gate stays the 3¢ bootstrap CI.

## Honest gates, v2 (day-block bootstrap)

A 0.01% Brier improvement with two simulated bets never activates a model.
`promotion_eligible` now requires:

- beats_market on BOTH holdout Brier and log-loss (as before), AND
- at least **200** EV-at-ask simulated trades, AND
- the **day-block** bootstrap 90% P&L CI excluding zero. Days (UTC close
  day) are the resample unit — a day's settlements share a vol regime, so
  one lucky day cannot carry the gate. `ml/bet_log.json` in the model
  release lists every simulated bet (ticker, minute, side, ask, EV,
  outcome) so any "what were the bets" question is answerable directly.

The app's auto-tuner got the same treatment (`EdgeAutoTuner`): it needs
100 settled signals, ≥30 bets at the chosen threshold, and a positive
10th-percentile EV under day resampling before the app stops sitting
out. `SignalConstants.AUTO_TUNE_*` hold the knobs.

## EWMA volatility

Realized vol is now an EWMA (λ = 0.86) over up to 60 one-minute returns
instead of the 16-bar sample std, in both the trainer
(`realized_vol_annual`) and the app (`ExternalMarketFeatures.realizedVol`)
— identical formula, so train/serve parity holds. Sigma is the digital
fair's only parameter; the smoother estimate brought the app's fair value
about 30% closer to the market in testing.

## Settlement-index recording

`MarketDataRecorder` now records the CF Benchmarks index stream
(`index_YYYY-MM-DD.csv.gz`: ts_ms,asset,index_id,value,avg_60,
final_minute_avg, ~1/s per asset) whenever recording is on. Coinbase
points to the wrong side of the target in ~6.7% of BTC windows; after
2-4 weeks of index rows the trainer can learn from the real settlement
source (`load_backtest_cache` path will read them).

## 15-minute reversal feature (prev_window_return, feature #10)

arXiv 2608.21888: at 15-minute horizons crypto shows pervasive out-of-sample
sign reversion (90% of 183 Binance pairs; flip rate 50.2% -> 53.0% by prior
move size), concentrated after flow-driven moves. Implemented as a parity
feature in the offset model:

- Trainer: `prev_window_return` — simple return over the previous window
  (open-900s .. open) from Coinbase bars that closed by the open (no
  look-ahead), clipped to ±5%.
- App: `SpotTape.returnOver(15 min)` -> `StreamSpot.return15m` ->
  `AssetSpotFeatures.prevWindowReturn` -> `EdgeFeatures.Raw.prevWindowReturn`
  (feature #10, SIZE 11).

The model can now learn the tilt instead of hand-coding it; if the fitted
weight is ~0 on 270 days of data, the reversal is not capturable here
either — an honest result the L2 sweep will surface.

(Changelog note: the reversal-feature training run is dispatched via a
push touching ml/ — see the workflow paths filter.)
