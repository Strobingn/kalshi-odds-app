# DipHunter offline edge trainer (KIMI-Bitcoin)

Trains a compact logistic model on settled Kalshi **BTC 15-minute** markets
plus Coinbase public spot candles. Walk-forward (time-ordered) validation,
Platt calibration, Brier / log-loss versus the Kalshi market price, and a
diagnostic net P&L after a simplified `feeRate × P × (1−P)` fee (default 7%)
plus a 3¢ confidence margin — **filled at the ask, never the midpoint**.
The 2026-09-25 backtest showed midpoint fills flatter every strategy, so a
fixed 0.5¢ half-spread is charged when an ask print is missing. Use the
separate candle backtest and live paper fills for real evaluation.

BTC-only on this branch: the app trades `KXBTC15M` exclusively
(`CryptoMarkets.DEFAULT_SERIES`), so ETH/SOL rows only diluted training.

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

GitHub Actions: **Actions → Train edge model → Run workflow → KIMI-Bitcoin**.
The JSON + `edge_model_manifest.json` are uploaded as an artifact and
published on the branch-only `edge-model-KIMI-Bitcoin` release. In this app:
**Data → Get latest model**. (The workflow file itself must be retargeted
from `edge-model-chat-GTP` — see KIMI-BITCOIN.md; workflow edits need the
`workflow` token scope.)

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
|model − market| > fee + margin, and `ModelActivation` refuses to activate
any model whose holdout does not beat the market mid with verified
real-data provenance. That gate is intentional — do not weaken it.

The live trainer exits with an error if Kalshi/Coinbase data collection
fails or fewer than 30 rows are available. `--fixture` is explicitly
synthetic; its manifest cannot activate automatically in the Android app.
Previously published manifests without a verified data source are also
rejected.
