# $1,000 BTC paper experiment

Local prototype based on review of `Strobingn/kalshi-odds-app` branch `chat-GTP`,
commit `d34495bfb01560c3ad3660962179a6931f6d8cf6`. This runner is separate from
the Android APK. No API keys, order submission, or real-money
paths exist in this runner. It uses public GET requests only.

## Run

Python 3.11+; no third-party packages:

```sh
cd tools/paperlab
python3 -m unittest -v
python3 paperlab.py init --db paperlab.sqlite
python3 paperlab.py once --db paperlab.sqlite
python3 paperlab.py run --db paperlab.sqlite --minutes 60
python3 paperlab.py status --db paperlab.sqlite
python3 paperlab.py export --db paperlab.sqlite > results.json
```

The database persists simulated cash, trades, raw feed responses, errors,
decisions and UTC receipt times across restarts. Never mix test fixtures with the
real experiment database. Changing rules requires a new experiment database.
`run` runs only while this process/environment is alive; no background hosting
or future schedule is configured locally. The separate GitHub workflow runs an
initial 15-minute collection on pushes to `codex/paper-lab-1000`. After merge,
Actions → BTC paper experiment can run a 1–60 minute collection manually.
Each hosted run starts a fresh $1,000 experiment; download its ledger artifact
to continue that exact account locally. Do not add fresh balances across runs
or count a green workflow as a profitable strategy. Feed errors remain logged.
Positions still pending when a hosted run stops must be settled by continuing
the downloaded ledger later; they must not be reported as zero-profit trades.

## Frozen candidate: btc-market-anchor-v1

Start at $1,000 simulated cash. Trade BTC only, at most once per market. Observe
minutes 3–13 of each 15-minute window. Estimate a spot/strike digital probability
using 60 returns from contiguous, already-closed Coinbase minute bars. Shrink its
deviation from the Kalshi midpoint by 75%:

`p_yes = midpoint + 0.25 × (digital_probability − midpoint)`.

Evaluate both buy sides against their own asks and rounded taker fees. Require
more than 5 percentage points of modeled return per contract. This fixed margin
and shrinkage are research choices, not fitted or proven profitable parameters.
Select highest modeled return; no minimum jackpot/profit-if-win filter. A $10
profit-if-win requirement biases entries toward longshots without proving value.

Cap each position at $5 or 0.5% of available cash, whichever is smaller, and size
to visible depth. Re-fetch the book after a 1-second delay; reject moved-away
quotes, expired signals, crossed/missing books, slow responses and stale spot.
Partial simulated fills use only depth still present at the touch. Fees use the
0.07 non-direct-member formula rounded up to a cent; actual fees may differ.
No martingale. Limit open cost to $25, daily realized loss plus possible pending
losses to $20, and drawdown plus pending costs to $100. Only Kalshi's finalized
YES/NO result releases cash or books a win; missing settlement stays pending.

## What the results mean

Even a quote surviving a second snapshot is **not proof of an exchange fill**.
This polling experiment misses intervening cancellations, queueing, market
impact and partial-fill fee differences. Equity is cash plus unsettled cost,
not marked-to-market liquidation value. Coinbase is a proxy for the contract's
CF Benchmarks final-minute average; this candidate deliberately avoids the
last two minutes and does not claim to model that averaging process correctly.
Public book access must work on the actual account/network; unavailable access
causes abstention, never synthetic data or fictitious trades.

Evaluate all frozen trades, including losses and unfilled candidates. Keep a
complete final chronological holdout independent of any tuning. Evaluate the
BTC-only net P&L, return on deployed capital, maximum drawdown and uncertainty
clustered by UTC day; hundreds of correlated ticks are not independent bets.
No fixed sample count alone proves profitability. Require positive net results
whose uncertainty survives fees and additional slippage, then repeat forward
on fresh days. Do not promote this candidate to live orders based on model
accuracy, a winning streak, or the old report's exploratory 32–50c slice.

## Next strategy with a stronger information source

Record the authenticated 1Hz CF Benchmarks BRTI feed and sequence-checked Kalshi
book snapshots/deltas with source and receipt timestamps. Test whether changes
in the final-minute running benchmark average predict settlements before asks
reprice. Apply the actual contract rounding/equality rules, realistic reaction
and submission delays, exact fees and disappearing depth. This is an unproven
latency hypothesis requiring feed access, not a promised arbitrage. Keep it in
a separate experiment, including a fresh untouched holdout.

## Why this work is needed

The older repository replay reports a 65.4% win rate at a 65.8c average ask and
−3.3% ROI. `EdgeModelManifest` still activates on Brier/log-loss comparisons,
without an executable-profit requirement. `EdgeAutoTuner` optimizes thresholds
using midpoint prices on the same settled samples. `TicketBuilder` now compares
asks/fees and visible depth, but trusts raw imported/fallback model probabilities.
The existing forward rows freeze visible quotes; they do not confirm fills.
These are concrete research gaps, not evidence that the newest branch loses the
same amount as the old replay.
