# Strategies in Practice: Scalping, Market Making and Arbitrage on Kalshi / Polymarket Short-Dated Crypto Markets (research as of 2026-10-16)

> **Method note for the writer.** Full-page fetches failed in this session (DNS errors for every non-GitHub domain, and the GitHub API refused access to third-party repos), so every claim below comes from search-engine result summaries of the cited pages, not from reading the pages in full. Numbers are given as the search summaries reported them. Treat exact figures as "reported by the source" and re-check them before quoting them as hard numbers. Most sources are 2026 vendor blogs or trade press. The academic papers are flagged where they appear.
>
> **Fee-assumption warning (affects every maker strategy below).** The brief assumes 0 maker fee. Two 2026 sources say Kalshi started charging makers: a summary of Kalshi's Liquidity Incentive Program material says "after August 19, 2026 ... a fill against [a resting order] can also trigger the maker fee Kalshi turned on that night" ([Kalshi Help – Liquidity Incentive Program](https://help.kalshi.com/en/articles/13823851-liquidity-incentive-program) / [CFTC LIP filing, July 15 2026](https://www.cftc.gov/filings/orgrules/rules07152610358.pdf)), and TurbineFi says "Kalshi began charging makers after [the Whelan] sample ended" ([TurbineFi, 4,904 backtests](https://www.turbinefi.com/blog/5000-strategy-backtest-kalshi-btc-15m)). A bot vendor says the opposite, that Kalshi charges no maker fee on KXBTC15M ([botforkalshi.com](https://www.botforkalshi.com/blog/kalshi-bitcoin-bot)). The fee researcher must settle this. If makers now pay a fee on the 15-minute series, the economics of strategies 1, 6 and 7 change materially.

## Which strategies are documented on these markets, and how do they work?

### Takeaway
About ten strategy families are documented for Kalshi KXBTC15M/KXETH15M and Polymarket 5m/15m/1h up/down markets. Only a few have evidence beyond marketing: latency/oracle arbitrage on Polymarket (real, now taxed with dynamic taker fees), settlement-window manipulation on Polymarket 5-minute markets (an academic paper; largely absent on 15-minute markets), and the maker-beats-taker result on Kalshi (an academic paper). Late-favourite buying, both-sides-under-$1 arbitrage and cross-venue arbitrage have thin or near-zero net edges in the available data. Directional and mean-reversion signal bots mostly lose in vendor backtests.

### Cited Findings

**Contract mechanics that shape all strategies**
- KXBTC15M opens a new up/down BTC contract every 15 minutes, 24/7, and settles on a 60-second average of the CF Benchmarks Bitcoin Real-Time Index — [kalshibacktest.com](https://kalshibacktest.com/resources/kalshi-btc-15-minute-markets); [predictionmarketspicks.com](https://predictionmarketspicks.com/articles/kalshi-bitcoin-15-minute-markets) (secondary sources, 2026).
- The index is sampled about once per second over the final minute (about 60 prints). The same 60-second-average method is reported for 15-minute, hourly, daily, weekly, monthly and yearly BTC contracts. A wick that spikes and reverses inside the minute "barely moves the average" — [predictionmarketspicks.com, How Kalshi Settles Bitcoin](https://predictionmarketspicks.com/articles/how-kalshi-settles-bitcoin); [Caliper Markets](https://calipermarkets.com/blog/kalshi-bitcoin-settlement-price). This could not be checked against Kalshi rule text, and one page's claim that short-dated contracts use "BRRNY" rather than BRTI is disputed.
- Polymarket: on **August 7, 2026** Polymarket reportedly changed its short-term crypto up/down markets so that both the "price to beat" and the closing price are Chainlink-computed time-weighted averages instead of single snapshots — [tradoxvps.com](https://tradoxvps.com/polymarket-twap-settlement/); [wooyang/polymarket-arbitrage-bot README](https://github.com/wooyang/polymarket-arbitrage-bot) (mentions a "Chainlink TWAP RTDS" feed with a 60-second settlement window for 15-minute markets). This is not confirmed from a Polymarket primary source.

**S1. Latency / oracle arbitrage (spot leads contract)**
- Mechanics: bots buy when Polymarket 15-minute BTC/ETH/SOL odds lag "confirmed spot momentum" on Binance and Coinbase, typically entering near 50/50 and exiting on convergence or holding to settlement — [Yahoo Finance, "Arbitrage Bots Dominate Polymarket"](https://finance.yahoo.com/news/arbitrage-bots-dominate-polymarket-millions-100000888.html); [Finance Magnates](https://www.financemagnates.com/cryptocurrency/polymarket-introduces-dynamic-fees-to-curb-latency-arbitrage-in-short-term-crypto-markets/).
- The flagship anecdote: one bot reportedly turned **$313 into $414,000 in a single month**, trading only BTC/ETH/SOL 15-minute up/down markets with $4,000–$5,000 bets and a **98% win rate**. The figures come from analyst "Dexter's Lab" via Yahoo Finance, are not verified on-chain, and date from late 2025 to early 2026 — [Yahoo Finance](https://finance.yahoo.com/news/arbitrage-bots-dominate-polymarket-millions-100000888.html).
- Lag size is disputed and unsourced: one vendor says Polymarket trails Binance by **30–90 seconds**, another source says about **10–15 minutes** to adjust after large moves — [quantvps.com](https://www.quantvps.com/blog/binance-to-polymarket-arbitrage-strategies), as summarised by search; both are unverified.
- Platform response: Polymarket added **taker-only, dynamic fees on 15-minute crypto up/down markets**, dated **about January 6, 2026**, explicitly "to neutralise latency-based arbitrage." The fee peaks near 50% odds and falls toward 0%/100%. Reported caps are about **3.15% on a 50¢ contract**, or "up to 3%." Fees fund a **daily USDC maker-rebate pool** paid in proportion to filled maker liquidity — [Cointelegraph](https://cointelegraph.com/news/polymarket-quietly-adds-taker-fees-15-minute-crypto-markets); [Finance Magnates](https://www.financemagnates.com/cryptocurrency/polymarket-introduces-dynamic-fees-to-curb-latency-arbitrage-in-short-term-crypto-markets/); [Blockonomi](https://blockonomi.com/polymarket-launches-maker-rebates-program-with-taker-fees-on-15-minute-crypto-markets/); [DeFi Rate](https://defirate.com/news/polymarket-users-approve-new-crypto-market-fees/). The exact formula was not retrieved.
- Small-sample retail evidence: one developer reports a **69.6% win rate over 23 real-money BTC trades** (February 2025, older) from a latency-style strategy — [chudi.dev](https://chudi.dev/blog/how-i-built-polymarket-trading-bot). Twenty-three trades is statistically meaningless.
- Kalshi analogue: a cross-market "GBM model + cross-exchange arbitrage detection + Kelly sizing" bot for Kalshi BTC binaries exists, with no reported P&L found — [brandononchain/kalshibot](https://github.com/brandononchain/kalshibot).

**S2. End-of-window settlement-average strategies (using the known part of the final-minute average)**
- No published write-up found that describes trading against the partially-known Kalshi 60-second average. Search returned mechanics only — [predictionmarketspicks.com](https://predictionmarketspicks.com/articles/how-kalshi-settles-bitcoin).
- Close adjacent evidence (academic, June 30 2026): Dai, Jia & Yu, "Settlement Manipulation in Prediction Markets" (Stanford / SMU). After Polymarket launched its **5-minute** BTC contract, **settlement-time spot order flow on Binance spiked**, followed by large post-settlement price reversals. Manipulators captured profit "mostly from retail." Press coverage cites **821 wallets capturing about $8.2M**. The authors find manipulation is **"largely absent" in 15-minute contracts** and propose longer horizons as the fix — [arXiv 2606.31675](https://arxiv.org/pdf/2606.31675); [SSRN 7028398](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=7028398); [Bitcoin Magazine](https://bitcoinmagazine.com/news/traders-took-8-2-million-from-polymarkets). Press reports say the mechanism was large trades in the **final seconds** moving the Chainlink reference across the strike — [polymarkets.co.il, "The Last Three Seconds"](https://polymarkets.co.il/en/news/polymarket-bitcoin-five-minute-settlement-manipulation).
- Late-flip base rates on Kalshi KXBTC15M (OddsShopper, 2026): across 200 and then about 1,600 more settled windows, **wide finishes rarely flip late (11 of 958)**. Flips concentrate in close windows. In an October 9–11 sample, the losing side was priced **≥70¢ with two minutes left in 10 of 19 flips**, and in four highlighted cases the losing Yes was **52–60¢ with two minutes left** — [OddsShopper, "Kalshi 15-Minute Bitcoin Rigged? We Checked 200 Windows"](https://www.oddsshopper.com/articles/prediction-markets/are-kalshi-15-minute-bitcoin-markets-rigged). A related 3,320-window check exists — [predictionmarketspicks.com](https://predictionmarketspicks.com/articles/is-kalshi-bitcoin-rigged).

**S3. Buying near-certain favourites late in the window ("done-deal", "bonding")**
- Vendor data, 2026: across **6,298 settled KXBTC15M windows**, buying the leading side was about **+0.67¢/contract gross**, while the measured average taker fee on those fills was about **1.55¢/contract**, giving a net edge of roughly zero or negative. At 95¢ you risk 95¢ to make 5¢ before fees — [botforkalshi.com](https://www.botforkalshi.com/blog/kalshi-bitcoin-bot) (vendor self-report).
- Open-source example: fabricio-sousa/mystic-bot runs a late-window "Done-Deal" entry on KXBTC15M, confirmed by BTC momentum filters, with a read-only dashboard. No P&L was retrieved, and the repo URL returned 404 when fetched, so it may have been deleted or made private — [GitHub listing via search](https://github.com/fabricio-sousa/mystic-bot).
- Favourite-longshot bias on Kalshi overall (academic): high-price contracts win more often than their price implies and earn small positive returns. Low-price contracts lose heavily — [Bürgi, Deng & Whelan, "Makers and Takers" (UCD WP25/19, July 2025; GWU 2026-001, Feb 2026)](https://www.karlwhelan.com/Papers/Kalshi.pdf); [VoxEU column](https://cepr.org/voxeu/columns/economics-kalshi-prediction-market). Not specific to 15-minute crypto.
- Polymarket "bonding" (buying 95–99¢ outcomes): a YouTube piece describes a trader betting about **$7M to make 0.2%**. This is anecdote, undated and unverified — [YouTube](https://www.youtube.com/watch?v=pGYNN-sEUVw).

**S4. Complete-set / both-sides-under-$1 arbitrage (single venue)**
- Mechanics: buy Up and Down when the combined ask is below $1 (for example, 45¢ + 46¢ = 91¢ locks 9¢). A "dump-and-hedge" variant buys the side that crashes early in the window, then buys the other side once the combined cost falls below a threshold — [moonsat, "6 Main Types of Trading Bots on Up/Down Markets"](https://moonsat.medium.com/6-main-types-of-trading-bots-on-up-down-markets-on-polymarket-12893a484f28); [wooyang/polymarket-arbitrage-bot](https://github.com/wooyang/polymarket-arbitrage-bot).
- Evidence: one developer reports **92% wins (162 W / 14 L) but only about $2 net P&L** from arbitrage-style trades — [dev.to/manja316, "176 Trades on Polymarket"](https://dev.to/manja316/176-trades-on-polymarket-what-my-bot-actually-made-its-not-what-you-think-3iib). Thin 5m/15m books mean large orders move price and erase the margin — [polymtradebot.com review](https://polymtradebot.com/blog/review-of-the-polymarket-trading-bot-script) (vendor). The account handle "gabagool" turned up nothing.
- On Kalshi, Yes and No live in one book (a Yes bid is a No ask), so a two-leg under-$1 lock is structurally rarer. This is an inference; no source found.

**S5. Cross-venue Kalshi vs Polymarket arbitrage on the same 15-minute window**
- The two contracts are **not the same contract**. Each venue sets its own strike at window open and settles on its own source (Kalshi: CF Benchmarks RTI 60-second average; Polymarket: Chainlink, a TWAP since August 2026). BTC can be "up" on one venue and "down" on the other, so a "locked" pair can lose on both legs — [DeFi Rate](https://defirate.com/prediction-markets/crypto/); [OddsShopper, settlement rules](https://www.oddsshopper.com/articles/prediction-markets/kalshi-vs-polymarket-settlement-rules); [cryptostruct.com](https://cryptostruct.com/guides/kalshi-vs-polymarket-data).
- Data point: a one-day replay (**2026-08-21, 97 windows per venue**) found the two venues **tracked each other within about 1¢ along the entire path** — [cryptostruct.com, "Kalshi vs Polymarket: the Same Market, Two Order Books"](https://cryptostruct.com/guides/kalshi-vs-polymarket-data). Claims of "≈5+ arbitrage opportunities per window" are unverified self-published content — [dev.to/benjamin_martin](https://dev.to/benjamin_martin_749c1d57f/polymarket-x-kalshi-arbitrage-27di); [YouTube](https://www.youtube.com/watch?v=pGYNN-sEUVw).

**S6. Passive market making with inventory skew / spread capture**
- Academic evidence on Kalshi (all markets, not 15-minute specific): **Takers lose about 32% on average, Makers about 10%**. The average contract return is **−20% before fees and −22% after**. In the sample, only takers paid fees, at **0.07·P·(1−P)** per contract, rounded up — [Bürgi, Deng & Whelan](https://www.karlwhelan.com/Papers/Kalshi.pdf); [SSRN 5502658](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=5502658); [VoxEU](https://cepr.org/voxeu/columns/economics-kalshi-prediction-market). So makers did better than takers, but the average maker still lost money.
- TurbineFi argues the paper's maker returns reflect a period when resting quotes were free, and that Kalshi later started charging makers — [TurbineFi](https://www.turbinefi.com/blog/5000-strategy-backtest-kalshi-btc-15m).
- Polymarket open source: warproxxx/poly-maker is a two-sided quoting bot with configurable parameters. Its README reportedly says "in today's market this bot is not profitable and will lose money. Use it as a reference implementation" (that wording is confirmed only by secondary sources) — [GitHub poly-maker](https://github.com/warproxxx/poly-maker); [polbots.com](https://polbots.com/bot/poly-maker). An independent user who ran it made **no net profit**: maker rewards and spread were offset by bugs and misconfiguration — [tezlee Substack](https://tezlee.substack.com/p/i-cloned-a-polymarket-market-making).
- The intra-window liquidity profile on KXBTC15M is reportedly wider early, deepest mid-window, and thin near the close as MMs pull risk. Typical resting depth is "a few hundred to a few thousand contracts within 2¢ of mid" — [kalshibacktest.com](https://kalshibacktest.com/resources/kalshi-btc-15-minute-markets) (unverified). Books are thinner overnight and on weekends — [sailgp.com](https://sailgp.com/prediction-markets/kalshi/15-minute-markets).

**S7. Liquidity-incentive / rebate farming**
- Kalshi Liquidity Incentive Program (LIP): each eligible market's book is snapshotted **once per second at a random moment**. Resting orders score by size and proximity to a Reference Price (the first level, walking down from the best bid, where cumulative size reaches **1/5 of Target Size**). Pools are split by share of scored liquidity. Each market publishes a schedule (target size, discount factor, reward). Orders **need not fill** to earn. The program runs until **January 1, 2027** unless amended — [Kalshi Help](https://help.kalshi.com/en/articles/13823851-liquidity-incentive-program); [CFTC filing July 15 2026](https://www.cftc.gov/filings/orgrules/rules07152610358.pdf); [Aug 2025 notice (older)](https://kalshi-public-docs.s3.amazonaws.com/regulatory/notices/Volume%20and%20Liquidity%20Incentive%20Program%20-%20August%202025.pdf). Secondary sites quote rewards of **$1–$1,000 or $10–$1,000 per market per day**, inconsistently. No source confirms whether KXBTC15M/KXETH15M are LIP-eligible.
- Kalshi's separate **Volume Incentive Program was set to end October 13 (2026)** amid CFTC scrutiny — [DeFi Rate](https://defirate.com/news/kalshi-volume-rewards-cftc-scrutinizes-prediction-markets/); [crypto.news](https://crypto.news/kalshi-to-end-volume-rewards-amid-5b-trade-scrutiny/).
- Polymarket 15-minute crypto: taker fees fund **daily USDC maker rebates proportional to filled maker liquidity** (since about January 2026) — [Blockonomi](https://blockonomi.com/polymarket-launches-maker-rebates-program-with-taker-fees-on-15-minute-crypto-markets/).

**S8. Order-flow / momentum / mean-reversion signal strategies**
- TurbineFi (vendor), 30 days ending **April 29 2026**: of **4,904** KXBTC15M strategies, **102 were profitable**. "Panic fade" took 93 of the top 100 (93 of 96 variants profitable, mean ROI **+4.90%**), and **all 432 mean-reversion variants lost**. Fees and slippage were charged, with fills modelled at next-candle open — [TurbineFi 4,904](https://www.turbinefi.com/blog/5000-strategy-backtest-kalshi-btc-15m). **Edge decay:** a **September 29 2026** update across **5,732 variants from 63 reports** had 2,154 positive and 2,496 negative. Panic fade **lost in the later test**, and momentum results depended heavily on entry and exit rules. The author concedes April was "one parameter sweep, one window, one execution model" — [TurbineFi 5,732](https://www.turbinefi.com/blog/5732-backtests-kalshi-bitcoin-15-minute-bots-2026). TurbineFi also claims KXBTC15M "mean-reverts more reliably under specific conditions" — [TurbineFi research](https://www.turbinefi.com/research/kalshi-btc-15-minute-markets-mean-revert-more-reliably-under-specif-8e46853f0bad).
- A fade-extremes backtest (fade YES <0.35 / NO >0.65) lost across variants, with a top-variant win rate around 50% — per search summary of [Turbine / bitsbytesandbets](https://bitsbytesandbets.substack.com/p/problems-with-probability).
- Polymarket: a self-reported **77% win rate over 11,717 trades** on 5m/15m/1h, where the author notes break-even is about **75%** at typical entry prices — [dev.to/lkto1m](https://dev.to/lkto1m/my-polymarket-trading-bot-strategy-across-11717-trades-5m15m1h-framework-explained-3gbl). A vendor bot shows **3,455 trades, 54.6% wins, +$3,501 on $2,000** (self-reported) — [polbots.com](https://polbots.com/bots/btc-15-minute). One research team's directional 15-minute strategy lost about **38%** — [LayerX](https://layerx.xyz/blog/polymarketbots).
- Momentum ignition / fading it: no source specific to these markets. The Dai-Jia-Yu result (spot flow spikes at settlement, then reversals) is the closest documented "ignition then reversal" pattern, and it is on Polymarket 5-minute markets.

**S9. Queue-position strategies**
- No source found that describes queue-priority strategies on Kalshi or Polymarket 15-minute books. Kalshi has filed with the CFTC for a rule that would **delay certain orders**. Coverage of a similar Crypto.com rule says market makers would be exempt, and reporting says the Kalshi filing does not specify who is affected — [mexc.com news](https://www.mexc.com/news/243238) (secondary, not checked against the filing).

### Inferences
- The strategies with real documented edge on these exact products (latency arbitrage, settlement-second manipulation) are either already taxed (Polymarket dynamic fees, January 2026; TWAP settlement, August 2026) or need sub-second spot feeds and fast execution. They are poor fits for a phone engine.
- Kalshi's 60-second averaging works as a built-in anti-manipulation and anti-latency measure. Once about 30–50 of the 60 prints are known, the remaining uncertainty shrinks roughly with the square root of the remaining prints. A paper engine with the running average (which this app has) can compute a fair value that should beat a stale book in the final minute. But no public source documents this as a profitable live strategy. Professionals with the same CF feed likely reprice the book within that second.
- Given the maker/taker gap in the Whelan paper and fee-funded maker rebates on Polymarket, passive quoting (posting limit orders, ideally skewed by a model fair value) is the structurally favoured side for a small trader. Adverse selection by faster spot-informed takers is the main risk.

### Gaps
- No public, verifiable live P&L for any market-making strategy on KXBTC15M/KXETH15M/KXSOL15M.
- No source tests the "known partial final-minute average" strategy on Kalshi.
- Exact Polymarket dynamic-fee formula and rebate math were not retrieved (pages unreachable).
- Whether Kalshi's 15-minute crypto series currently charge makers (the conflict above) and whether they are LIP-eligible.

## What evidence exists on returns, win rates, fill rates, competition and edge decay?

### Takeaway
Rigorous evidence is scarce and mostly 2025–2026. Academic work covers Kalshi-wide maker/taker returns, Polymarket order-book microstructure and settlement manipulation. Nearly all 15-minute-specific "evidence" is vendor backtests or self-reported bot logs, and these show high win rates with thin or negative net P&L. Edges decay within months: the platform patched latency arbitrage, and backtest winners did not persist from April to September 2026.

### Cited Findings
- **Academic (verified-type evidence):**
  - Kalshi maker vs taker returns (−10% vs −32%), favourite-longshot bias, −20%/−22% average returns — [Bürgi, Deng & Whelan 2025/2026](https://www.ucd.ie/economics/t4media/WP2025_19.pdf).
  - Polymarket order-book microstructure: **30 billion events over 52 days, 600-market panel**. Findings include a longshot spread premium, depth that looks like a geometric grid, and maker liquidity that is "fairly decentralized but with a thin tail held by a few wallets" — [Dubach, arXiv 2604.24366 (April 2026)](https://arxiv.org/abs/2604.24366).
  - Polymarket-v1 database: tick-rule and BVC trade classification are close to random against on-chain aggressor labels, a pattern attributed to trade-direction autocorrelation and **concentrated market-making** — [Qin & Yang, arXiv 2606.04217 (June 2026)](https://arxiv.org/pdf/2606.04217). Implication: inferring order-flow imbalance from prints alone is unreliable. Use orderbook_delta and the trade feed's taker side where available.
  - Non-retail trading study of about **13.4M filled orders in one week** on Polymarket. MM withdrawal behaviour cannot be validated without off-chain book data — [arXiv 2605.11640 (May 2026)](https://arxiv.org/html/2605.11640v1).
  - **OpenMarket**, a synchronized Polymarket–Binance high-frequency dataset aimed at short-term crypto market research — [arXiv 2607.26245 (July 2026)](https://arxiv.org/pdf/2607.26245).
  - Settlement manipulation on Polymarket 5-minute markets (about $8.2M, 821 wallets per press). Largely absent at 15 minutes — [Dai, Jia & Yu, arXiv 2606.31675 (June 30 2026)](https://arxiv.org/pdf/2606.31675).
  - The search tool flagged these arXiv IDs as very recent, and none could be opened in this session. Verify before citing figures.
- **Vendor / self-reported (unverified):** TurbineFi backtests (102 of 4,904 profitable in April 2026; 2,154 of 5,732 positive by September 2026) — [TurbineFi](https://www.turbinefi.com/blog/5732-backtests-kalshi-bitcoin-15-minute-bots-2026). Botforkalshi's late-favourite gross +0.67¢ vs 1.55¢ fee — [botforkalshi](https://www.botforkalshi.com/blog/kalshi-bitcoin-bot). Dev.to logs: 77%/11,717 trades near break-even, and 92% wins for about $2 net — [lkto1m](https://dev.to/lkto1m/my-polymarket-trading-bot-strategy-across-11717-trades-5m15m1h-framework-explained-3gbl); [manja316](https://dev.to/manja316/176-trades-on-polymarket-what-my-bot-actually-made-its-not-what-you-think-3iib).
- **Verification problems:** one open-source tester's logs omit market IDs, so the record is self-attested only. Another developer found Polymarket's historical endpoint returned **empty datasets for 15-minute markets**, which undermines backtests — per search summary of [Indie Hackers / paragraph.com, "Building a Stable 15-Minute Trading Bot"](https://www.indiehackers.com/post/building-a-stable-15-minute-trading-bot-for-polymarket-crypto-up-down-markets-563a14e882).
- **Promotional claims to treat as noise:** "Claude AI bots making hundreds of thousands" — [Medium](https://medium.com/@weare1010/claude-ai-trading-bots-are-making-hundreds-of-thousands-on-polymarket-2840efb9f2cd). "86% return" — [HTX](https://www.htx.com/news/86-return-how-to-use-a-bot-to-earn-passively-on-polymarket-1lvJrZQN/). "$68 to $1.5M" reverse-engineered strategy — vendor claim via [polymtradebot.com](https://polymtradebot.com/). A Kairos page notes that most individually built bots earn far less and face well-capitalised competition — [kairos.trade](https://kairos.trade/compare/polymarket-bot).
- **Market size context:** citing FT data, short-term (5–15 minute) contracts were **more than half of crypto betting on Kalshi and Polymarket as of March 2026** — [AOL](https://aol.com/articles/15-minute-markets-cryptocurrency-prices-102000000.html).
- **Fill rates:** no source reports maker fill rates on KXBTC15M. TurbineFi's backtests assume next-candle-open taker fills — [TurbineFi](https://www.turbinefi.com/blog/5000-strategy-backtest-kalshi-btc-15m).

### Inferences
- High win rate is the standard trap. Strategies buying at 75–95¢ need win rates of 75–95% just to break even, so "77%" or "92%" headlines are consistent with zero or negative expectancy. The writer should rank strategies on expected value per contract after fees, not on hit rate.
- Edge-decay evidence: Polymarket latency arbitrage went from "$313 to $414k in a month" (late 2025) to fee-neutralised (January 2026) to TWAP settlement (August 2026), so edges lasted months, not years. TurbineFi's panic-fade winner did not survive five months.

### Gaps
- No Dune or on-chain study isolating 15-minute crypto wallets' P&L distribution was found. One search explicitly found none.
- No independent study of maker fill rates or adverse selection on Kalshi 15-minute books.

## What latency, capital and data does each strategy need? Which are realistic from a phone or paper engine?

### Takeaway
Latency arbitrage and settlement-second plays need direct exchange feeds and low-millisecond execution. Vendors cite under 5 ms round-trip and AWS eu-west-2 hosting for Polymarket. A phone engine cannot compete there. Strategies realistic for a phone engine are slower: model-skewed passive quoting, final-minute fair-value checks using the running CF average (paper only, to measure whether the book lags), LIP-style resting liquidity, and filtered late-favourite entries. All are sensitive to maker fees and adverse selection.

### Cited Findings
- Vendor latency guidance: Polymarket MMs and arbitrage bots target **under 5 ms round-trip**, and the CLOB is reportedly hosted on **AWS eu-west-2 (London)** — [newyorkcityservers.com](https://newyorkcityservers.com/blog/polymarket-server-location-latency-guide) (commercial VPS vendor, unverified).
- Polygon's public mempool lets MEV bots sandwich or back-run Polymarket orders. The microstructure paper did not analyse the mempool, so on-chain fills may include bot-generated trades — [Dubach, arXiv 2604.24366](https://arxiv.org/abs/2604.24366).
- Data dependencies by strategy, from source descriptions:
  - Latency arbitrage needs Binance/Coinbase tick feeds plus a venue book — [Yahoo Finance](https://finance.yahoo.com/news/arbitrage-bots-dominate-polymarket-millions-100000888.html).
  - Settlement-average strategies need the venue's exact oracle: the CF RTI for Kalshi, Chainlink TWAP (RTDS) for Polymarket — [wooyang repo](https://github.com/wooyang/polymarket-arbitrage-bot).
  - Late-favourite filters need distance-to-strike and time-remaining — [OddsShopper](https://www.oddsshopper.com/articles/prediction-markets/are-kalshi-15-minute-bitcoin-markets-rigged).
  - LIP needs per-market schedule and resting size near the reference price — [Kalshi Help](https://help.kalshi.com/en/articles/13823851-liquidity-incentive-program).
- Capital: the reported latency-arbitrage bot bet **$4,000–$5,000 per trade** — [Yahoo Finance](https://finance.yahoo.com/news/arbitrage-bots-dominate-polymarket-millions-100000888.html). A Polymarket bonding trader reportedly put **about $7M at risk for 0.2%** — [YouTube](https://www.youtube.com/watch?v=pGYNN-sEUVw). Retail bots in the dev.to logs ran on hundreds to a few thousand dollars — [polbots](https://polbots.com/bots/btc-15-minute).
- Capacity: KXBTC15M depth is reportedly a few hundred to a few thousand contracts within 2¢ of mid and thins near the close — [kalshibacktest.com](https://kalshibacktest.com/resources/kalshi-btc-15-minute-markets). Polymarket 5m/15m liquidity "can thin out rapidly" — [polymtradebot review](https://polymtradebot.com/blog/review-of-the-polymarket-trading-bot-script).

### Inferences
Feasibility ratings below are this researcher's synthesis, not sourced claims.

| Strategy | Latency need | Phone/paper feasible? | Main risk |
|---|---|---|---|
| S1 Latency arbitrage | ms; colocated; direct spot feeds | No (paper can only measure lag) | Fees since Jan 2026; pros faster |
| S2 Final-minute settlement-average fair value | Sub-second; CF 1–5 Hz suffices to *estimate* | Paper yes; live edge doubtful | Pros reprice same second; thin book at close |
| S3 Late favourite with distance filter | Seconds | Yes | Near-zero EV after taker fees; tail flips in close windows |
| S4 Both-sides under $1 | Sub-second (fleeting) | Mostly no | Partial fills leave naked leg |
| S5 Cross-venue | Seconds, but two accounts | No (different oracles/strikes) | Basis risk: both legs lose |
| S6 Skewed passive MM | Seconds to cancel on spot moves | Yes in paper; live is a cancel-latency risk | Adverse selection from spot-informed takers; maker fee status |
| S7 LIP / rebate farming | Low | Yes | Eligibility unknown; fills trigger maker fee |
| S8 Signal (fade/momentum) | Seconds to minutes | Yes | Backtest overfitting; decay |

- A paper engine should model maker fills conservatively: fill only when the trade feed prints through the order's price, with queue position behind displayed size. Otherwise passive strategies will look far better on paper than live.

### Gaps
- No measured Kalshi API round-trip latencies from mobile versus colocated clients were found. The other researcher covers API limits.

## Are the 15-minute crypto markets dominated by a few market makers, and what does that imply for a small trader?

### Takeaway
Institutional MMs are confirmed on both venues: Susquehanna (SIG) on Kalshi since April 2024, and Jump Trading via equity-for-liquidity deals. But no source quantifies their share of the KXBTC15M/KXETH15M/KXSOL15M queues. Polymarket academic data shows maker liquidity is "fairly decentralized" with a thin dominant tail and concentrated market-making effects. A small trader joining the queue should expect to sit behind professional quotes and be filled mainly when informed flow moves through them.

### Cited Findings
- SIG became Kalshi's **first major institutional market maker** in **April 2024** (older material), with a commitment covering indices, economics, **crypto**, FX and ad-hoc events. The 15-minute series did not exist yet and is not named — [Bloomberg, April 3 2024](https://www.bloomberg.com/news/articles/2024-04-03/susquehanna-starts-trading-desk-for-event-contracts-on-kalshi); [Kalshi News, "Liquid Prediction Markets are (finally) here"](https://news.kalshi.com/p/liquid-prediction-markets-are-finally-here); later described as Kalshi's "dedicated institutional market maker" — [earningsandmore Substack](https://earningsandmore.substack.com/p/welcome-to-the-funhouse).
- **Jump Trading** reportedly takes small equity stakes in Kalshi and Polymarket **in exchange for providing liquidity** — [Yahoo Finance / Bloomberg](https://finance.yahoo.com/news/jump-trading-small-stakes-polymarket-121525476.html).
- Polymarket maker liquidity: "fairly decentralized but [with] a thin tail held by a few wallets" — [arXiv 2604.24366](https://arxiv.org/abs/2604.24366). "Concentrated market-making" is cited as a driver of trade-direction patterns — [arXiv 2606.04217](https://arxiv.org/pdf/2606.04217).
- MMs pull risk near the close of KXBTC15M windows, so depth thins — [kalshibacktest.com](https://kalshibacktest.com/resources/kalshi-btc-15-minute-markets).
- A Kalshi CFTC filing for order delays may exempt MMs (by analogy to Crypto.com). This is unverified — [mexc.com](https://www.mexc.com/news/243238).
- No source quantifies the share of 15-minute crypto queue or volume held by any firm — confirmed absent across two searches.

### Inferences
- With professionals quoting tight spreads mid-window and pulling quotes at the close, a small passive order at the touch tends to fill when the price is moving against it (adverse selection). Better spots are those MMs leave: early in the window when the book is wide, around the final-minute pullback, and overnight or on weekends when the book is thin. These need a fair-value model from the CF index to avoid being picked off.
- The Whelan maker/taker gap suggests being a maker is still better than being a taker, even against professionals. It does not show that small makers profit.

### Gaps
- No Kalshi data on designated MM programs specific to crypto 15-minute series. No firm-level share data.

## Which open-source Kalshi or Polymarket bots exist for these markets, what do they do, and what do their authors report?

### Takeaway
There are many GitHub repositories (2025–2026), mostly directional signal bots, ML classifiers, late-window "done-deal" entries or paper traders, plus a few Polymarket arbitrage and market-making frameworks. **None that surfaced publishes verifiable live profitability.** The best-known MM bot's author reportedly says it loses money.

### Cited Findings
- **Kalshi KXBTC15M / KXETH15M** (descriptions from GitHub listings via search. Repo contents could not be opened: the API refused access and one URL returned 404.)
  - [SiddhaBasu/kalshi-bot](https://github.com/SiddhaBasu/kalshi-bot): volatility model plus XGBoost classifier, walk-forward validated and probability-calibrated, with a whale-trade scanner, a weather strategy and a React dashboard. Paper and live modes.
  - [reedjacobp/kalshi-trading-bot](https://github.com/reedjacobp/kalshi-trading-bot): algorithmic bot for 15-minute crypto with an Express/React dashboard and a Python bot.
  - [DeweyMarco/simple-kalshi-bot](https://github.com/DeweyMarco/simple-kalshi-bot): **paper-trades three strategies** on KXBTC15M and logs to CSV. This is the closest analogue to this app.
  - [hamad-khawaja/kalshi-trading-bot](https://github.com/hamad-khawaja/kalshi-trading-bot): KXBTC15M + KXETH15M, a 16-signal heuristic model, risk management, paper and live modes.
  - [brandononchain/kalshibot](https://github.com/brandononchain/kalshibot): GBM fair value, cross-exchange arbitrage detection, Kelly sizing (about 235 days old per search, so roughly early 2026).
  - [fabricio-sousa/mystic-bot](https://github.com/fabricio-sousa/mystic-bot): late-window "Done-Deal" entry with momentum filters (URL 404 when fetched).
  - [momotsanya/KalshiBot](https://github.com/momotsanya/KalshiBot): momentum or reversal bet on each new window with **martingale sizing** and a loss-streak cap ("under construction"). Martingale is a red flag.
  - [daffieeee-arch/Kalshi-Bot](https://github.com/daffieeee-arch/Kalshi-Bot): demo-only API client.
  - [r3curs10n/kalshi-bot](https://github.com/r3curs10n/kalshi-bot): records live Kalshi data and trains a **PPO reinforcement-learning** policy.
- **Polymarket 15-minute / 5-minute**
  - [warproxxx/poly-maker](https://github.com/warproxxx/poly-maker): two-sided MM. The README reportedly says it is not profitable in today's market (secondary confirmation only). An independent run made no net profit — [tezlee](https://tezlee.substack.com/p/i-cloned-a-polymarket-market-making).
  - [wooyang/polymarket-arbitrage-bot](https://github.com/wooyang/polymarket-arbitrage-bot): TypeScript multi-strategy arbitrage detection, CLOB execution, Chainlink TWAP RTDS for up/down markets. It explicitly disclaims "risk-free arbitrage."
  - [aulekator/Polymarket-BTC-15-Minute-Trading-Bot](https://github.com/aulekator/Polymarket-BTC-15-Minute-Trading-Bot): "7-phase architecture," multi-signal, "self-learning." No verified results.
  - [PredictiveFlow-Labs/polymarket-btc-5min-15min-up-down-arbitrage-bot](https://github.com/PredictiveFlow-Labs/polymarket-btc-5min-15min-up-down-arbitrage-bot): keyword-stuffed description, likely promotional or spam.
  - Roundups: 127 bots ranked by "ROI" (self-reported) — [polbots.com](https://polbots.com/); "Top 20 Polymarket Bots on GitHub" — [X/zostaff](https://x.com/zostaff/status/2050593192135754176); [polycatalog.io](https://www.polycatalog.io/polymarket-trading-bots).
- Practical author lessons: "6 things that broke our Polymarket trading bot before it made a dollar" (execution and ops failures) — [dev.to/ekocam](https://dev.to/ekocam/6-things-that-broke-our-polymarket-trading-bot-before-it-made-a-dollar-4efi). LayerX's research journey: a directional 15-minute strategy lost about 38%, with arbitrage still in development — [LayerX](https://layerx.xyz/blog/polymarketbots).

### Inferences
- The open-source ecosystem is heavy on signal and ML bots and light on real queue-aware market makers for Kalshi 15-minute markets. A paper engine that models queue position and adverse selection would be more rigorous than most public repos.
- Many "Polymarket 15-minute bot" sites (polymtradebot.com, polbots.com and similar) sell scripts. Their ROI tables should not count as evidence.

### Gaps
- Star counts, last-commit dates, licenses and any live P&L inside READMEs or issues could not be retrieved (GitHub access blocked for these repos in this session).
- No Kalshi-specific open-source market maker for 15-minute crypto was found.
