# Kalshi rules, fees, programs and API facts for scalping / market making on KXBTC15M, KXETH15M, KXSOL15M (as of 2026-10-16)

**How this was researched (read first).** The sandbox blocks direct fetching of kalshi.com, docs.kalshi.com, help.kalshi.com, the Mintlify docs mirror and most news sites. Three kinds of source were usable:

1. **Primary, read first-hand.** These are Kalshi's CFTC filings, rulebook, contract terms, FIX spec and parameter files in Kalshi's public S3 bucket (`kalshi-public-docs.s3.amazonaws.com`, listed and downloaded 2026-10-16). Also read first-hand: the official Kalshi Python SDK `kalshi_python_sync` 3.34.0. It is generated from Kalshi's OpenAPI spec and was released 2026-10-13.
2. **docs.kalshi.com pages seen only through search-engine snippets.** These are marked "(search snippet)".
3. **Third-party blogs and news, plus this repo's own earlier live-API probes.** The probes are `docs/kalshi-fees-and-incentives.md` (fee probe, 2026-10-07) and `docs/ml-review-2026-09-27.md` (settlement study).

Treat "(search snippet)" and third-party items as lower confidence.

S3 base URL used below: `https://kalshi-public-docs.s3.amazonaws.com/`.

---

## 1. Fee schedule: taker formula, maker fees, which series charge makers, 2025–2026 changes, caps

### Takeaway
The general taker fee is still `roundup(0.07 × C × P × (1−P))`. Kalshi restated it in an official filing on 2026-10-14: the maximum is $0.0175 per contract at P = $0.50. Maker fees are set per series through `fee_type`. A live API probe on 2026-10-07 found that KXBTC15M, KXETH15M and KXSOL15M are all `quadratic` with multiplier 1, which means **no maker fee** (makers pay 0, takers pay 0.07). The newest fee-relevant change is a volume-tiered **Event Contracts Taker Rebate Program**, effective on or after 2026-10-27. It only pays accounts that are net makers with more than 0.5% of a series' maker volume.

### Cited Findings
- **Official taker coefficient (2026-10-14).** Kalshi's filing gives the example "for a fill executed at a price of $0.50 (the price at which the Base Taker Fee per contract is highest) the Base Taker Fee per contract is $0.0175 (0.07 × 0.50 × 0.50)". It defines "Base Taker Fee" as "the standard taker fee per contract for that fill under the Exchange's generally applicable fee schedule, before any rounding" — [Event Contracts Taker Rebate Program, filed 2026-10-14](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Event%20Contracts%20Taker%20Rebate%20Program.pdf)
- **Fee-schedule PDF.** The current PDF is titled "Fee Schedule for July 2026 - 7.7.26 Update" (seen only as a search-result title; the PDF itself is blocked) — [kalshi.com/docs/kalshi-fee-schedule.pdf](https://kalshi.com/docs/kalshi-fee-schedule.pdf)
- **Per-series fee types (SDK 3.34.0, 2026-10-13).** Each `Series` carries `fee_type` ∈ {`quadratic`, `quadratic_with_maker_fees`, `quadratic_with_combo_maker_fees`, `flat`} and a float `fee_multiplier`. Verbatim description: "'quadratic' is described by the General Trading Fees Table, 'quadratic_with_maker_fees' is described by the General Trading Fees Table with maker fees described in the Maker Fees section, 'quadratic_with_combo_maker_fees' is the same maker-fee structure with a 0.5 maker multiplier instead of 0.25, 'flat' is described by the Specific Trading Fees Table." — [kalshi-python-sync 3.34.0 (models/series.py)](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Maker coefficient.** On a `quadratic_with_maker_fees` series the maker fee is 0.25 × taker, i.e. `0.0175 × C × P × (1−P)`: at most 0.4375¢ per contract, charged only on fill, with cancels free. Source: SDK field description (2026-10-13) plus fee-schedule summaries (search snippets; not read first-hand) — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/); [pm.wiki fee explainer](https://pm.wiki/learn/kalshi-fees-explained)
- **The three 15-minute crypto series have no maker fee (live API probe, 2026-10-07).** KXBTC15M, KXETH15M, KXSOL15M and KXBTCD are all `fee_type=quadratic` with `fee_multiplier=1`, so maker rate 0 and taker rate 0.07 — [repo: docs/kalshi-fees-and-incentives.md, "Update 2026-10-07"](../../docs/kalshi-fees-and-incentives.md)
- **Scheduled fee changes are machine-readable.** `GET /series/fee_changes?series_ticker=&show_historical=` returns `{series_ticker, fee_type, fee_multiplier, scheduled_ts}`. `GET /events/fee_changes` returns per-event overrides (`fee_type_override`, `fee_multiplier_override`; null means the override is cleared). Each Market also has `fee_waiver_expiration_time`. All per SDK 3.34.0 (2026-10-13) — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Maker fees on combos (2026, secondary sources).** One outlet says maker fees were switched on for combos/parlays at 11:59 PM ET on 2026-08-19 at 50% of the taker fee. It adds that the online schedule gained a combos row while the PDF stayed dated 7/7/26 — [Bitcoin.com: Kalshi parlay maker fee brought in $26M in four weeks](https://news.bitcoin.com/igaming/kalshis-parlay-maker-fee-brought-26-million-four-weeks/) (search snippet). This matches the SDK's `quadratic_with_combo_maker_fees` (0.5 multiplier).
- **Rounding.** The fee is rounded up to $0.0001 per fill. A per-order "fee accumulator" refunds whole cents of over-rounding, so a multi-fill order converges to the single-fill fee. The older wording ("round up to the next cent") is an upper bound. Page listed as updated 2026-03-03 (search snippet, not read first-hand) — [docs.kalshi.com/getting_started/fee_rounding](https://docs.kalshi.com/getting_started/fee_rounding)
- **Actual charged fees are reported per order.** `Order.taker_fees_dollars`, `Order.maker_fees_dollars` and `Fill.fee_cost` are in the SDK (2026-10-13) — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **No explicit cap.** No per-order or per-contract fee cap was found in any source. The P(1−P) shape caps taker fees at 1.75¢ and maker fees at 0.4375¢ per contract — [repo doc](../../docs/kalshi-fees-and-incentives.md)
- **Taker Rebate Program (filed 2026-10-14, effective on/after 2026-10-27, runs to 2027-12-31).**
  - Who: open to "Self-Clearing Members", excluding Kalshi affiliates and IB/FCM customers.
  - Tiers: monthly "Effective Volume" = Σ p(1−p)c over maker and taker fills. Tier 1 is ≥ 2,000,000 → 15% taker rebate; Tier 2 is ≥ 20,000,000 → 30%; Tier 3 is ≥ 100,000,000 → 50%.
  - Per-series conditions: a rebate is paid in a series only if the account's maker share of that series' maker volume is > 0.5% **and** its maker volume exceeds its taker volume there.
  - Enhancement: Tier 3 in a "Qualifying Small-Tick Market" (tick ≤ $0.005 between $0.10 and $0.90) gets the rebate raised to max[50% × fee, fee − $0.005]. That cuts the net taker fee to $0.005 at P = 0.50.
  - Payment: by the 15th of the following month.
  - Interaction with other programs: only the highest per-trade incentive applies, and incentives are cut back to prevent net-negative (maker + taker) fees.
  - Source: [Taker Rebate Program filing](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Event%20Contracts%20Taker%20Rebate%20Program.pdf)
- **Fee Rebate Program (Jan 2025, effective 2025-01-28, runs to 2027-01-27).**
  - Rebate: monthly rebate on exchange fees paid. 0% on the first $99.99, then 20/40/60/80% tiers above $100/$250/$750/$2,000.
  - Exclusions: affiliates, Market Maker/FCM agreement members and Volume Incentive participants.
  - Source: [Fee Rebate Program Jan 2025](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Fee%20Rebate%20Program%20Jan%202025.pdf) (as summarised in [repo doc](../../docs/kalshi-fees-and-incentives.md))

### Inferences
- **Taker break-even.** A taker round trip at P = 0.50 costs about 3.5¢ per contract (1.75¢ × 2) under accumulator rounding. A scalper who crosses the spread on both legs must capture more than about 3.5¢ plus the spread, which is usually 1¢ on these books (see §3 and the repo's pair-maker notes). Maker-in / maker-out at 0 fee is the only structurally cheap way to scalp these series.
- **The Taker Rebate thresholds are far above retail scale.** Effective Volume of 2,000,000 is about 8,000,000 contracts traded at 50¢ in a month. That equals about $140,000 of gross taker-equivalent fees (2,000,000 × 0.07). The enhanced rebate also needs sub-cent ticks, which the 15-minute crypto contract terms do not have (§3). For a small account this program is effectively irrelevant. It mainly lowers costs for large net-maker firms, which may tighten competition at the touch.
- **Watch for fee changes.** A future maker fee on the 15-minute crypto series would show up first in `/series/fee_changes` (with `scheduled_ts`) before it takes effect. Polling that endpoint is a cheap guard.

### Gaps
- The July 2026 fee schedule PDF (and any newer online table) could not be read first-hand. Not verified: the list of `quadratic_with_maker_fees` series, the "Specific Trading Fees Table" rates and any special crypto-series rows.
- Unverified whether `fee_multiplier` also scales the maker side.
- Unknown whether the 15-minute crypto series have had any fee changes scheduled after 2026-10-07. Re-run `GET /series/fee_changes` to check.
- No official statement was found on whether Kalshi intends to put maker fees on crypto series.

---

## 2. Incentive and market-maker programs (LIP, volume incentives, rebates, DMM/DLP) and whether they apply to 15-minute crypto

### Takeaway
Two changes matter here.

- **LIP now names the 15-minute crypto markets.** The Oct 2026 amendment to the **Liquidity Incentive Program (LIP)**, effective on/after 2026-10-28 and running to 2027-01-01, lists "Crypto: 15 min" and "Crypto: Hourly" as eligible subcategories. The Discount Factor is fixed at 0.5 per tick, rewards range from $10 to $10,000 per business day per market, Time Periods last 1–7 days, and payouts below $1.00 are not paid.
- **The Volume Incentive Program has ended.** It terminated effective no earlier than 2026-10-13.

Designated market-maker benefits (reduced fees, higher position limits, DLP rewards up to a capped weekly amount) require a signed Market Maker Agreement. As of 2026-10-07, no LIP schedules were live on KXBTC15M, KXETH15M or KXSOL15M.

### Cited Findings
- **LIP Oct 2026 amendment: dates and scope.**
  - Filed 2026-10-14; effective on/after 2026-10-28; runs "until the earlier of January 1, 2027".
  - Appendix C lists crypto subcategories: "BTC, 15 min, Hourly, ETH, SOL, DOGE, XRP, BNB, HYPE, NEAR, Other tokens, Pre-Market".
  - Source: [Liquidity Incentive Program Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20Amendment.pdf)
- **LIP Oct 2026: variables (same filing).**
  - Discount Factor "set for all Eligible Markets at 0.5", meaning a 50% score reduction per tick away from the reference price.
  - Target Size > 100 and < 10,000 contracts.
  - Liquidity Reward ≥ $10 and ≤ $10,000 per business day per market; the cover letter says "$10,000 per business day for the most volatile Eligible Markets", with per-subcategory caps in a confidential appendix.
  - Time Period 1–7 days.
  - Public notice on the Liquidity Rewards page at least 24 h before start, and eligible contracts named at least 24 h before start.
  - Source: [LIP Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20Amendment.pdf)
- **LIP scoring (same filing; unchanged mechanics).**
  - Snapshots: one book snapshot per second at a random time. A snapshot is excluded if the market is closed or either side lacks Target Size.
  - Reference price: walk down from the best bid, adding size. The Reference Price is the level where cumulative size reaches Target/5. Stop at Target Size. A Yes ask counts as a No bid.
  - Score: DF^(ticks behind ref) × size, normalised per side.
  - Payout: user share of summed scores × Reward × (non-excluded / total snapshots). It is paid only if ≥ $1.00, rounded down to the cent.
  - Source: [LIP Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20Amendment.pdf)
- **LIP eligibility (Oct 2026).**
  - Who: all Kalshi members except affiliates and IB/FCM customers.
  - "Participants who have signed a market making agreement with Kalshi and have obligations or benefits in a market as a result of the Market Maker or Liquidity Provider programs will not receive any Liquidity Rewards in that market."
  - "An Eligible Contract will not be subject to more than one active Liquidity Reward at a time."
  - Source: [LIP Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20Amendment.pdf)
- **Earlier LIP terms (July 2026), now superseded.**
  - Limits: Target 100–20,000; DF ≤ 1.00; R $1–$1,000 per calendar day; period ≤ 31 days.
  - Source: [LIP July 15, 2026 Update](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20-%20July%2015%2C%202026%20Update.pdf); [July 30, 2026 Modification](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20-%20July%2030%2C%202026%20Modification%20to%20July%2015%2C%202026%20Update.pdf). These limits also appear as the struck-through text in the Oct 2026 tracked copy.
- **API surface for LIP (SDK 3.34.0, 2026-10-13).** `GET /incentive_programs` returns `IncentiveProgram{market_ticker, incentive_type, start_date, end_date, period_reward (centi-cents), discount_factor_bps, target_size_fp, max_reward_per_account (centi-cents), paid_out}` — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Live state as of 2026-10-07 (repo probe).** There were 0 LIP programs on KXBTC15M, KXETH15M, KXSOL15M and KXBTCD. The only crypto series with listed programs was KXCRYPTOLEAD15M ("Coin Race 15 minutes"). The endpoint ignored series/market filters — [repo doc](../../docs/kalshi-fees-and-incentives.md)
- **Volume Incentive Program terminated.**
  - Filed 2026-09-28; terms now end "the earlier of October 13, 2026".
  - Old terms: a per-market Volume Reward shared pro rata over volume at $0.03–$0.97; it could be limited to taker or maker side; it was capped at $0.005 per contract per participant; MM-agreement members were excluded.
  - Source: [Termination of Volume Incentive Program](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Termination%20of%20Volume%20Incentive%20Program.pdf). An Aug 2026 update had extended it to 2027-09-01 just before the termination: [VIP Aug 2026 Update](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Volume%20Incentive%20Program%20Aug%202026%20Update.pdf)
- **Designated Liquidity Provider (DLP) Market Making Program.** This is the May 2026 Liquidity Provider Program, renamed and amended.
  - Dates: filed 2026-10-14; effective on/after 2026-10-28; runs to 2027-12-01.
  - Eligibility: "Eligible Participants are all Kalshi members who have executed a Market Maker Agreement". DLPs are chosen through a publicly noticed Bidding Period of at least 3 days (email auctions@kalshi.com).
  - Scope: categories include Crypto (plus new Finance and Perpetuals).
  - Rewards: the earlier flat "$50,000 USD per week" per-series cap is replaced by confidential per-category caps. Minimum Uptime carries a pro-rata reward reduction. Payouts are monthly.
  - Source: [DLP Market Making Program Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Designated%20Liquidity%20Provider%20Market%20Making%20Program%20Amendment.pdf)
- **Market Maker Program (2025 Form Market Maker Agreement, Rulebook Chapter 4).**
  - Benefits (Rule 4.3): MMs "may receive benefits, including but not limited to financial benefits, reduced fees, differing Position Limits and Position Accountability Levels, and enhanced access".
  - Obligations (Rule 4.4): "maintaining two-sided markets within a defined spread and with a minimum depth".
  - Position levels (Rule 4.5): MM accountability levels default to 10× the non-MM level on contracts with quoting obligations, and MMs are exempt from Position Limits there.
  - Source: [Kalshi DCM Rulebook v1.29 (S3 upload 2026-08-17)](https://kalshi-public-docs.s3.amazonaws.com/regulatory/rulebook/Kalshi%20DCM%20Rulebook%20v.1.29.pdf)
- **Market Maker Program schedule is confidential.** Schedule II (liquidity conditions and benefits per covered product) was amended 2025-08-30 (live events) and 2026-06-03 (effective 2026-06-16; perpetual futures). Its contents are confidential (FOIA) — [Aug 30 2025 MM Program Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/August%2030%20Cover%20Letter%20to%20MM%20Program%20Amendment.pdf); [June 3 2026 MM Program Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/June%203%202026%20Amendments%20to%20MM%20Program%20-%20Cover%20Letter.pdf)
- **Named liquidity providers (S3 parameter file, 2025-09-21).** Kalshi's `liquidity.json` lists "committed_lps" ["SIG", "KT", "Alcedine", "Jump"] with spread/size requirements: e.g. NFL moneyline max spread 6¢, min 10,000 contracts. It covers NFL only; no crypto entries were present — [parameters/liquidity.json](https://kalshi-public-docs.s3.amazonaws.com/parameters/liquidity.json)
- **Susquehanna.** Susquehanna Government Products became Kalshi's "first dedicated institutional market maker" (press release 2024-04-03) — [BusinessWire](https://www.businesswire.com/news/home/20240403664852/en/Kalshi-Onboards-Its-First-Dedicated-Institutional-Market-Maker). A 2026-06-11 report says Susquehanna "is believed to be the largest market maker on Kalshi" (secondary, unnamed source) — [InGame](https://ingame.com/susquehanna-biggest-sports-kalshi-volume-knicks)
- **Kalshi's own market maker.** Kalshi owns a market maker, "Kalshi Trading", that takes the other side of some customer bets (reported by Bloomberg Dec 2025). A proposed class action filed Nov 2025 alleges it disadvantages customers. Source: search snippet summarising AOL/Bloomberg; not read first-hand — [AOL](https://www.aol.com/articles/15-minute-markets-cryptocurrency-prices-102000000.html)
- **Deposit and Trading Reward Program.** Filed 2026-10-14/15, effective on/after 2026-10-28. It gives rewards for deposits and trading activity; the reward methodology is confidential — [Deposit and Trading Reward Incentive Program](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Deposit%20and%20Trading%20Reward%20Incentive%20Program.pdf)

### Inferences
- **LIP could reach the 15-minute markets soon.** From 2026-10-28, Kalshi may post LIP schedules on 15-minute crypto markets. Two details make them fit badly: a Time Period lasts at least 1 day while each market lives 15 minutes, and the $1.00 floor applies per user per market per period. So rewards are likely posted per market across many short markets, or as day-long periods on rolling contracts.
- **Payout at small size.** Payout ≈ R × f × q / (2S), where R is the reward, f the fraction of time resting, q your resting size and S the qualifying size on the side. With books thousands of contracts deep, a 10–100-contract quoter is unlikely to clear $1 per market unless R is large. Re-check this when actual R and Target Size values are published. The Discount Factor of 0.5 per 1¢ tick means only orders at or within about 1–2¢ of the reference price earn meaningful score.
- **Who probably holds the touch.** "KT" in `liquidity.json` is likely "Kalshi Trading", the affiliate MM. That reading is an inference, not confirmed. The committed-LP set (SIG, KT, Jump, etc.) suggests professional firms with MM agreements, possible fee reductions and higher limits dominate the touch in high-volume series.

### Gaps
- No public LIP or DLP schedule for KXBTC15M, KXETH15M or KXSOL15M was found as of 2026-10-16. The Liquidity Rewards and DLP Rewards web pages are blocked here.
- Not public: the confidential per-subcategory caps for "Crypto 15 min" (Appendix D), the Market Maker Program Schedule II benefits (fee discounts, spreads, depth), and whether designated MMs exist on the 15-minute crypto series.
- No first-hand source was found for the Bloomberg "Kalshi Trading" story or the class-action status.

---

## 3. Order types and mechanics (limit, post-only, IOC/FOK, GTC/expiry, reduce-only, order groups, batch, STP, tick size, position limits, priority)

### Takeaway
Kalshi is a price-time-priority CLOB (Rule 5.9). The API supports the following:

- **Order types:** limit orders with `time_in_force` ∈ {`good_till_canceled` (optionally with `expiration_time`/`expiration_ts`), `immediate_or_cancel`, `fill_or_kill`}, plus `post_only`, `reduce_only` (IOC only in v2) and `cancel_order_on_pause`.
- **Self-trade prevention:** `taker_at_cross` or `maker`.
- **Order management:** amend, decrease, batch create/cancel, cancel-all, order groups (rolling 15-second fill-limit kill switch) and queue-position endpoints.
- **Sizes and prices:** fractional counts down to 0.01 contracts and fixed-point dollar prices.

The **15-minute crypto contracts keep a $0.01 minimum tick.** The Oct 2026 move to $0.001 ticks covered a list of other rulebooks, not CRYPTO. Each member's Position Accountability Level is $25,000 per strike.

### Cited Findings
- **Priority (Rulebook v1.29, Rule 5.9).** "Kalshi's trading algorithms execute all trades by matching orders according first by price and then time priority." Rule 5.10 says orders fill "or better", and the unfilled remainder rests at the limit price. RFQ-generated orders "execute at a lower time priority than all existing resting orders" — [Rulebook v1.29](https://kalshi-public-docs.s3.amazonaws.com/regulatory/rulebook/Kalshi%20DCM%20Rulebook%20v.1.29.pdf). SDK queue-position endpoints (`GET /portfolio/orders/{id}/queue_position`, `GET /portfolio/orders/queue_positions`) say queue position "is determined using a price-time priority" (2026-10-13) — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **v2 order API (SDK 3.34.0, 2026-10-13).**
  - Endpoints: `POST /portfolio/events/orders` (create), `/portfolio/events/orders/batched` (batch create/cancel), `/{order_id}/amend` (price, total count, expiration) and `/{order_id}/decrease` (`reduce_by` or `reduce_to`). The legacy "`/portfolio/orders` endpoint will be deprecated no earlier than May 6, 2026".
  - v2 shape: single-book `side` (bid/ask), `price` as a fixed-point dollar string, `count` as a fixed-point string.
  - `time_in_force` must be one of `fill_or_kill`, `good_till_canceled`, `immediate_or_cancel`. "GTT is not a valid API value". An expiring order is GTC plus `expiration_time`; IOC cannot be combined with an expiration.
  - Flags: `post_only` (bool). `reduce_only`: "Orders with reduce_only set to true will be rejected unless time_in_force is immediate_or_cancel." `cancel_order_on_pause`: cancel if trading is paused for any reason.
  - Also: `self_trade_prevention_type` (required in v2), `client_order_id`, `order_group_id`, `subaccount`, `exchange_index`.
  - Source: [SDK models/create_order_v2_request.py](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Legacy v1 create-order extras (SDK 2026-10-13).** `buy_max_cost` ("Maximum cost in cents. When specified, the order will automatically have Fill-or-Kill (FoK) behavior"). `sell_position_floor` is deprecated in favour of `reduce_only`. Integer `yes_price`/`no_price` run 1–99 — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Self-trade prevention (SDK 2026-10-13).** "`taker_at_cross` cancels the taker order when it would trade against another order from the same user; execution stops and any partial fills already matched are executed. `maker` cancels the resting maker order and continues matching." — [SDK models/self_trade_prevention_type.py](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Order groups (SDK 2026-10-13).** An order group has a "contracts limit measured over a rolling 15-second window … When the limit is hit, all orders in the group are cancelled and no new orders can be placed until reset". Up to 100,000 groups per user. Endpoints: create, reset, trigger (manual kill), update limit, delete. A WS channel `order_group_updates` also exists (pykalshi 2.0.0, 2026-07-27) — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/); [pykalshi](https://pypi.org/project/pykalshi/)
- **Batch and cancel-all (SDK 2026-10-13).** "The maximum batch size scales with your tier's write budget". `cancel_all_orders` cancels resting orders across every exchange shard, and "Newly placed orders may also be cancelled during the minute after the request." — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Fixed-point prices and sizes (SDK 2026-10-13).**
  - Prices: "Fixed-point US dollar string. Most request fields accept 2-4 decimal places … responses emit up to 6. Valid quote intervals for a given market are constrained by that market's price level structure." Each Market has `price_level_structure` and `price_ranges[] {start, end, step}`.
  - Sizes: count strings allow "Fractional contract values (e.g., "2.50") … minimum granularity is 0.01 contracts."
  - Source: [SDK models/market.py, price_range.py](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Tick for the 15-minute crypto contracts is $0.01.** The CRYPTO15M contract terms (S3 object last modified 2026-10-05) say: "Minimum Tick: The Minimum Tick size for the Contract shall be $0.01", "Price bands … at least $0.01 and at most $0.99" (cover filing), and "Position Accountability Level … $25,000 per strike, per Member" — [contract_terms/CRYPTO15M.pdf](https://kalshi-public-docs.s3.amazonaws.com/contract_terms/CRYPTO15M.pdf); [product-certifications/CRYPTO15M.pdf](https://kalshi-public-docs.s3.amazonaws.com/regulatory/product-certifications/CRYPTO15M.pdf)
- **Oct 2026 sub-cent tick change (filed and effective 2026-10-05).** It changes the "Minimum Tick" from $0.01 to $0.001 for a listed group of rulebooks, among them $INX, NASDAQ100, ECONSTAT, RATECUT(S), PRESPARTY, WTIMINMAX, RAINM and others. **CRYPTO / CRYPTO15M are not on the list** — [Group Amendment on Min Tick Size](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Group%20Amendment%20on%20Min%20Tick%20Size%20-%20Google%20Docs.pdf)
- **Combo price grid (changelog, search snippet).** Combo (multivariate) markets moved from a uniform $0.001 "deci_cent" grid to a finer grid ($0.0001 at the extremes, $0.001 in between, structure `center_deci_edge_centi_cent`); "other markets are unchanged". The docs warn against keying logic off structure names and say to use `price_ranges` instead — [docs.kalshi.com changelog](https://docs.kalshi.com/changelog); [docs.kalshi.com/getting_started/subpenny_pricing](https://docs.kalshi.com/getting_started/subpenny_pricing)
- **Fractional trading.** A rule filing changed Rule 13.1 to allow fractional shares (S3 upload 2026-02-23) — [Rulebook Filing (Change to Rule 13.1) - Fractional Shares](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Rulebook%20Filing%20(Change%20to%20Rule%2013.1)%20-%20Fractional%20Shares%20(1)%20(1).pdf)
- **Position rules (Rulebook v1.29).** Rule 5.18: Position Accountability Levels apply as set in contract terms, and a participant above the level must refrain from increasing or must reduce if instructed. Rule 5.19: Position Limits are "the maximum loss that can be incurred". MMs with quoting obligations are exempt — [Rulebook v1.29](https://kalshi-public-docs.s3.amazonaws.com/regulatory/rulebook/Kalshi%20DCM%20Rulebook%20v.1.29.pdf)
- **Messaging controls (Rulebook v1.29).** Members sending orders via automated functionality "must ensure adequate controls are in place to prevent excessive messaging". Rule 5.17 prohibits spoofing, layering, quote stuffing, self-matching and wash trading — [Rulebook v1.29](https://kalshi-public-docs.s3.amazonaws.com/regulatory/rulebook/Kalshi%20DCM%20Rulebook%20v.1.29.pdf); [LIP Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20Amendment.pdf)
- **Subaccounts (SDK 2026-10-13).** Subaccounts are "available to all users on the Advanced API tier and above … Maximum 63 numbered subaccounts per user (64 including the primary account)", with per-subaccount netting settings — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Observed spread.** The 15-minute BTC book "is almost always 1¢ wide" (repo recordings 2026-10-03 → 10-08, 440 markets) — [repo: docs/maker-research.md](../../docs/maker-research.md)

### Inferences
- **The tick is a hard floor on spread.** With a $0.01 tick and books that are usually 1¢ wide, a maker earns at most 1¢ per round trip at the touch. Queue position (price-time priority) is therefore the main competitive variable. The queue-position endpoints and `amend` (which presumably loses priority on price change; unverified) matter for scalping.
- **No enhanced taker rebate here.** These markets are not "Qualifying Small-Tick Markets" for the Taker Rebate enhancement unless Kalshi later shrinks the CRYPTO tick. Watch for a new 40.6 tick amendment that includes CRYPTO/CRYPTO15M.
- **No post-only exit at the close.** `reduce_only` only with IOC means you cannot rest a post-only reduce-only exit; you must manage exposure with order groups or explicit cancels.
- **Pause risk.** `cancel_order_on_pause` plus order groups give exchange-side kill switches. These matter for 15-minute markets that close and settle on a fixed schedule.

### Gaps
- The live `price_level_structure` / `price_ranges` values for KXBTC15M, KXETH15M and KXSOL15M markets were not read. The contract terms say $0.01, but the API value should be checked.
- Not documented in sources read: whether `amend` keeps time priority (e.g., on a size decrease) or resets it.
- The exact maximum batch size per tier was not found (only "scales with write budget").
- Not verified: whether fractional contracts are enabled on the crypto 15-minute series.
- No documented "post-only reject vs. slide" behaviour was found.

---

## 4. API: REST/WebSocket rate limits by tier, tier upgrades, WebSocket channels, FIX, latency/co-location

### Takeaway
REST traffic is metered with token buckets: separate read and write buckets, with a default endpoint cost of 10 tokens.

- **Tiers:** Basic, Advanced, Expert, Premier, Paragon, Prime, Prestige.
- **Getting there:** Advanced is self-service (one of your last 100 orders placed via API). Expert, Paragon and Prestige (and per the changelog Premier and Prime) are granted automatically from trailing-30-day share of exchange volume.
- **WebSocket:** channels include orderbook_delta, ticker, trade, fill, market_positions, user_orders, market_lifecycle_v2, order_group_updates, and `cfbenchmarks_value` / `cfbenchmarks_value_5hz` (the settlement index, with running 60-second averages).
- **FIX:** FIX 4.4-style gateways exist, with a 150 msg/s order-entry limit.
- **Co-location:** no public co-location offering was found.

### Cited Findings
- **Token-bucket model (SDK 2026-10-13).** "Each request deducts tokens equal to its endpoint cost; the bucket refills at refill_rate tokens per second up to bucket_capacity … otherwise the request is rejected with HTTP 429." Write buckets "at non-Basic tiers hold two seconds of budget". `GET /account/endpoint_costs`: "Default token cost … This is currently 10" — [SDK models/bucket_limit.py, get_account_endpoint_costs_response.py](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Tier names and live limits (SDK 2026-10-13).** `GET /account/limits` returns `usage_tier`, `read`/`write` buckets and `grants[]` (`level`, `expires_ts`, `source` = "volume" or "manual"). "Public Predictions tiers include Basic, Advanced, Expert, Premier, Paragon, Prime, and Prestige." — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Advanced tier is self-service (SDK 2026-10-13).** `POST /account/api_usage_level/upgrade` "Grants a permanent Advanced API usage-level grant … Criteria: at least 1 of the user's last 100 Predictions orders was created via API." — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/); also in the changelog (search snippet) — [docs.kalshi.com/changelog](https://docs.kalshi.com/changelog)
- **Volume-based tiers (official docs, search snippet).**
  - Review: "a daily review grants these tiers automatically". Volume share = trailing 30-day volume (both sides) ÷ (2 × previous month's total exchange volume). A grant lasts 30 days.
  - Earn / keep thresholds: Expert 0.075% / 0.05%; Paragon 0.25% / 0.20%; Prestige 1.00% / 0.80%.
  - Budgets (read/write tokens per second): Expert 600/600; Paragon 2,400/2,400; Prestige 12,000/9,600.
  - Progress endpoint: `GET /account/api_usage_level/volume_progress` returns trailing 30-day volume and earn/keep goals.
  - Source: [docs.kalshi.com/getting_started/rate_limits](https://docs.kalshi.com/getting_started/rate_limits); [volume progress API ref](https://docs.kalshi.com/api-reference/account/get-account-api-usage-level-volume-progress)
- **Other tier budgets (search snippets; partially conflicting).**
  - Basic 200 read / 100 write tokens per second, i.e. 20 / 10 default-cost requests per second.
  - Advanced 300 / 300.
  - Premier 1,200 / 1,200 (write capacity 3,600 in one snippet).
  - Prime 4,800 / 4,800.
  - Some third-party guides give Premier 1,000 and Prime 4,000. The changelog reportedly raised Premier, Paragon, Prime and Prestige limits by 20%, which would reconcile the two.
  - Third-party guides also give conflicting Paragon/Prestige numbers (2,000; 10,000/8,000; 6,000/8,000).
  - Source: [docs.kalshi.com rate_limits](https://docs.kalshi.com/getting_started/rate_limits); [botforkalshi](https://www.botforkalshi.com/blog/kalshi-api-tutorial); [turbinefi](https://www.turbinefi.com/blog/why-kalshi-built-for-automated-trading-2026); [quantvps](https://www.quantvps.com/blog/kalshi-order-book-api-endpoints-explained)
- **Older model (superseded).** Requests per second: Basic 20 read/10 write, Advanced 30/30, Premier 100/100, Prime 400/400. Premier needed "3.75% of monthly exchange volume plus technical competency" and Prime 7.5% — [vendor docs mirror](https://the-obstacle-is-the-way.github.io/kalshi-starter-code-python/_vendor-docs/kalshi-api-reference/) (search snippet). **Outdated; conflicts with the token model above.**
- **Batch cost.** One guide says batches charge per item (25 default-cost creates = 250 tokens), so batching saves round trips, not tokens (third-party, search snippet) — [botforkalshi](https://www.botforkalshi.com/blog/kalshi-api-tutorial)
- **WebSocket channels.**
  - pykalshi 2.0.0 (2026-07-27) channel constants: `orderbook_delta`, `ticker`, `trade`, `fill`, `market_positions`, `user_orders`, `market_lifecycle_v2`, `order_group_updates`, `multivariate_contract(s)` — [pykalshi](https://pypi.org/project/pykalshi/)
  - This repo's WS client also subscribes to `cfbenchmarks_value` and `cfbenchmarks_value_5hz` — [repo: KalshiWsClient.kt](../../app/src/main/java/com/dirk/kalshiodds/signal/ws/KalshiWsClient.kt)
- **`cfbenchmarks_value` channel (official docs, search snippet).**
  - Subscription: subscribe with `index_ids`, e.g. `BRTI`, `ETHUSD_RTI`, or `["all"]`. Without index_ids no values flow.
  - Message fields: each message carries the raw vendor frame plus `avg_60s_data`, a trailing per-tick 60-second average with window size and start/end timestamps.
  - Quarter-hour field: "`last_60s_windowed_average_15min` … appears only in the final minute before a quarter-hour close".
  - Auth: API-key headers are required at the WebSocket handshake even for public channels.
  - `cfbenchmarks_value_5hz` "streams CF Benchmarks index values at up to 5 updates per second on indices the vendor publishes at 200ms granularity"; the 1/s channel is unchanged.
  - Source: [docs.kalshi.com/websockets/cfbenchmarks-value](https://docs.kalshi.com/websockets/cfbenchmarks-value); [docs.kalshi.com/changelog](https://docs.kalshi.com/changelog)
- **REST data freshness (SDK 2026-10-13).** "There is typically a short delay before exchange events are reflected in the API endpoints … combine API responses … with WebSocket data". `GET /exchange/user_data_timestamp` shows when balance, order, fill and position data was last validated — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Exchange shards (SDK 2026-10-13).** Orders, cancels and balances take `exchange_index` ("Exchange shard index … Use -1 to auto-route by market ticker"). There are cross-shard balance transfers and a "target balance allocation" across shards. One third-party guide says crypto and commodities sit on their own index (unverified) — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/); [predictefy](https://blog.predictefy.com/kalshi-api-guide)
- **FIX spec (v1.0.12; S3 upload 2025-06-27; XML dictionary updated 2026-10-16).**
  - Gateways: order entry `fix.elections.kalshi.com:8228` (no retransmission) and `fix-rt.elections.kalshi.com:8230` (with retransmission), drop copy `fix-dc…:8229`, post-trade `:8231`, RFQ `:8232`.
  - "rate limit of 150 messages per second for the order entry session" (application messages only).
  - Sessions are force-logged-out daily 2:00–2:10 AM ET. Heartbeat 30 s.
  - Order fields: TimeInForce Day / GTC / IOC / GTD. ExecInst PostOnly (added 2025-06-12). SelfTradePreventionType (added 2024-04-30). Mass cancel. Market Settlement Report (35=UMS).
  - Source: [Kalshi_FIX_API_v1.pdf](https://kalshi-public-docs.s3.amazonaws.com/fix/Kalshi_FIX_API_v1.pdf); [kalshi-fix-dictionary.xml](https://kalshi-public-docs.s3.amazonaws.com/fix/kalshi-fix-dictionary.xml)
- **Measured lag (repo).** "Kalshi's book reacts to Coinbase within about a second" (`ws_lag.py`, 2026-10-08) — [repo: docs/maker-research.md](../../docs/maker-research.md)

### Inferences
- **Basic tier is tight for quoting.** About 10 writes per second (100 tokens ÷ 10) is thin for two-sided quoting across 3 series with frequent amends. Self-upgrading to Advanced (300 write tokens, about 30 writes per second, two-second burst) costs nothing and should be the first step. Expert needs 0.075% of exchange volume, which is out of reach for small accounts.
- **Use the 5 Hz channel.** `cfbenchmarks_value_5hz` together with `avg_60s_data` and `last_60s_windowed_average_15min` lets a bot track the settlement average directly, without proxying via Coinbase. That matters most in the final minute.

### Gaps
- The official tier table for Basic, Advanced, Premier and Prime, and the Premier/Prime earn/keep thresholds under the new volume model, were not read first-hand. The snippets conflict.
- WebSocket limits were not found: connections per key, subscriptions per connection, message rates.
- FIX access eligibility (which tier or agreement) was not found.
- No public information on co-location, cross-connects, data-centre location or documented latency SLAs.
- No authoritative list of per-endpoint token costs (`GET /account/endpoint_costs` must be called live).

---

## 5. Settlement of the 15-minute crypto markets

### Takeaway
The governing contract terms (CRYPTO15M) settle on a **simple average of the CF Benchmarks real-time index (BRTI for BTC, ETHUSD_RTI for ETH, SOL equivalent) over the 60 seconds before the close time**. Missing or incomplete data resolves affected strikes **No**. Settlement is "no later than the day after" expiration unless under review.

For the up/down series, the strike (`floor_strike`) equals the previous window's 60-second average, and a tie settles **YES** (≥). This repo's 8,080-market study of the API confirmed it (2026-09). Kalshi exposes the running average via the `cfbenchmarks_value` WebSocket channel.

### Cited Findings
- **Underlying and source (CRYPTO15M contract terms, S3 object modified 2026-10-05).** "The Underlying for this Contract is the spot price of one <cryptocurrency> in U.S. dollars at <time>, according to a simple average of the CF <cryptocurrency> <index> for the 60 seconds prior to <time> … Revisions to the Underlying made after Expiration will not be accounted for". "The Source Agency is CF Benchmarks"; <index> is "a specific CF Benchmarks Index (e.g. 'Bitcoin Real-Time Index')" — [contract_terms/CRYPTO15M.pdf](https://kalshi-public-docs.s3.amazonaws.com/contract_terms/CRYPTO15M.pdf)
- **Comparators and missing data (same).**
  - "'Above X' means strictly greater than X … 'at least X' means X or greater."
  - "If no data is available or incomplete on the Expiration Date at the Expiration Time, then affected strikes resolve to No."
  - Settlement Value $1.00.
  - "Settlement Date … no later than the day after the Expiration Date, unless the Market Outcome is under review pursuant to Rule 7.1".
  - Source: [contract_terms/CRYPTO15M.pdf](https://kalshi-public-docs.s3.amazonaws.com/contract_terms/CRYPTO15M.pdf)
- **Template oddity.** The template's "Expiration Time … 10:00 AM ET" and strike increments of $0.01 come from the generic CRYPTO certification (originally filed 2025-07-18). The real close time comes from each market's `close_time` / `expected_expiration_time` — [product-certifications/CRYPTO15M.pdf](https://kalshi-public-docs.s3.amazonaws.com/regulatory/product-certifications/CRYPTO15M.pdf)
- **Up/down rule verified from the API (repo, 2026-09).** In `settlement_study.py`, 8,080 markets: "YES when the simple average of the last 60 seconds of CF Benchmarks' index before close is *at least* the previous window's 60 s average. Ties settle YES, 100% of results match." The best Coinbase proxy (60 s time-weighted average) has a residual of 0.47 bp for BTC (≈ $4), 0.89 bp for ETH and 1.13 bp for SOL — [repo: docs/ml-review-2026-09-27.md](../../docs/ml-review-2026-09-27.md)
- **Third-party agreement.** Third-party sources agree that ties resolve YES (`strike_type` "greater_or_equal"). They also warn that the window's strike often shows "Target price: TBD" at the first poll of a new window, so read `floor_strike` from the API — [HF dataset DegenHedge/kalshi-btc-15m](https://huggingface.co/datasets/DegenHedge/kalshi-btc-15m) (search snippet); [predictionmarketspicks KXBTC15M](https://predictionmarketspicks.com/articles/kalshi-bitcoin-15-minute-markets)
- **Not BRRNY.** Kalshi's help center says crypto contracts settle on a 60-second average of CF Benchmarks Real-Time Indexes, which publish once per second. Settlement is not the once-daily BRRNY fixing — [help.kalshi.com crypto markets](https://help.kalshi.com/en/articles/13823838-crypto-markets) (search snippet); [predictionmarketspicks](https://predictionmarketspicks.com/articles/how-kalshi-settles-bitcoin) (third-party)
- **API settlement fields (SDK 2026-10-13).** Market `settlement_timer_seconds` ("The amount of time after determination that the market settles"), `expiration_value` ("The value that was considered for the settlement"), `floor_strike`, `strike_type`, `settlement_ts`, `can_close_early`, `early_close_condition` — [SDK models/market.py](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **Outcome review (Rulebook v1.29).** Under Rule 6.3(c), when the outcome cannot be determined, "Kalshi has sole discretion to interpret a Contract's Terms" and determines payouts. Rule 7.1 sets out the Market Outcome Review Process — [Rulebook v1.29](https://kalshi-public-docs.s3.amazonaws.com/regulatory/rulebook/Kalshi%20DCM%20Rulebook%20v.1.29.pdf)
- **Running average in real time (official docs, search snippet).** `cfbenchmarks_value` messages carry `avg_60s_data` (trailing 60 s average per tick) and, in the final minute before a quarter-hour close, `last_60s_windowed_average_15min`, which is the settlement-style average — [docs.kalshi.com/websockets/cfbenchmarks-value](https://docs.kalshi.com/websockets/cfbenchmarks-value)
- **Independent check (third-party).** One site recorded the index live and compared its 60-second averages with Kalshi's settles, finding a "median gap of 13 cents" — [predictionmarketspicks "Is Kalshi Bitcoin Rigged?"](https://predictionmarketspicks.com/articles/is-kalshi-bitcoin-rigged) (search snippet)

### Inferences
- **The final minute is the endgame.** In the final 60 s, part of the settlement average is already printed. Only the remaining seconds can move it, so fair value converges sharply. This is where the 5 Hz feed and `last_60s_windowed_average_15min` give an edge. Makers resting quotes into the last minute face severe adverse selection.
- **The data-outage tail is asymmetric.** "No data or incomplete → No" means YES holders carry the tail risk of a CF Benchmarks outage. It is small but real.
- **Capital turnover.** Payout timing is governed by `settlement_timer_seconds` after determination. The contract only guarantees "no later than the day after", which matters for capital recycling between consecutive 15-minute windows.

### Gaps
- The exact `settlement_timer_seconds` for KXBTC15M, KXETH15M and KXSOL15M was not read. Also unread: the series-specific `rules_primary` text, including whether any "trimmed average" variant applies.
- The SOL index id (e.g. "SOLUSD_RTI") was not confirmed from a primary source.
- No official statement was found on how often Kalshi has invoked outcome review on 15-minute crypto markets.

---

## 6. 2025–2026 Kalshi changes that affect scalping economics (new fees/rebates, tick changes, new series, MM partnerships, volume)

### Takeaway
The key changes are listed below in chronological order. Fees on the three 15-minute crypto series themselves were unchanged (taker 0.07, maker 0) as of 2026-10-07. Third-party data put the 15-minute crypto markets among Kalshi's largest fee generators: BTC was estimated at about $60M in fees in Sept 2026.

### Cited Findings
- **2025-07-18.** Generic CRYPTO contract self-certified (CF Benchmarks, 60 s average, $0.01 tick) — [product-certifications/CRYPTO15M.pdf](https://kalshi-public-docs.s3.amazonaws.com/regulatory/product-certifications/CRYPTO15M.pdf)
- **2025-08 / 2025-09.** "Volume and Liquidity Incentive Program – August 2025" and the "Liquidity Incentive Program September 8 2025 Backup Filing" (titles from the S3 listing; contents not read) — [Volume and Liquidity Incentive Program - August 2025](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Volume%20and%20Liquidity%20Incentive%20Program%20-%20August%202025.pdf)
- **2025-08-30.** MM Program Schedule II amended (live-event liquidity conditions; confidential) — [MM Program Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/August%2030%20Cover%20Letter%20to%20MM%20Program%20Amendment.pdf)
- **Early 2026.** Fractional shares rule change (Rule 13.1; S3 upload 2026-02-23) and LIP Feb 11, 2026 update (effective 2026-02-28) — [Fractional Shares filing](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Rulebook%20Filing%20(Change%20to%20Rule%2013.1)%20-%20Fractional%20Shares%20(1)%20(1).pdf); [LIP Feb 2026](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20-%20February%2011%2C%202026%20Update.pdf)
- **2026-05-06 or later.** Legacy `/portfolio/orders` deprecation (v2 `/portfolio/events/orders` replaces it) — [SDK](https://pypi.org/project/kalshi-python-sync/3.34.0/)
- **May–June 2026.** Liquidity Provider Program, $50k/week per incentivized series, MM-agreement holders only; amended 2026-06-11 and 2026-06-18 — [LP Program (Modified, Clean)](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Kalshi%20-%20Liquidity%20Provider%20Program%20-%20Cover%20Letter%20and%20Appx%20A%20(Modified%2C%20Clean).pdf)
- **2026-07-07.** Fee schedule update ("Fee Schedule for July 2026 - 7.7.26 Update"; title only) — [kalshi-fee-schedule.pdf](https://kalshi.com/docs/kalshi-fee-schedule.pdf)
- **2026-07-15 / 07-30.** LIP updates (eligibility widened to all members except affiliates and IB/FCM; ran to 2027-01-01) — [LIP July 30 2026](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20-%20July%2030%2C%202026%20Modification%20to%20July%2015%2C%202026%20Update.pdf)
- **2026-07-23 to 2026-10-13: new 15-minute series beyond crypto.**
  - New certifications: COMMOD15M (2026-07-23), INX15M and NASDAQ10015M (2026-07-29), CRYPTORETURN15M / CRYPTORETURNCOMPARE (2026-07-30; "which of BTC, ETH, SOL, XRP, HYPE has the highest/lowest return", listed from 2026-07-31), FXGBPUSD15M (2026-09-01), 2/5/7/10/30-year yield 15M (2026-10-13).
  - Source: S3 listing of `regulatory/product-certifications/`, e.g. [CRYPTORETURN15M.pdf](https://kalshi-public-docs.s3.amazonaws.com/regulatory/product-certifications/CRYPTORETURN15M.pdf)
- **2026-08-19 (secondary).** Maker fees switched on for combos at 50% of the taker fee — [Bitcoin.com](https://news.bitcoin.com/igaming/kalshis-parlay-maker-fee-brought-26-million-four-weeks/) (search snippet)
- **2026-09-28.** Volume Incentive Program terminated, effective no earlier than 2026-10-13 — [Termination filing](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Termination%20of%20Volume%20Incentive%20Program.pdf)
- **2026-10-05.** Minimum tick changed to $0.001 for about 38 non-crypto rulebooks; CRYPTO is not included — [Group Amendment on Min Tick Size](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Group%20Amendment%20on%20Min%20Tick%20Size%20-%20Google%20Docs.pdf)
- **2026-10-12.** Large Volume OTC Swap Trading Rebate Program terminated (title from the S3 listing) — [Termination of Large Volume OTC Swap Trading Rebate Program](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Termination%20of%20Large%20Volume%20OTC%20Swap%20Trading%20Rebate%20Program.pdf)
- **2026-10-14 / 15 filings.**
  - Event Contracts Taker Rebate Program (on/after 2026-10-27).
  - LIP amendment naming "Crypto 15 min" and "Crypto Hourly" (on/after 2026-10-28).
  - DLP Market Making Program amendment (on/after 2026-10-28).
  - Deposit and Trading Reward Program (on/after 2026-10-28).
  - Source: [Taker Rebate](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Event%20Contracts%20Taker%20Rebate%20Program.pdf); [LIP Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Liquidity%20Incentive%20Program%20Amendment.pdf); [DLP Amendment](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Designated%20Liquidity%20Provider%20Market%20Making%20Program%20Amendment.pdf); [Deposit & Trading Reward](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Deposit%20and%20Trading%20Reward%20Incentive%20Program.pdf)
- **API tier changes (2026, changelog via search snippets).** Self-serve Advanced upgrade, automatic volume-based tiers (Expert/Paragon/Prestige plus Premier/Prime), and a reported 20% limit increase for Premier, Paragon, Prime and Prestige. Dates not visible in snippets — [docs.kalshi.com/changelog](https://docs.kalshi.com/changelog)
- **Volume and fee statistics (third-party estimates, Oct 2026).**
  - BTC markets about $60.4M in fees in Sept 2026; gold 15-minute about $5M; ETH about $2.6M. These estimates exclude rebates paid to market makers.
  - Sept 2026 contracts: gold 15-minute about 539–542M vs ETH about 317–318M (Aug: 213M gold vs 271M ETH).
  - Source: [Bitcoin.com: 15-minute gold bets top ether](https://news.bitcoin.com/igaming/kalshis-15-minute-gold-bets-top-ether-fast-markets-drive-fees/); [Parameter](https://parameter.io/kalshis-15-minute-gold-contracts-generate-5m-in-fees-surpassing-ethereum-markets/); [Cryptonomist 2026-10-07](https://en.cryptonomist.ch/2026/10/07/kalshi-gold-markets-volume/) (all via search snippets)
- **Crypto volume share (third-party).** About $4.7B Kalshi crypto volume over 30 days (+22%), about 12% of prediction-market trading, "mostly 15-minute Bitcoin bets" — [DeFi Rate](https://defirate.com/prediction-markets/crypto/) (search snippet)
- **Coinbase distribution (secondary).** Coinbase began offering 15-minute crypto contracts on Oct. 6, with Kalshi supplying the infrastructure (year not explicit in snippet; context suggests 2026) — [Cryptonomist](https://en.cryptonomist.ch/2026/10/07/kalshi-gold-markets-volume/) (search snippet)
- **Single-window anecdote.** "Over $1.3 million in trading volume on a single 15 minute window" for Bitcoin (anecdotal review) — [WagerTalk](https://www.wagertalk.com/prediction-markets/kalshi/15-minute-markets) (search snippet)

### Inferences
- **The economics favour resting orders.** Fees are 0 for makers on the 15-minute crypto series, the Volume Incentive Program (which could pay up to $0.005 per contract on volume) is gone, and the new programs reward large net-makers. Small scalpers' P&L is now spread capture minus adverse selection, with no rebates.
- **LIP is the one route to extra income** if Kalshi actually posts "Crypto 15 min" schedules after 2026-10-28.
- **Implied BTC volume.** About $60M a month of BTC taker fees implies very large taker flow, at least about 3.4B contracts at 50¢ if all were at the 1.75¢ maximum. Professional makers (SIG, Jump, Kalshi Trading) are competing for that flow at the 1¢ touch.
- **Coinbase flow.** If Coinbase routes 15-minute crypto orders into Kalshi's book, retail taker flow (and queue competition) on these series may grow from Oct 2026.

### Gaps
- No official Kalshi volume statistics for KXBTC15M, KXETH15M or KXSOL15M were found; all volume and fee figures are third-party estimates.
- No evidence was found of Kalshi 5-minute crypto markets (none in the S3 product-certification listing as of 2026-10-16).
- No official Kalshi blog or press release on the Coinbase 15-minute integration was read.
- Exact changelog dates for the rate-limit tier changes and the cfbenchmarks_value_5hz launch were not visible.
- No primary source on Kalshi Trading (the affiliate MM) activity in crypto 15-minute markets.
