# Kalshi 15-Minute BTC Trading Rules (KXBTC15M)

## How the Market Works
- Series: `KXBTC15M`. Opens every quarter hour (:00, :15, :30, :45), 96 windows a day.
- Settlement: 60-second average of CF Benchmarks BRTI over the final minute vs the open. A last-second spike does not decide it alone.
- Tie goes to YES. The price opens near 50¢ because 15 minutes is too short for drift to beat noise.
- Fee: 0.07 × contracts × price × (1 − price). Peaks at 1.75¢ per contract at 50¢.
- Across 25k+ windows, up/down is ~50/50. The price is roughly fair. Edge is in timing and fees, not direction.

## The Window
| Time left   | Behavior                        | Action                                              |
|-------------|---------------------------------|-----------------------------------------------------|
| 10+ min     | Price stays 30–75¢. Book is wide. | Buy under 50¢ or in the range when not falling.   |
| 5–10 min    | Tightest spreads, deepest book. | Best regime. Normal size.                           |
| <5 min      | Rapid shift. Depth thins.       | Only if already in profit or clear direction.       |
| Last 60s    | Settlement auction. Price runs to 99¢ or 1¢. | Cut. Do not hold.                          |

## Spikes
- ~10% of windows see a side trade at 9¢ or lower and still win. Most of those lows happen with 3–8 minutes left, not in the final minute.
- Median time from that low back to 50¢ is ~37 seconds.
- Spikes come from BTC spot moving fast, the book repricing, or a large order.
- Do not chase. Buy before, or wait for the next one. Sell into the spike at the take profit.

## Rules for the App
1. Buy the cheaper side under the max ask when it is not still falling. No dip required.
2. With more than range minutes left, also buy in the range (default 30–75¢).
3. Sell at the take profit or the stop. Never hold to settlement.
4. Size is a fraction of cash. Bigger only when the range is clear.
5. Settings live in the app and stick. Change them there.

## Settings (defaults)
| Setting       | Default | Meaning                                      |
|---------------|---------|----------------------------------------------|
| Take profit   | 8¢      | Sell when bid is this far above entry        |
| Stop loss     | 5¢      | Sell when bid is this far below entry        |
| Max ask       | 50¢     | Buy under this                               |
| Size          | 25%     | Fraction of cash per entry                   |
| Range minutes | 10      | Above this, buy in the range                 |
| Shift minutes | 5       | Below this, the move is fast                 |
| Range low     | 30¢     | Bottom of the long-time range                |
| Range high    | 75¢     | Top of the long-time range                   |

## What Not To Do
- Do not require a dip from a high. Under 50¢ is enough.
- Do not hold to settlement. The last minute is a cut.
- Do not buy a falling price. Wait for it to stop.
- Do not ignore time left. The range and the shift are different markets.
- Do not trade off a Coinbase chart. Settlement is the BRTI average.
- Do not hard-code settings.
