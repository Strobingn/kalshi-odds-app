# DipHunter offline edge trainer

Trains a compact logistic model on settled Kalshi **BTC / ETH / SOL 15-minute**
markets plus Coinbase public spot candles. Walk-forward (time-ordered)
validation, Platt calibration, Brier / log-loss versus the Kalshi market
price, and a simulated net P&L after the documented Kalshi-style
`feeRate × P × (1−P)` fee (default 7%) plus a 3¢ confidence margin.

The export is a few dozen floats. The phone only **infers** it — it does not
train the heavy 0.3.0 stack.

## One command

```bash
python3 ml/train_edge.py
```

Writes `ml/edge_model.json`. Import that file in the app: **Data → Import model**.

Options:

```bash
python3 ml/train_edge.py --days 30 --out ml/edge_model.json
python3 ml/train_edge.py --fixture   # no network; synthetic walk-forward
```

Python 3.10+ standard library only (no pip packages).

GitHub Actions: **Actions → Train edge model → Run workflow**, or the weekly
Monday cron. The JSON + `edge_model_manifest.json` are uploaded as an artifact
and published on the rolling `edge-model-latest` release. In the app:
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
| 7 | cross_asset | reserved (0 if missing) |
| 8 | time_of_day | minutes since midnight / 1440 |
| 9 | digital_fair | vol digital P(S_T > K) |

## Honest numbers

The script prints hold-out `model_brier` vs `market_brier`. If the model does
not beat the market after fees, do not trade the edge. The app also hides
“edge” flags until |model − market| > fee + margin.
