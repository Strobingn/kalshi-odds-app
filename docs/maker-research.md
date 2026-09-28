# Maker (resting limit order) research — Kalshi 15m BTC

Status: **tooling only, no results yet.** Nothing here says a maker strategy
works. The simulator exists so that, once enough 1-second recordings exist,
we can answer the question with a pre-registered test.

## Idea

Instead of taking the ask (paying the spread plus the taker fee), rest a buy
limit order on the bid and get filled when a taker sells into it. The main
risk is **adverse selection**: resting orders tend to fill exactly when the
price is about to move against them (someone with better information, such
as a faster spot feed, hits the bid right before the mid drops).

## Method (`tools/research/maker_sim.py`)

- **Decision times**: every 30 s from 2:00 to 12:00 elapsed in each market.
- **Fair value**: digital option Φ(d2) from Coinbase spot vs the strike, σ from
  the trailing 30 one-minute spot log returns (`pipeline.p_finish_above`).
- **Side**: whichever of YES/NO has the larger `fair − P − maker_fee_per_contract(P)`.
- **Pre-registered grid** (18 configs, fixed before any data was seen):
  - price rule: `join` (best bid), `improve` (best bid + 1¢, only if still
    below the ask), `fair` (highest cent price ≤ min(bid + 1¢, ask − 1¢) that
    clears the margin);
  - cancel after T ∈ {15, 30, 60} s, or 60 s before close, whichever is first;
  - margin ∈ {0.02, 0.04}.
- **Order**: $5 all-in (`pipeline.size_all_in` at the maker rate), prices
  5–95¢, one resting order at a time per market, and the first fill ends the
  market (one position per market). Positions are held to settlement.
- **Fees**: `pipeline.kalshi_total_cost` (the app's `KalshiFee`), charged on
  the filled total at the maker rate.

### Fill models

| | Conservative (the one that counts) | Optimistic (contrast only) |
|---|---|---|
| Fills from | recorded trades on our side at or through our price: YES buy at P needs `taker_side = no` and `yes_price ≤ P`; NO buy at Q needs `taker_side = yes` and `1 − yes_price ≤ Q` | first book snapshot where the ask on our side is ≤ P, or any qualifying trade |
| Queue | displayed qty at our price when we posted (0 if we improved the bid) must trade first; we never move up from cancels ahead of us | ignored |
| Size | partial fills allowed | full fill |
| Window | trades strictly after the post ms, up to and including the cancel ms | same |

Other assumptions: trades with an empty `taker_side` never fill the
conservative model (the report shows how many there are). A `fair` post below
the best bid uses the best-bid qty as its queue, because top-of-book
recordings do not show depth at lower levels. Kalshi may round fees per fill,
while the simulator rounds once on the filled total.

### Metrics

- Fill rate (orders with any fill ÷ orders posted) and contract fill rate.
- P&L held to settlement, $ per filled order, and P&L per $ risked.
- Adverse selection: side mid 5/30/60 s after the first fill minus side mid
  at post time (negative means adverse), plus markout vs the fill price.
- Day-block bootstrap 95% and 99% CIs of $ per filled order.
- Fee sensitivity: every run also shows the taker rate 0.07 and maker rates
  0, 0.0175, 0.035.
- A taker reference (same fair and side logic, but taking the ask at 0.07,
  capped at the displayed ask qty).

### IS/OOS protocol

- Days are UTC days, with each market assigned to the day it opens.
- With **≥ 6 days**, the last third of days (at least 2) is OOS. For each fill
  model, the config is picked on IS P&L (≥ 20 IS fills) at `--maker-fee`, then
  evaluated on OOS at every fee rate.
- With **< 6 days** the report is labelled EXPLORATORY: the full grid is shown
  on all days, no config is picked, and no claims are made.

## Data needed

Use the app recorder's files (one per UTC day; the gzip may be multi-member,
and a truncated tail from a file still being written is tolerated):

```
spot_YYYY-MM-DD.csv.gz    ts_ms,product,price
book_YYYY-MM-DD.csv.gz    ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,yes_ask_qty,no_bid,no_bid_qty,no_ask,no_ask_qty
trades_YYYY-MM-DD.csv.gz  ts_ms,ticker,yes_price,count,taker_side
settle_YYYY-MM-DD.csv     ticker,close_ms,strike,result
```

Collect at least **2–4 weeks** of continuous recordings. With fewer than
about 15 days the OOS window is under 5 days, and a 99% day-block CI means
nothing (the report says NOT EVALUABLE). Gaps in `taker_side` or in book
coverage bias the conservative model towards no fills.

## How to run

```
python3 tools/research/maker_sim.py --dir <recordings dir> --maker-fee <rate> --out maker.md
python3 tools/research/test_maker_sim.py        # unit tests, stdlib only
```

`--maker-fee` has a default of **0.0**. That is a placeholder, not a fact:
set it from Kalshi's current fee schedule. When the flag is omitted, the report
labels the fee "DEFAULT placeholder" and the decision rule reads NOT
EVALUABLE. Other flags: `--series` (default `BTC`) and `--iters` (bootstrap
iterations, default 2000). A 7-day synthetic set runs in about 20 s.

## Decision rule

Consider live **paper** trading of a maker strategy only if **the
conservative model's OOS 99% day-block CI of $ per filled order excludes 0 at
the verified maker fee**, with at least 5 OOS days. The optimistic model, the
IS tables and the fee-sensitivity grid are context and never count. Even on a
pass, check the Δmid columns: a strongly negative Δmid means the edge depends
on the settlement fair being right, not on capturing spread.
