# Dip Hunter TFLite Features

Fixed float32 input order (length 8). Android and training must match.

| Idx | Name | Definition |
|-----|------|------------|
| 0 | mid_price | YES mid in [0,1] from candle bid/ask mid or close |
| 1 | volume_norm | log1p(volume) / log1p(1e6) over recent candle window |
| 2 | tte_frac | seconds_to_expiry / 900, clipped to [0,1] |
| 3 | volatility | candle (high-low) or std of recent mids |
| 4 | momentum | mid_now − mid_at_window_start |
| 5 | mean_reversion | 0.5 − mid_price |
| 6 | series_id | 0 = KXBTC15M, 1 = KXWTI15M |
| 7 | oi_norm | log1p(open_interest) / log1p(1e6) |

Standardization: `(x − mean) / std` using `feature_scaler.json`.

## Model

- Framework: TensorFlow Lite
- Architecture: `Input(8) → Dense(32, ReLU) → Dense(16, ReLU) → Dense(2, softmax)`
- Output softmax order: **[P(NO), P(YES)]** (index 0 = NO, index 1 = YES)
- Trained on Kalshi settled markets + 1-minute candlesticks (live + historical)

## Labels

- `1` if market `result == yes`, else `0`

## 0.3.0 sequence + GBM (heavy ML)

Kept: the 8-feature MLP above. Added on-device (Kotlin, optional student JSON):

**Sequence window** — last 5 minutes, 30 × 10s bins, channels:

| Idx | Name | Definition |
|-----|------|------------|
| 0 | mid | YES mid in [0,1] |
| 1 | size | log1p(trade size) / 8 |
| 2 | imbalance | near-mid bid vs ask, [-1,1] |
| 3 | aggressor | signed taker flow, [-1,1] |
| 4 | spot | BTC/ETH/SOL 1m/5m return |

**Shared backbone** — Temporal CNN (5→12, k=3 ×2 + GAP) and TinyLSTM (h=8) + series embedding (BTC/ETH/SOL). Multi-task heads: P(YES), time-to-move, mid vol, P(fill).

**GBM** — 16 tabular features (`GbmBooster.FEATURE_NAMES`), stacked with MLP/CNN/LSTM. Ensemble variance / MC-dropout gates alerts and tickets.

**Refresh** — `python3 ml/train_heavy.py` → `app/src/main/assets/heavy_ml_student.json` (see `ml/DISTILL.md`). Cold start uses compiled priors and the 0.2.x blend until sequence/stack is ready.

## 0.3.0 extended AI (10–19)

All on-device, Settings-gated, advisory / skip-filter only. **No unsupervised orders.**

| # | Module | What it does |
|---|--------|----------------|
| 10 | `RegimeClassifier` | Softmax quiet/trend/vol/chop/news + session/weekend; `scaleStack` |
| 11 | `AnomalyDetector` | Cancel-storm, quote-stuffing, fake-depth; block ≥ 0.72 |
| 12 | `SurvivalModel` | Logit(mid) + path tilt + late gravity → P(YES) |
| 13 | `RlSizer` | Softmax {0.25,0.5,1.0,1.25}×configured stake; REINFORCE; $1–$25 clip |
| 14 | `NewsPulse` | 16-d hash embed + lexicon; CoinDesk RSS fail-soft cache |
| 15 | `RivalFlowCluster` | Online MIXED/CHASE/SMART centroids |
| 16 | `BayesianMmShadow` | EMA fair / inventory / shaded quote |
| 17 | `ConformalSets` | s=\|p−y\|, q=⌈(1−α)(n+1)⌉; skip if interval contains 0.5 |
| 18 | `MetaLabeler` | Logistic take/skip; cold always take (min 10) |
| 19 | `PathSimulator` | 48 paths × 10 steps, Box–Muller; P(side wins at expiry) |

## Live edge / UI features (not all inside the net)

Computed on-device for trading decisions (display only — no orders):

- **edge_pp** = AI YES% − Market YES%
- **spread** = yes_ask − yes_bid
- **volume / volume_24h / open_interest / liquidity_dollars** from market DTO
- **stance**: Lean YES / Lean NO / No edge (threshold `EDGE_ALERT_THRESHOLD_PP` = 5pp)
- Feedback: prediction log → settlement `result` → accuracy + Brier
