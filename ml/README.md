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
and published on the rolling `edge-model-claude` release (this branch's app
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
