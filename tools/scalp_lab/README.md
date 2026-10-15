# Read the phone's paper-scalper ledger

Use a consistent SQLite backup containing `scalp_trades`, or a JSON/JSONL/CSV export of those rows. The tool reads locally and never connects to Kalshi. Copy SQLite through a database backup/export operation; copying a live database without its WAL can omit recent trades. A summary screenshot or `scalp_stats` alone cannot support the chronological analysis.

```sh
python3 tools/scalp_lab/analyze.py phone-backup.sqlite --out /tmp/scalp-readout
python3 tools/scalp_lab/analyze.py scalp-trades.jsonl --out /tmp/scalp-readout --cluster-day
python3 -m unittest discover -s tools/scalp_lab -v
```

`readout.md` is the human-readable result; `readout.json` contains cash flows, fees, ROI, realized drawdown, counts, exit-reason counts and clustered bootstrap intervals. No dependencies beyond Python 3.10+.

Required closed-row fields (snake_case or the app's camelCase): `id`, `ticker`, `state`, `contracts`, `entry_price`, `entry_fee_usd`, `entry_at_ms`, `sold_contracts`, `proceeds_usd`, `exit_fee_usd`, `closed_at_ms`, `net_usd`, `rule_version`. Optional `note` separates `v2ai=keep` and `v2ai=trial`; `exit_reason` explains exits. JSON accepts a row array or an object with `scalp_trades` or `scalps`. IDs deduplicate identical rows; conflicting snapshots, missing fees, nonfinite numbers and mismatched cash flows fail explicitly.

The split needs at least three UTC entry days. Earlier two-thirds of distinct days fit the rule; later days validate it. Trades whose whole market window crosses the split are excluded from both sides. Primary/shadow rows, no-fills and open positions are counted separately. Variants keep their exact rule IDs, including V2 and tweak/size siblings. Trade count and dollar profit must be interpreted alongside dollars per contract because clip sizes differ.

For each variant, compare four predeclared entry caps (no cap, 30c, 40c, 50c), with at least 30 fitting trades across five market windows. Pick the cap with the highest fitting net cents per contract, breaking ties toward the less restrictive cap. Freeze that choice and its fitting rank before reading validation. The output includes losing choices; a selected cap is not an instruction to trade it. Fewer observations yield no fitted cap. Cluster intervals by the shared 15-minute window, or use `--cluster-day` for a broader dependence check. One cluster has no estimated interval.

This is a filter of recorded fills. It cannot estimate how skipped trades free bankroll, enable later re-entries, change queue position or change exits. Multiple models often consume the same paper liquidity; summing their results is not a depth-feasible portfolio. Run each frozen promising rule on a new forward period and depth-replay proposed size increases. Existing test/validation results used to choose a model become development data for that choice; they are no longer its final holdout.
