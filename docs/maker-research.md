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

## Pair maker (two-sided resting bids) — `tools/research/pair_maker.py`

YES and NO on one market pay exactly $1 between them, and KXBTC15M has no
maker fee. A YES bid at Py plus a NO bid at Pn with Py + Pn < $1 therefore
locks in 1 − Py − Pn per pair when both legs fill, with no forecast. The
risk is a one-legged fill right before the price moves against it.

Pre-registered grid: `join` / `improve` (1¢ above the bid, queue 0) ×
lock ≥ 1 / 2 / 3¢ × cancel 30 / 60 / 120 s × unwind `hold` (keep the lone
leg to settlement) or `complete` (buy the missing leg at the ask with the
0.07 taker fee). 10 contracts per leg, decisions every 30 s from 1:00 to
13:00, one pair of bids at a time per market, conservative fills only.
Maker fee 0 (verified) with 0.0175 as a stress line.

The report splits P&L into *locked $* (the riskless pairs) and *other $*
(everything a one-legged fill cost or made), and shows the filled leg's
mid move for one-legged fills (negative = adverse selection). Same IS/OOS
protocol and decision rule as `maker_sim.py`: paper trade only if the OOS
99% day-block CI of $ per posted episode excludes 0 with ≥ 5 OOS days.

```
python3 tools/research/pair_maker.py --dir <recordings dir> --out pair.md
python3 tools/research/test_pair_maker.py
```

Workflow "Pair maker (Claude)" runs it on the cloud recorder's cache.

### Result, 2026-10-08 (cloud recordings 2026-10-03 → 10-08, 440 markets)

**It loses.** Every config with a real sample lost money; the locked pairs
are swamped by one-legged fills.

| | IS (4 days) | OOS (2 days) |
|---|---|---|
| picked config | `join/L0.01/T30/hold` | same |
| episodes | 6,450 | 1,781 |
| both legs filled | 48% | 51% |
| $ per episode (10 ct/leg) | −0.283, 99% CI [−0.381, −0.205] | −0.199, 99% CI [−0.312, −0.198] |
| locked $ / other $ | +310 / −2,134 | +92 / −446 |
| one-leg Δmid | −6.2¢ | −6.8¢ |

- The book is almost always 1¢ wide, so the most a pair can lock is 1¢;
  a lock of ≥ 2¢ happened 50 times in 4 days, and `improve` (1¢ inside on
  both sides) almost never fits.
- A lone leg is adversely selected by 6–15¢ (worse the longer the bid
  rests), roughly ten times the 1¢ a completed pair earns.
- Completing the lone leg at the ask does not help: it pays the 7% fee on
  a price that already moved.
- The decision rule says NOT EVALUABLE (2 OOS days < 5), but the sign is
  not in doubt: retire the idea unless a fill-toxicity model (cancel the
  bid before it is picked off) can remove most of the 6¢ markout.

### Spot guard result, 2026-10-08 (same 6 days)

Cancelling a leg once Coinbase moves 2 / 5 / 10 bps against it (1 s cancel
latency) barely helps. Best guard was 2 bps:

| | no guard | G2 |
|---|---|---|
| IS $/episode (`join/L0.01/T30/hold`) | −0.283 | −0.263 |
| OOS $/episode | −0.199, 99% CI [−0.312, −0.198] | −0.160, 99% CI [−0.312, −0.159] |
| one-leg Δmid (IS) | −6.2¢ | −5.6¢ |

5 and 10 bps almost never fire before the fill. By the time Coinbase has
moved enough to say "this bid is about to be picked off", the taker has
already hit it — Kalshi's book reacts to Coinbase within about a second
(`ws_lag.py`), so a guard that waits for Coinbase plus a 1 s cancel is too
slow. **The pair maker stays retired.** A fill-toxicity model would need a
signal that leads Kalshi's own takers, not Coinbase.
