# Dip Hunter 0.3.0 — teacher → student weights

The phone runs a **student**: Temporal CNN + TinyLSTM + 16-d shared backbone +
GBM forest + last-layer heads. Kotlin defaults ship in the APK so a cold
install works with no extra files. When you have richer history, train a
teacher offline and **export a student snapshot** into app assets.

Nothing here places trades. Refreshing weights only changes on-device
analysis. Tickets still need an in-app **Approve**.

## What lives where

| Artifact | Role |
|----------|------|
| Kotlin defaults (`TemporalCnn.defaults`, `GbmBooster.defaultModel`, …) | Cold-start student. Always compiled in. |
| `app/src/main/assets/diphunter.tflite` | Existing 8-feature MLP (0.1.7+). Kept. |
| `app/src/main/assets/heavy_ml_student.json` | Optional student export (CNN / GBM / heads). Ignored if missing. |
| `app/src/main/assets/diphunter_seq.tflite` | Optional TFLite sequence student (same 30×5 input). Loaded if present. |
| On-device DataStore (`diphunter_heavy_ml`) | Continual last-layer + stack + per-series Platt/isotonic. Not an APK asset. |

Disable the whole stack in **Settings → Heavy ML** to force the 0.2.x MLP blend.

## Refresh procedure

1. Collect settled crypto 15m rows (BTC / ETH / SOL only). CSV columns
   documented in `ml/train_heavy.py` (`mid,size,imb,agg,spot` sequences +
   tabular + `y_yes`).
2. From the repo root:

   ```bash
   python3 ml/train_heavy.py --csv path/to/history.csv
   ```

   If TensorFlow is installed the script also writes `diphunter_seq.tflite`.
   JSON export always writes even without TF.

3. Copy outputs:

   ```
   ml/heavy_ml_student.json  →  app/src/main/assets/heavy_ml_student.json
   ml/diphunter_seq.tflite   →  app/src/main/assets/diphunter_seq.tflite   # optional
   ```

4. Bump `versionCode` if you ship a new APK so Play/sideload replaces assets.
5. Rebuild: `./gradlew :app:assembleDebug`.

On-device fine-tune does **not** require this pipeline. Settlements update
the YES head, ensemble stack weights, and per-(series, TTE) calibrators
locally. Asset refresh is for a better prior.

## Student contract

- Sequence input: `float32 [1, 30, 5]` = mid, size, imbalance, aggressor, spot
  over the last 5 minutes (10s bins). Left-padded.
- Series embedding index: 0=BTC, 1=ETH, 2=SOL, 3=other crypto.
- GBM features: see `GbmBooster.FEATURE_NAMES` (length 16).
- Softmax / sigmoid heads: **P(YES)** in `(0,1)`.
- Keep the student small. Phone scoring is on the tick path.

## Battery

Sequence + GBM + three MC-dropout CNN passes cost more CPU than the 8-feature
MLP. Settings can disable Heavy ML, the sequence tower, or GBM independently.
