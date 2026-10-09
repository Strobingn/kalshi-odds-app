# Scalping study tools (docs/scalping-2026-10-09.md)

Public data only, no API key. Files are written to the current folder, so run
from an empty working folder with this folder on `PYTHONPATH`:

```
export PYTHONPATH=<repo>/tools/research/scalp
pip install numpy pandas lightgbm

# A. minute-level scalps (45 days of 1-minute quotes + Coinbase closes)
python3 -m pull_candles KXBTC15M 45        # mk_KXBTC15M.json, cd_KXBTC15M.jsonl
python3 -m pull_cb 47                      # cb_btc.json
python3 -m scalp_a
python3 -m verify_a                        # second code path for two rows

# B. second-level scalps (21 days of every public trade, ~70,000 requests)
mkdir -p tape/raw && python3 -m pull_tape tape 21 8
python3 -m test_scalp_b                    # hand-built simulator checks
python3 -m scalp_b tape scalp_b_out.json 2 && python3 -m report_b scalp_b_out.json
python3 -m verify_b tape 40                # loop-based second implementation

# C/D. ML and the queue table
python3 -m build_ml tape ml_data.npz 2
python3 -m train_ml
python3 -m queue_ml tape

# E. the model the app ships, its queue penalty and the parity fixture; the fast strategies
python3 -m train_compact                   # scalper_model.json
python3 -m export_app_model tape           # adds the queue penalty; writes scalper_parity.json
#   -> app/src/main/assets/scalper_model.json, app/src/test/resources/scalper_parity.json
python3 -m strat_bt tape
```

`DESIGN.md` holds both designs as they were fixed before scoring.
