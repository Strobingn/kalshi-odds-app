# DipHunter daily edge model

Trains a compact logistic model on settled Kalshi **BTC / ETH / SOL 15-minute**
markets (`KXBTC15M`, `KXETH15M`, `KXSOL15M`) plus Coinbase public spot candles.
Walk-forward (time-ordered) validation, Platt calibration, Brier / log-loss
versus the Kalshi market price, and a simulated net P&L after the documented
Kalshi quadratic fee `0.07 × P × (1−P)` (YES pays the mid, NO pays `1 − mid`)
plus a 3¢ confidence margin.

The phone only **infers** the exported JSON. It does not train on device.
Fetches are public market data. Do not put Dirk's Kalshi account key in this
path.

## Run it daily

```bash
python3 ml/publish_daily.py
python3 ml/publish_daily.py --github   # also create the GitHub release
```

`--github` needs the `gh` CLI already logged in (a GitHub token with Contents:
write). It is not the Kalshi key.

GitHub Actions: **Actions → Train edge model → Run workflow** on `kashi`.
The daily cron is in `.github/workflows/train-edge-model.yml`, but Actions
schedules fire only on the repository default branch. While that branch is
`main`, the cron does not run. `workflow_dispatch` on `kashi` does.

The trainer exits non-zero when the gate fails, and `ml/publish_model.py`
refuses again before it copies files or calls `gh`. A losing model is not
written to `ml/published/` and no `model-YYYYMMDD` release is created.

Options:

```bash
python3 ml/train_edge.py --days 30
python3 ml/train_edge.py --fixture   # no network; synthetic; never publishable
python3 ml/test_train_edge.py && python3 ml/test_publish_model.py
```

Python 3.10+ standard library only (no pip packages).

## What gets published

Only when `beat_market` is true:

- GitHub release tag `model-YYYYMMDD`
  - `https://github.com/Strobingn/kalshi-odds-app/releases/download/model-YYYYMMDD/edge_model.json`
  - `https://github.com/Strobingn/kalshi-odds-app/releases/download/model-YYYYMMDD/edge_model_manifest.json`
- `ml/published/model-YYYYMMDD/` and `ml/published/latest.json` on `kashi`

The manifest carries `version`, `trained_at`, `package`
(`com.dirk.kalshiodds.kashi`), `sha256` of the model bytes, holdout Brier /
log-loss, fee-aware `sim_pnl` versus `market_pnl`, and `beat_market`.

See `ml/published/README.md` for the index JSON the app reads.

In the app: **Data → Get latest model**. The phone also checks on launch.
It activates a download only when the package matches, the sha256 matches,
and the holdout still beats the market after fees. Otherwise the bundled
DipHunter TFLite model stays in use.

## Gate (do not publish when this fails)

- Not synthetic
- ≥ 2000 settled markets, ≥ 2000 rows, ≥ 400 holdout rows
- Holdout Brier and log-loss each beat the market by ≥ 0.010
- Fee-aware selective P&L > 0, at least 30 trades, and that P&L beats
  buying the market favourite on the same holdout

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
| 7 | cross_asset | 5-minute spot return |
| 8 | time_of_day | UTC hour / 24 |
| 9 | digital_fair | vol digital P(S_T > K) |
