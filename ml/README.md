# DipHunter offline edge trainer

Trains a compact logistic model on settled Kalshi **BTC / ETH / SOL 15-minute**
markets plus Coinbase public spot candles. Walk-forward (time-ordered)
validation, Platt calibration, Brier / log-loss versus the Kalshi market
price, and a diagnostic midpoint-fill P&L after a simplified
`feeRate × P × (1−P)` fee (default 7%) plus a 3¢ confidence margin.
The diagnostic uses the correct side midpoint (`YES=mid`, `NO=1−mid`),
but actual taker fills cost the ask and order-level fees are rounded.
Use the separate ask-fill backtest and live paper fills to evaluate trades.

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

GitHub Actions: **Actions → Train edge model → Run workflow → chat-GTP**.
The JSON + `edge_model_manifest.json` are uploaded as an artifact and published
to the shared `edge-model-latest` release. Every app branch can download that
same experimental candidate through **Data → Get latest model**. The workflow
also refreshes the legacy `edge-model-chat-GTP` alias for already-installed
builds.

After the baseline trainer passes its no-look-ahead checks, the same Actions
run queues an independent GBDT research challenger. It is archived as an
artifact only—it never auto-replaces the phone model.

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

The script prints hold-out `model_brier` vs `market_brier`. Better Brier and
log-loss are necessary checks for activation, but do not establish a trading
edge after spread, ask fills, and fees. The app hides “edge” flags until
|model − market| > fee + margin.

The live trainer now exits with an error if Kalshi/Coinbase data collection
fails or fewer than 30 rows are available. `--fixture` is explicitly synthetic;
its manifest cannot activate automatically in the Android app. Previously
published manifests without a verified data source are also rejected.
