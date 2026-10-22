#!/usr/bin/env python3
"""Export actual on-device model examples from a paper replay ZIP for offline work.
No synthetic labels, inferred fills, balancing or train/test shuffling.
"""
import argparse
import csv
import io
import json
import zipfile
from pathlib import Path


def extract(path):
    with zipfile.ZipFile(path) as archive:
        state = json.loads(archive.read("paper-state.json"))
    rows = state.get("quant", {}).get("observations", [])
    features = ["time_left_900s", "ask", "bid_move_10s_x10", "bid_move_30s_x10",
                "bid_step_rms_30s_x10", "spread_x100", "top3_depth_imbalance", "recent_recovery_x10",
                "range", "sweep", "trend", "forecast", "spot_distance_x1000", "spot_distance_present",
                "spot_return_1m_x1000", "spot_return_1m_present", "spot_return_5m_x1000", "spot_return_5m_present",
                "spot_vol_15m_x1000", "spot_vol_15m_present"]
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["source", "ticker", "setup", "side", "opened_ms", "close_ms", "outcome_ms", "net_per_contract"] + features)
    for row in rows:
        if len(row["x"]) != len(features):
            raise ValueError("Unknown feature schema; refusing to mislabel columns")
        writer.writerow([state["source"], row["ticker"], row["setup"], row["side"], row["openedMs"], row["closeMs"], row["outcomeMs"], row["netPerContract"]] + row["x"])
    return output.getvalue(), len(rows)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("replay_zip", type=Path)
    parser.add_argument("output_csv", type=Path)
    args = parser.parse_args()
    content, count = extract(args.replay_zip)
    args.output_csv.write_text(content)
    print(f"Exported {count} observed control examples. Keep each ticker together and embargo both close/outcome timestamps by 90s.")
