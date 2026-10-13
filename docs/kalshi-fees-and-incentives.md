# Kalshi maker fees and liquidity incentives (KXBTC15M / KXETH15M / KXSOL15M / KXBTCD)

Researched 2026-10-05. Everything below was read on that date unless noted.

**Bottom line**

- **The maker fee on our series is not confirmed.** Kalshi sets it per series
  through the API fields `fee_type` and `fee_multiplier`. Those fields allow only
  two values that fit these series: **0** (`quadratic`) or **0.0175 × multiplier**
  (`quadratic_with_maker_fees`). Two unofficial websites say KXBTC15M has no maker
  fee. Nobody has read the series record from the API yet. That is what
  `tools/research/fee_probe.py` does (workflow "Fee probe (Claude)").
  **Simulation rate: unknown until the fee probe runs.** Until then, keep 0 as
  the main case and **0.0175** as the required stress line, which is what
  `maker_sim.py` and `FlowFade.kt` already do.
- **The Liquidity Incentive Program is real, retail can join, and it runs until
  2027-01-01.** At our size it almost certainly pays **$0**. A user's payout for
  each market and time period must reach **$1.00**, and a 10-contract order on
  books that are 2,000+ contracts deep earns only a fraction of that (worked
  numbers in §4). The probe also lists any liquidity-incentive programs live on
  our markets.
- **The Market Maker / Liquidity Provider programs are not open to us.** They
  require a signed Market Maker Agreement.

## 1. Sources and how each was read

The sandbox egress proxy blocks kalshi.com, help.kalshi.com, docs.kalshi.com,
cftc.gov and most third-party sites. It allows PyPI and Kalshi's public S3
document bucket. Each source below says how it was read.

| # | Source | Date of source | How read |
|---|---|---|---|
| S1 | Kalshi official Python SDK `kalshi_python_sync` 3.31.0, generated from Kalshi's OpenAPI spec ("Kalshi Trade API Manual Endpoints", version 3.31.0). <https://pypi.org/project/kalshi-python-sync/> | released 2026-09-29 | **Downloaded and read** (the `models/series.py`, `models/fee_type.py`, `models/series_fee_change.py`, `models/event_fee_change.py`, `models/incentive_program.py`, `models/market.py`, `models/order.py`, `api/exchange_api.py`, `api/events_api.py`, `api/incentive_programs_api.py` files) |
| S2 | CFTC filing "Liquidity Incentive Program – July 15, 2026 Update" (KalshiEX LLC, Rule 40.6(a)). <https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20-%20July%2015%2C%202026%20Update.pdf> | 2026-07-15, effective 2026-07-30 | **Downloaded, text extracted** |
| S3 | "Liquidity Incentive Program – July 30, 2026 Modification to July 15, 2026 Update" (tracked + clean terms). <https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20-%20July%2030%2C%202026%20Modification%20to%20July%2015%2C%202026%20Update.pdf> | 2026-07-30 | **Downloaded, text extracted** |
| S4 | "Liquidity Incentive Program – February 11, 2026 Update". <https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20-%20February%2011%2C%202026%20Update.pdf> | 2026-02-11, effective 2026-02-28 | **Downloaded** (cover letter read) |
| S5 | "Kalshi – Liquidity Provider Program – Cover Letter and Appx A (Modified, Clean)" + "June 18, 2026 Amendment to Liquidity Provider Program". <https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Kalshi%20-%20Liquidity%20Provider%20Program%20-%20Cover%20Letter%20and%20Appx%20A%20(Modified%2C%20Clean).pdf> | May / June 2026 | **Downloaded, text extracted** |
| S6 | "Fee Rebate Program Jan 2025". <https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Fee%20Rebate%20Program%20Jan%202025.pdf> | 2025-01, effective 2025-01-28 | **Downloaded, text extracted** |
| S7 | "Termination of Volume Incentive Program". <https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Termination%20of%20Volume%20Incentive%20Program.pdf> | 2026-09-28 | **Downloaded** (cover letter read) |
| S8 | Kalshi Fee Schedule PDF, <https://kalshi.com/docs/kalshi-fee-schedule.pdf>. The search engine lists its title as "Fee Schedule for July 2026 - 7.7.26 Update" | 2026-07-07 per the title | **Blocked.** Seen only through web-search summaries |
| S9 | Kalshi API docs, "Fee rounding", <https://docs.kalshi.com/getting_started/fee_rounding> | listed as updated 2026-03-03 | **Blocked.** Seen only through web-search summaries |
| S10 | Kalshi Help Center, "Liquidity Incentive Program", <https://help.kalshi.com/en/articles/13823851-liquidity-incentive-program> | n/a | **Blocked.** Seen only through web-search summaries |
| S11 | Allium data docs, "Kalshi series fee changes", <https://docs.allium.so/historical-data/predictions/kalshi/series-fee-changes> (third party) | n/a | **Blocked.** Seen only through web-search summaries |
| S12 | botforkalshi.com/blog/kalshi-fees-explained, predictionmarketspicks.com KXBTC15M article (third party) | 2026 | **Blocked.** Seen only through web-search summaries |

The current fee-schedule PDF (S8) is not in the public S3 bucket. That bucket
has many 2026 notices but only the 2022 fee-schedule filing and perpetual-futures
fee schedules. So the list of which series carry maker fees could not be read
from an official document here. Only the API can settle it.

## 2. Fee formulas

### Confirmed (official, S1: OpenAPI field description shipped in Kalshi's SDK)

Each `Series` has:

- `fee_type`: enum `quadratic` | `quadratic_with_maker_fees` |
  `quadratic_with_combo_maker_fees` | `flat`. Verbatim description:
  > "Fee structures can be found at https://kalshi.com/docs/kalshi-fee-schedule.pdf.
  > 'quadratic' is described by the General Trading Fees Table,
  > 'quadratic_with_maker_fees' is described by the General Trading Fees Table with
  > maker fees described in the Maker Fees section, 'quadratic_with_combo_maker_fees'
  > is the same maker-fee structure with a 0.5 maker multiplier instead of 0.25,
  > 'flat' is described by the Specific Trading Fees Table."
- `fee_multiplier`: "a floating point multiplier applied to the fee calculations."

Related official API surface (S1):

- `GET /series/fee_changes?series_ticker=&show_historical=` returns
  `series_fee_change_arr[]` with `{id, series_ticker, fee_type, fee_multiplier,
  scheduled_ts}`. This is how Kalshi publishes scheduled fee changes ahead of
  time. Public; no auth listed in the SDK.
- `GET /events/fee_changes` returns per-event overrides
  (`fee_type_override`, `fee_multiplier_override`; null means the event falls
  back to the series). Public.
- `Market.fee_waiver_expiration_time`: "Time when this market's fee waiver
  expires".
- `Order.taker_fees_dollars` / `Order.maker_fees_dollars`, `Fill.fee_cost`.
  These are the fees actually charged on our own orders, read through the
  authenticated API. They are the final check once live.

### Fee-schedule numbers (S8 via search summaries, plus S11; consistent across several summaries, not read first-hand)

- Taker: `fee = roundup(0.07 × C × P × (1 − P))`, scaled by the series
  multiplier. The maximum is 1.75¢ per contract at P = 0.50.
- Maker, on series that charge it: `fee = roundup(0.0175 × C × P × (1 − P))`.
  That is 25% of the taker coefficient, matching S1's "0.25". It is charged only
  when a resting order fills. Cancelling costs nothing.
- Allium (S11) puts it as `M_taker × 0.07 × C × P(1−P)` for takers and
  `M_maker × 0.0175 × C × P(1−P)` for makers. Before a series' first recorded
  fee change, it treats the series as quadratic with taker multiplier 1 and
  **maker multiplier 0**.
- S1 does not settle whether `fee_multiplier` also scales the maker side.
  `fee_probe.py` assumes it does (maker = 0.0175 × m) and prints the raw fields
  so this can be checked.
- No per-contract or per-order cap is mentioned in any source seen. The
  P(1−P) shape caps the fee at 1.75¢ (taker) and 0.4375¢ (maker) per contract.

### Rounding (S9 via search summaries; matches S1's 6-dp fee fields)

- The trade fee is rounded **up to $0.0001** on each fill.
- A separate "rounding fee" ($0.0000–$0.0099) brings the balance back to a
  whole cent.
- A per-order **fee accumulator** tracks the rounding overpaid across fills, for
  both maker and taker fills. Each time it passes $0.01, Kalshi refunds a whole
  cent. Over all the fills of one order, the total converges to what a single
  equivalent fill would cost.
- The older fee-schedule wording ("round up to the next cent") is therefore an
  upper bound per order. `tools/backtest/pipeline.kalshi_total_cost` rounds up
  to 6 dp and then rounds the total debit up to the cent. That is slightly
  conservative and fine.

### Which series carry maker fees

- **Official: unknown from here.** The answer is the series' `fee_type`, which
  `fee_probe.py` reads.
- **Unofficial:** botforkalshi / predictionmarketspicks (S12, via search
  summaries) say "Kalshi charges no maker fee on the KXBTC15M series". Our own
  `docs/flow-fade-2026-10-04.md` also says "No maker fee on this series" but
  gives no source.
- Some search summaries contradict each other. One says the maker fee is about
  1.75% "on every category". Another says "most markets 0% maker fee" plus
  special event fees. These are low-quality aggregator text and are not used.
- Nothing seen covers KXETH15M, KXSOL15M or KXBTCD specifically.

## 3. Programs that pay or refund

| Program | Who is eligible | What it pays | Relevant to us? |
|---|---|---|---|
| **Liquidity Incentive Program (LIP)** (S2–S4, S10) | "all Kalshi members, except … Introducing Brokers, Futures Commission Merchants, and customers thereof when transacting via the IB or FCM" (S3 clean terms, from 2026-07-30). The S2 version also excluded Kalshi affiliates and Market Maker Agreement members; S3 dropped those exclusions. A summary of S10 also says non-U.S. users are excluded (not verified). Nothing limits it to UI or API users, so retail API orders count. | A share of a reward pool for each market and each time period, scored from resting orders (formula below). Runs "until the earlier of January 1, 2027" or amendment/termination (S3). An earlier version ended 2026-09-01. | Only if our markets have a schedule. `/incentive_programs` lists them and the probe checks this. Even then, see §4. |
| Liquidity Provider Program (S5) | "Eligible Participants are all Kalshi members who have executed a Market Maker Agreement" | Up to $50,000/week per Incentivized Series, for a Designated Liquidity Provider who meets max-spread, min-size, uptime and coverage requirements. Categories include **Crypto**. Runs until 2027-12-01. | **No.** It needs a Market Maker Agreement. |
| Fee Rebate Program (S6) | All members except affiliates and Market Maker / FCM Agreement members; excludes Volume Incentive participants | Monthly rebate on exchange fees paid: 0% on the first $99.99, then 20/40/60/80% tiers above $100 / $250 / $750 / $2,000. Runs until 2027-01-27. | **No.** At $5 tickets we pay well under $100/month in fees, and a zero-fee maker pays nothing to rebate. |
| Volume Incentive Program (S7) | — | — | Being terminated, effective no earlier than **2026-10-13** (filing 2026-09-28). Terms not studied. |

### LIP mechanics (S2/S3, verbatim in substance)

- **Schedule.** Each eligible market page, and `GET /incentive_programs`, shows
  one or more Time Periods. Each has a Target Size, a Discount Factor (DF) and a
  Time Period Reward R. The limits are: period ≤ 31 days;
  100 < Target Size < 20,000 contracts; DF ≤ 1.00; and $1 ≤ R ≤ $1,000 per
  calendar day in the period, applied per market. In the API,
  `period_reward` is in **centi-cents**, `discount_factor_bps` is in basis
  points, and `target_size_fp` is in contracts.
- **Snapshots.** One snapshot per second, taken at a random moment within the
  second. A snapshot is excluded if the market is closed, or if either side
  lacks resting size ≥ Target Size. Yes asks count as No bids.
- **Reference price, per side.** Walk down from the best bid, adding all bids at
  each price level. The Reference Price is the first level where the cumulative
  size reaches Target Size / 5. Stop once the cumulative size reaches Target
  Size. All bids walked over are "qualifying"; bids deeper than that score
  nothing.
- **Score, per side.** For each qualifying bid,
  `score = DF^max(RefPrice − bidPrice in ticks, 0) × size`. Each side's scores
  are normalized to sum to 1. A user's snapshot score is the sum of their
  normalized scores on both sides (at most 2).
- **Payout.**
  `Payout = (Σ_snapshots user score / Σ_snapshots all users' scores) × R × (non-excluded snapshots / total snapshots)`.
  It is paid only if it is ≥ **$1.00**, rounded down to the cent. It is not paid
  in real time; final scoring happens after the period.

## 4. What a liquidity incentive would add to our maker results

From the formula above: on a non-excluded snapshot both sides have qualifying
bids, so all users' scores sum to 2. The payout then simplifies to

```
Payout ≈ R × (1 / (2·T)) × Σ_t [ your_share_YES(t) + your_share_NO(t) ]
your_share_side(t) = Σ_your bids DF^N·q / Σ_all qualifying bids DF^N·size
```

Here T is the number of seconds in the period. For one resting bid of q
contracts, at or above the reference price (N = 0, which covers both "join the
best bid" and "1¢ better than best"), against S contracts of qualifying size on
that side, resting a fraction f of the period:

```
Payout ≈ R × f × q / (2·S)        (paid only if ≥ $1.00 per market per period)
```

Rough numbers for a **$5 order**: q ≈ 10 contracts at 50¢. S is at least the
Target Size. Our recorded books have about 2,000–10,000 contracts queued at the
best bid (`docs/flow-fade-2026-10-04.md`), so take S = 2,000.

| Reward R per 15-min market | Rest the whole 15 min (f = 1) | Flow-fade (30 s per signal, f ≈ 0.033) |
|---:|---:|---:|
| $1 (the per-day minimum) | $0.0025 → **$0** (< $1) | $0.00008 → **$0** |
| $10 | $0.025 → **$0** | $0.0008 → **$0** |
| $100 | $0.25 → **$0** | $0.008 → **$0** |
| $400 | $1.00 → $1.00 | $0.03 → **$0** |

To clear the $1 floor in one market you need `R × f × q / (2S) ≥ 1`. With q = 10
and S = 2,000, that means R ≥ $400 per market-period while resting the whole
time. With 96 markets per series per day, that is far above any plausible
budget. The $1 floor is judged per market and period. Many sub-$1 crumbs across
96 markets a day add up to nothing.

**Estimate: $0 per day for $5 orders**, under any program allowed by S3, unless
(a) a 15-minute series carries large per-market rewards (≳ $400/period) **and**
we rest for most of the market's life, or (b) the books are much thinner than
recorded. Even in the best case it is about $1 per market, which also needs
two-sided Target Size depth. If the probe shows LIP schedules on these markets,
plug the real R, Target Size and DF into the formula above, with S from
recordings, before believing any number.

If the maker fee turns out to be 0.0175, each fill costs
`0.0175 × P(1−P)` per contract: 0.44¢ at 50¢, 0.37¢ at 30/70¢, 0.16¢ at 10/90¢.
Per-order rounding makes a 10-contract fill at 50¢ cost $0.0438 rather than
$0.05. For scale, `docs/flow-fade-2026-10-04.md` reports +1.77 / +1.00 / +0.53¢
per fill at queue-ahead 2k / 5k / 10k. At 0.0175, the 10k case is mostly gone
and the others shrink by about a quarter to a half. Re-run
`maker_sim.py --maker-fee 0.0175` rather than trusting this arithmetic.

## 5. Confirmed vs. uncertain

**Confirmed (read first-hand in official material):**

- Per-series `fee_type` / `fee_multiplier` exist, with four fee types, and
  maker = 0.25 (or 0.5 for combo) of the taker structure (S1).
- Public fee-change and event-override endpoints, and order/fill fee fields
  (S1).
- LIP formula, limits, eligibility, the $1 minimum, and its run until
  2027-01-01 (S2, S3, S4).
- The Liquidity Provider Program needs a Market Maker Agreement (S5).
- Fee Rebate tiers (S6).
- The Volume Incentive Program is ending (S7).

**Not first-hand (search summaries of blocked pages):**

- The 0.07 / 0.0175 coefficients and the July 2026 fee-schedule date (S8).
- Rounding to $0.0001 with the accumulator (S9).
- Help-center LIP wording, including the claim that non-U.S. users are
  excluded (S10).
- That KXBTC15M has no maker fee (S12, unofficial).

**Unknown until the probe runs:**

- `fee_type` and `fee_multiplier` for KXBTC15M, KXETH15M, KXSOL15M and KXBTCD.
- Any scheduled fee change or event override on them.
- Whether any of their markets have LIP schedules, and with what R, Target Size
  and DF.

**Unknown even after the probe:**

- Whether `fee_multiplier` scales the maker side.
- The rates behind a `flat` fee type (they are in the fee schedule PDF).
- Whether Kalshi actually pays LIP to small retail accounts in practice.

Checking `maker_fees_dollars` on the first live fills settles the maker fee for
good.

## 6. What to set

- `tools/research/maker_sim.py DEFAULT_MAKER_FEE`: leave it at `0.0` (with its
  "placeholder" label) until the probe prints `MAKER_RATE=0` for the series.
  If it prints `MAKER_RATE=0.0175` (or another value), pass
  `--maker-fee <value>` and change the default.
- `FlowFade.kt MAKER_FEE_STRESS = 0.0175`: correct as the stress value. It is
  exactly the maker rate a `quadratic_with_maker_fees` series with multiplier 1
  would charge.
- Do not add any liquidity-incentive income to simulations for $5 tickets.

## Update 2026-10-07 (Mis_bitcoin): maker fee CONFIRMED zero

`tools/research/fee_probe.py` run against the live API (all four series):

| Series | fee_type | multiplier | Maker rate | Taker rate | LIP programs |
|---|---|---|---:|---:|---:|
| KXBTC15M | quadratic | 1 | **0** | 0.07 | 0 |
| KXETH15M | quadratic | 1 | **0** | 0.07 | 0 |
| KXSOL15M | quadratic | 1 | **0** | 0.07 | 0 |
| KXBTCD | quadratic | 1 | **0** | 0.07 | 0 |

The "unknown maker fee" caveat is resolved: **resting orders are free** on
every series we trade. Combined with the tape study (takers −1.01¢/contract,
front-of-queue resting bids +0.29¢/contract), the maker path
(`RestingBid`, `FlowFade` paper mode) is structurally the cheapest way to
get exposure. Also noted: `KXCRYPTOLEAD15M` ("Coin Race 15 minutes", which
crypto has the highest return) is the only crypto series with upcoming
liquidity-incentive programs (520 listed). The incentive-programs endpoint
ignores series/market filters, so per-market payout terms could not be
confirmed from the public API alone; needs a paginated probe by market_id.
