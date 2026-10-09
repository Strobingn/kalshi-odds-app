# Bigger AI model for DipHunter GTP

`ml/train_big_edge.py` trains a **128-tree, depth-3 gradient boosted classifier** on the same ten input features used by the Android `EdgeFeatures` path. It exports bounded flat trees that the app evaluates locally, without running Python on the phone. It is a research model: more capacity does not imply better trading performance.

## Train it

The first push of this trainer on `chat-GTP` starts its research workflow. For later runs, choose **Actions → Train big research model → Run workflow**, leaving `days=30` and `max_per_day=16` initially. The action collects settled BTC, ETH and SOL 15-minute markets, trains the model, and uploads `big_edge_model.json` and `big_edge_manifest.json` as an artifact. Collection uses public Kalshi candlesticks and Coinbase minute candles, and can take a while or fail due to API limits. It will refuse a dataset spanning fewer than 12 UTC days or 300 independent markets. It will never use a synthetic fallback.

Or train from an authentic CSV on a desktop with Python 3.12, NumPy and scikit-learn:

```bash
python3 ml/train_big_edge.py --csv /path/to/history.csv
```

Required columns: `market_ticker,decision_ts,y_yes,yes_ask,no_ask` and the ten exact names in `ml/train_edge.py`'s `FEATURE_NAMES`. `decision_ts` is UTC epoch seconds; each market may have several decision rows, with the same final `y_yes`. The app accepts the resulting `ml/big_edge_model.json` through **Data → Import model**. The branch's APK includes its tree interpreter.

## How it is checked

- Whole markets are assigned to ordered training (60% of days), probability-calibration (20%), and final test (20%) blocks. The training fit and calibration never see test outcomes.
- Before saving, the exported tree predictions are checked against scikit-learn on all test rows. Metrics compare Brier and log-loss against the Kalshi midpoint.
- A paper replay chooses at most one ticket per market and subtracts the YES or NO minute-close ask and an order-level $5-clip taker fee; it reports a market-bootstrap uncertainty interval.
- The research manifest has `data_source=offline_gbdt_research`, so the app cannot **automatically** activate this model from its rolling model release. Manual import is a user choice, not a profitability certification.

## What this cannot establish yet

Minute-close candlestick asks are not verified simultaneous executable fills. Some live feature calculations (especially momentum and volatility) differ from their historical candle approximations. The training rows use Coinbase spot as a proxy, while the contract settles on a CF Benchmarks final-minute average. A proper 1 Hz benchmark and order-book recorder, timestamped replay, and forward paper fills are needed before treating its picks as an edge. If the holdout loses after costs, keep it in research mode regardless of accuracy.

The choice of gradient boosted trees fits this structured feature set and supports compact on-device inference. scikit-learn documents [gradient boosting classification](https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.GradientBoostingClassifier.html) and [held-out probability calibration](https://scikit-learn.org/stable/modules/calibration.html). A pretrained language model has no special knowledge of future settlement ticks or available order-book fills.
