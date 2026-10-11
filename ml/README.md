# DipHunter offline edge trainer (schema 2)

Trains a **market-offset** logistic on every decision minute (1…13) of settled
Kalshi **BTC / ETH / SOL 15-minute** windows, using the backtest cache
(Kalshi 1m candles + Coinbase USD 1m spot):

    logit P(YES) = logit(mid) + b + Σ w_i · z_i

With every weight at zero it *is* the Kalshi mid, so it cannot do worse than
the market by construction — any weight has to earn its place on held-out days.

The export is a few dozen floats. The phone only **infers** it.

## One command

```bash
python3 ml/train_edge.py --cache tools/backtest/cache           # cache already fetched
python3 ml/train_edge.py --fetch --days 30                     # fetch first (what CI does)
```

Writes `ml/edge_model.json` + `ml/edge_model_manifest.json`. Import in the app:
**Data → Import model**, or **Data → Get latest model** for the weekly release.

`--fixture` trains on synthetic rows for smoke tests. Its output says
`"fixture": true` and the app refuses to load it. A real run with too little
data **exits non-zero** — it never falls back to synthetic data (the schema-1
trainer did, and published a fake Brier 0.024 model the app would activate).

Python 3.10+ standard library only.

## Features (order is the Android contract — `EdgeFeatures.kt`)

| # | Name | Meaning |
|---|------|---------|
| 0 | digital_gap | logit(Φ(d2)) − logit(mid), clipped ±4; 0 without spot |
| 1 | dist_to_strike_vol | ln(S/K) / (σ √T), clipped ±8 |
| 2 | spot_ret_1m | spot now / spot 60 s ago − 1 |
| 3 | spot_ret_5m | spot now / spot 300 s ago − 1 |
| 4 | tte_frac | seconds-to-expiry / 900 |
| 5 | spread | yes ask − yes bid |
| 6 | tod_sin | sin(2π · UTC second-of-day / 86400) |
| 7 | tod_cos | cos(…) |
| 8 | has_spot | 1 when spot, strike and σ were available |

σ = EWMA (half-life 10 bars) of completed 1m Coinbase log returns over the last
60 bars, annualized (`DigitalOptionFairValue.sigmaFromCloses`). Python and
Kotlin share the formulas and are pinned to each other by
`ml/fixtures/parity_sample.json` / `EdgeModelTest`.

## Honest numbers

- Days split in time order: first ~2/3 fit, last ~1/3 scored.
- `beats_market` requires, on the held-out days: lower Brier **and** log-loss
  than the mid, ≥ 300 markets, and a market-clustered bootstrap 95% CI of the
  log-loss gain **above zero**. The app re-checks all of this before activating.
- The sim bets the first minute per market whose net EV at the **close ask**
  (after Kalshi fees, $5 ticket) clears `ev_margin`. A higher hit rate alone
  never counts.
