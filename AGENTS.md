# Kalshi 15-Minute BTC App — Agent Map

Read this first. Load only the files you need for the current step.

## What this app is
KXBTC15M — Kalshi 15-minute Bitcoin up/down contracts.
Opens every :00, :15, :30, :45. Settlement is the 60-second average of CF Benchmarks BRTI over the final minute vs the open. Tie goes to YES.
Edge is timing + fees, not directional prediction. Across many windows the market is roughly fair.

## Trading rules (authoritative)
Load `docs/trading-rules.md` when touching entry, exit, sizing, or time-left logic.

Key points (do not contradict these):
- Buy the cheaper side under max ask when it is not still falling. No dip required.
- With more than range minutes left, also buy inside the range (default 30–75¢).
- Sell at take profit or stop. Never hold to settlement. Last 60s is a cut.
- Size = fraction of cash (default 25%). Bigger only when range is clear.
- Do not chase spikes. Sell into them if already in, otherwise wait.
- Settings live in the app and must be read from the store.

## Time-left regimes
| Time left   | Behavior                  | Action                                      |
|-------------|---------------------------|---------------------------------------------|
| 10+ min     | Price stays ~30–75¢       | Buy under 50¢ or in range when not falling |
| 5–10 min    | Tightest spreads          | Best regime, normal size                    |
| <5 min      | Rapid shift, depth thins  | Only if already in profit or clear          |
| Last 60s    | Settlement auction        | Cut. Do not hold.                           |

## Where things live
- Trading / strategy logic     → src/trading/ or equivalent strategy module
- Settings store               → settings / preferences (must be the source of truth)
- Market data / Kalshi feed    → data or kalshi client
- UI (odds, positions, controls) → components related to the 15m window
- Tests                        → tests/trading/ or co-located *.test.ts
- Docs / rules                 → docs/trading-rules.md

## Testing
- Unit tests for entry conditions, exit conditions, and time-left branches are required.
- Run the project’s test command (see package.json or README).
- Visual / manual check is secondary; automated tests are the primary evidence.

## What not to do
- Do not require a dip from a high. Under max ask is enough.
- Do not hold to settlement.
- Do not buy a falling price.
- Do not ignore time left.
- Do not trade off a raw Coinbase chart; settlement uses the BRTI average.
- Do not hard-code settings.

## Task-specific
For any change to entry/exit/sizing, also load the current settings defaults and the most recent failure notes if they exist.
