# Math audit 1.0.24

Paper only. Live order placement is unchanged. Shared pure fee helpers (`KalshiFee`, `ScalpRule.orderFee`) are the exception: a float ceil and a per-piece ceil were wrong for both paper and the live sizer, so those helpers now match Kalshi's order-level round-up. Version name stays 1.0.23.

Official schedule checked against the July 7, 2026 fee PDF and `docs.kalshi.com/getting_started/fee_rounding`: taker `round_up(0.07 · C · P · (1−P))` so fee + premium lands on the next cent; maker coefficient `0.0175` only where the series is on the maker table (KXBTC15M is `quadratic`, maker $0). Sells pay the same formula.

## Fees

| Formula | Where | Verdict |
| --- | --- | --- |
| Taker fee, one order, BigDecimal, ceil to the cent | `KalshiFee.total` | OK after dust guard. A remainder under $0.000001 above an exact cent no longer becomes another cent. |
| `ScalpRule.orderFee` | `Scalp.kt` | BUG. Was `ceil(7·C·P·(1−P) − 1e−9)` in Double. Now calls `KalshiFee.total`. |
| Sweep / partial exit | `KalshiFee.sweepFee`, `ScalpFills.sell` | BUG. Each fill piece was ceiled on its own, so two 5-contract prints at the same price cost $0.01 more than one 10-contract order. The exit fee is now one ceil across the sale prices. |
| Level walk | `ScalpControls.walkBuy`, `ScalpBook.walkBuy` / `walkSell` | BUG. Same per-level ceil. The clip is one order. |
| Maker | `KalshiFee.makerFee` | OK for KXBTC15M ($0). Non-zero series use the same cent alignment as taker, coefficient 0.0175 × M. |
| `feeRate` on an explicit paper buy | `PaperBook.explicitFill` | BUG. Ignored the book rate and stored the premium without the fee, then settlement charged the fee again. Stake is now the all-in debit and the book's `feeRate` is used. |
| Paper sell | `PaperBook.sell` | BUG. Exit fee was $0, so a round trip kept the spread and dropped the sell fee. Proceeds are now bid × contracts minus the taker fee. FIFO cost is a share of the all-in stake. |
| Settlement | `PaperBook.settle`, `ScalpBook.settle` | OK. Winner pays $1 per contract, loser $0. Fee is not taken again out of the $1. |

## Scalpers

Kept board: sf42, v2-g10-t12-s08-d15-w300-mk, g14-t12-s08-d15-w300, sf16f, sf15, sf17f, v2-dip-g05-t08-s06-d10-w180, sf07, sf04, sf08, fcfade-h600, sf41.

| Check | Verdict |
| --- | --- |
| Entry, target, stop, trail, max hold | OK. Stops and targets stay in dollars (8¢ is 0.08). |
| Fair-gap exit when the bid crosses fair | BUG label. The reason was "take-profit" even when fair had fallen and the sale was a loss (g14 bought 14¢, sold 9.6¢). It is now `fair-value exit`. Exit charts bucket that string as Fair, including the old wording. A price target is still Take profit. |
| Round-trip net | `proceeds − exit fee − (entry × contracts + entry fee)`. |
| sf04 / sf07 / sf08 | They often buy the same contract. Per-scalper stats stay. The all-scalpers total is labeled: those shared fills are counted once per scalper. `ScalpPortfolio.dedupedNet` is the one-count figure. |
| Win rate, average, drawdown, equity | Sums of stored `netUsd`. A win is `netUsd > 0`. Sharpe on the rich chart is per-trade mean / sample standard deviation (n−1), not annualized. |
| Auto-pause | BUG. `autoBench` defaulted on (30 trades, net < 0 benches) and the AI watcher wrote `aiBench`. Default is now off. One launch clears a saved auto-bench and `aiBench` (`autobench_cleared_1024`) and does not run again, so the settings toggle can be turned back on. The watcher still moves knobs inside the existing clamps. Force trade / Force bench are unchanged. A card says paused only for Force bench, the kill switch, a disabled strategy, or a user limit (daily loss, hourly, max open). Those limits still default to 0, which is off. |

## Kelly, probability, charts, sync

| Formula | Verdict |
| --- | --- |
| `f* = (p − cost) / (1 − cost)` with cost = one-contract all-in | OK. Half-Kelly is `× 0.5`. |
| Free bankroll | BUG for shadow. Each shadow clip was sized on the full bankroll, so one $10k book could show $23k–$35k of stakes in a single window. A new shadow clip is sized on remaining shadow cash, and a clip that does not fit is not booked. Paper free bankroll is `min(bankroll − open stake, cash)` and is never negative. Bet count is still unlimited. |
| GBM `P(UP) = Φ(ln(S/K) / (σ √τ))` with σ per √second | OK in `ScalpRule.fairYes` and `ShockFav.fairYes`. `τ` is seconds. The 60-second settlement average uses `√((τ−60)+20)` outside the last minute (`20 = 60/3`). |
| Digital fair on the detail chart | BUG. The white line was samples kept only in the open screen, and the spot behind `DirectionSanity` was the 25s Coinbase cache, so the line stepped while the bid jittered. Each bid print now stores `LiveFair` (same `Φ` as the digital override) from the newest spot, including a CF print, at that print's timestamp. Lag versus the market point is 0. Older prints are in `chart_ticks.fair_yes_pp`. Calibration math (Brier, isotonic) is unchanged. Sigma is still the minute-bar estimate. |
| Brier | Mean squared error of probability vs 0/1. |
| ET labels | `EtTime` is America/New_York, 12-hour, suffixed ET. The AI chart axis uses that. |
| Supabase `diphunter_sync` | Scalp payload still has variant, algo, netUsd, feesUsd, closedAtMs. Paper `pnlUsd` / prices go through a finite-number write so one NaN cannot abort the batch. If the paper or scalp batch fails, the sync watermark does not move, so those rows are retried. |

## Not changed

Home layout. Live order send path. App version. Removed scalpers stay removed.
