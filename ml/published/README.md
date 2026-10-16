# Published edge models

This directory is written by `ml/publish_model.py` **only** when the
fee-aware out-of-sample gate sets `beat_market` to true. A losing model
must not be copied here and must not become a GitHub release.

Nothing in this folder is a live model until that gate passes. The app
falls back to the bundled DipHunter TFLite model when `latest.json` is
absent or the download fails its checks.

## Tag

`model-YYYYMMDD` (UTC date of `trained_at`). Example: `model-20261005`.

## Files

```
ml/published/latest.json
ml/published/model-YYYYMMDD/edge_model.json
ml/published/model-YYYYMMDD/edge_model_manifest.json
```

`latest.json`:

```json
{
  "tag": "model-20261005",
  "package": "com.dirk.kalshiodds.kashi",
  "beat_market": true,
  "sha256": "<hex sha256 of the edge_model.json bytes>",
  "trained_at": "2026-10-05T08:17:00Z",
  "model_url": "https://github.com/Strobingn/kalshi-odds-app/releases/download/model-20261005/edge_model.json",
  "manifest_url": "https://github.com/Strobingn/kalshi-odds-app/releases/download/model-20261005/edge_model_manifest.json",
  "model_path": "ml/published/model-20261005/edge_model.json",
  "manifest_path": "ml/published/model-20261005/edge_model_manifest.json"
}
```

## URLs the app requests

1. `GET https://api.github.com/repos/Strobingn/kalshi-odds-app/releases?per_page=100`  
   Newest non-draft tag matching `model-YYYYMMDD`.
2. Release assets `edge_model.json` and `edge_model_manifest.json` on that tag:
   - `https://github.com/Strobingn/kalshi-odds-app/releases/download/model-YYYYMMDD/edge_model.json`
   - `https://github.com/Strobingn/kalshi-odds-app/releases/download/model-YYYYMMDD/edge_model_manifest.json`
3. If no dated release exists, the raw index on `kashi`:  
   `https://raw.githubusercontent.com/Strobingn/kalshi-odds-app/kashi/ml/published/latest.json`  
   Paths inside it resolve under  
   `https://raw.githubusercontent.com/Strobingn/kalshi-odds-app/kashi/`.

The manifest `package` must be `com.dirk.kalshiodds.kashi`. The manifest
`sha256` must match the model bytes. `beat_market` must be true and the
holdout numbers must still clear the same gate. Otherwise the phone keeps
the bundled model.
