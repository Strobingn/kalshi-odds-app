# Model improvement for the short-horizon maker-scalp model (features, labels, validation, calibration, on-device speed)

Scope note for the report writer: the web-fetch tool was blocked in this session (DNS/proxy refusal for arxiv.org, ucl.ac.uk, alphaxiv.org), so every external finding below comes from search-engine result summaries of abstracts, READMEs and paper pages, not from full-text reading. Numbers that came only from secondary summaries are flagged. Facts about the current model come from the repository itself.

Current model, as shipped in the repo (useful context for every section):
- 146 LightGBM trees, mean depth about 10.4 and max depth 17, about 30 internal nodes per tree (consistent with `num_leaves`=31 leaf-wise growth). The JSON file is about 300 KB. The Kotlin evaluator walks each tree with parallel `IntArray`/`DoubleArray` arrays. — [repo: app/src/main/assets/scalper_model.json](app/src/main/assets/scalper_model.json); [repo: ScalperModel.kt](app/src/main/java/com/dirk/kalshiodds/signal/scalper/ScalperModel.kt)
- Label: "cents per order posted: rest at the best bid 20 s, offer +1c, stop 4c, 120 s". Trained 2026-09-18..09-28, threshold chosen on 09-29..10-02, tested on 10-03..10-09. Test result: +0.353¢ per order for the orders it picks at the front of the queue, against +0.181¢ for taking every order. The KDoc gives a 99% interval of [+0.24, +0.47] and says 7 of 7 days were positive. The threshold θ is −0.034¢, and a separate piecewise-linear `queue_penalty` subtracts cents by contracts ahead. — [repo: scalper_model.json](app/src/main/assets/scalper_model.json); [repo: ScalperModel.kt](app/src/main/java/com/dirk/kalshiodds/signal/scalper/ScalperModel.kt)
- So the label is already a fixed-rule triple-barrier outcome (profit-take +1¢, stop −4¢, time-out 120 s) that is conditional on a fill at the front of the queue. Queue effects are handled after the model as a lookup penalty, not inside the label.

## 1. Labels for maker-order models (fill-conditional markout, two-stage P(fill)×E[pnl|fill], triple barrier, net-of-queue, cost-sensitive)

### Takeaway
Treat a maker order's result as two quantities that depend on each other: whether the order fills, and what the fill is worth (the markout). Fills are adversely selected: fills cluster just before the price moves against the maker. So a front-of-queue, fill-assumed label overstates the edge unless fill probability is modelled as a function of queue position and state. The best-supported design is a two-stage or hurdle model: P(fill | queue, state) from survival or logistic models, times E[pnl | fill, state]. Use the same triple-barrier exit rules the strategy actually trades, and validate the product on realised P&L. No paper was found that benchmarks these label designs head to head on the same data.

### Cited Findings
- **Adverse selection of fills ("negative drift").** DeLise (2024) shows, with a discrete market model, a proof, and an empirical simulation on 10-year US Treasury futures, that limit-order fills are high-cost and non-random. When the mid moves one tick down, the resting best bid becomes the ask and gets filled. Models that assume low-cost random fills therefore produce synthetic backtests that "appear to be profitable, but in reality fail to produce a profit". The paper says a 100% fill-at-touch assumption is far too generous, and that an exponential fill-rate model is better but "still inadequate". — [DeLise 2024, arXiv 2407.16527](https://arxiv.org/html/2407.16527v1)
- **Live crypto maker study.** A 2025 Oxford/QMUL study placed live orders on Binance perpetuals. It reports that a simple linear model with queue-size and imbalance inputs reached R² = 0.946 for fill probability, so complex ML may not be needed for that task. Randomly placed orders in balanced queues filled about 50% of the time. Maker orders "consistently" drift against the maker after fills, and the size of that drift depends on queue position and market conditions. Numbers are from a secondary summary page. — [alphaxiv summary of arXiv 2502.18625](https://alphaxiv.org/overview/2502.18625v1); [arXiv 2502.18625](https://web3.arxiv.org/pdf/2502.18625)
- **Markout definition.** A markout is the signed mid move over Δt after a fill: (later mid − fill price) for a limit buy, and (fill price − later mid) for a limit sell. One market-making RL paper uses Δt = 30 s and notes that at-touch fills with a one-tick spread have expected markouts of about +0.5 tick in stable prices. — [arXiv 2608.18195](https://arxiv.org/pdf/2608.18195)
- **Fill-time models.**
  - Maglaras, Moallemi & Wang (Quantitative Finance 2022) use an RNN to estimate the whole time-to-fill distribution of a limit order from market state, and report that it beats several benchmarks. — [IDEAS](https://ideas.repec.org/a/taf/quantf/v22y2022i11p1989-2003.html); [Columbia](https://business.columbia.edu/faculty/research/deep-learning-approach-estimating-fill-probabilities-limit-order-book)
  - Arroyo, Cartea, Moreno-Pino & Zohren (Quantitative Finance 2024) use a convolutional-Transformer encoder with a monotonic decoder for survival analysis of fill times. — [IDEAS](https://ideas.repec.org/a/taf/quantf/v24y2024i1p35-57.html); [Oxford ORA](https://ora.ox.ac.uk/objects/uuid:03367c56-81e6-4fea-b51e-18885e3f6680)
  - KANFormer (Dec 2025) is another survival model for fill probability. — [arXiv 2512.05734](https://arxiv.org/abs/2512.05734v1)
- **Queue position has value, and that value depends on adverse selection.** Moallemi & Yuan value an order by its queue position. They trade off spread earned against adverse-selection cost, and that cost rises with queue position. A related 2019 Glosten-Milgrom-style paper reports queue-position values on the order of the bid-ask spread. — [Moallemi & Yuan, queue value](https://drupalgsb-test.paas.cc.columbia.edu/sites/default/files-efs/pubfiles/25461/queue-value-2016.pdf); [MIT ORC abstract](https://web.mit.edu/orc/www/archive/seminars/2014s/abs/Moallemi_abs.html); [arXiv 1902.10743](https://arxiv.org/pdf/1902.10743)
- **Simulators.** Lalor & Swishchuk argue that simulators which generate prices and market orders independently overstate short-horizon strategy performance. Realistic fill probabilities plus tracking of adverse fills correct this. — [NEP-MST listing](https://nep.repec.org/nep-mst/2024-10-28)
- **Triple barrier / meta-labelling.** The triple barrier labels each event by whichever barrier (profit, stop, time) is hit first, and pairs with meta-labelling, where a second model decides which primary signals to act on. — [Trading Interview course](https://www.tradinginterview.com/courses/machine-learning/lessons/labeling-the-triple-barrier-method-and-meta-labeling/)
  - A typical "5–20% precision uplift" from meta-labelling is cited only as an unsourced rule of thumb. — [Jesse docs](https://docs.jesse.trade/docs/research/ml/meta-labeling)
  - One controlled practitioner test used 60.5 M EURUSD ticks (2022–23), triple-barrier labels, a meta-model and purged CV, and found no reliable edge for the three primary rules tested. — [MQL5 article](https://www.mql5.com/en/articles/23310)
- **Profit or magnitude-weighted objectives.**
  - A 2026 paper weights each training sample by |log return| so that economically large moves dominate the loss. It is a neural model, not boosting. — [arXiv 2601.13435](https://arxiv.org/pdf/2601.13435)
  - Nevasalmi & Nyberg use a customised gradient boosting that maximises utility directly instead of the forecast-then-allocate approach. — [Univ. Turku record](https://research.utu.fi/converis/portal/detail/Publication/52181838)
  - Sharpe-style objectives are scale-invariant in predicted position size, so they give a weak gradient for sizing. — [arXiv 2509.04541](https://arxiv.org/pdf/2509.04541)

### Inferences
- The current label is equivalent to E[pnl | filled at front of queue]. The test shows +0.35¢ at the front and an edge that disappears otherwise. That is exactly the pattern the negative-drift literature predicts: back-of-queue fills happen disproportionately when the price is about to cross through the order.
- The single best label change is probably to replace the post-hoc `queue_penalty` with a two-stage model:
  - (a) A fill model P(fill within 20 s | queue ahead, depth at touch, trade-arrival rate, cancel rate, time to close, BRTI drift). The Binance study suggests even logistic or linear models do well here. Survival or hazard framing (Maglaras et al.; Arroyo et al.) handles the 20 s time-out cleanly.
  - (b) A markout or exit model E[pnl | fill at queue position q, state]. Train it only on rows that would actually have filled under a queue simulation, and include q (or contracts ahead) as a feature. Its interaction with adverse selection is the point.
  - Decision rule: E[order] = P(fill)·E[pnl | fill] + (1 − P(fill))·0 − cancel cost. A hurdle/zero-inflated structure keeps each stage simple and well-calibrated.
- The fill-conditional model needs labels that reflect fills when the order was not at the front. That needs a queue simulator: trade-through or probabilistic queue models (see Section 7). It also needs live fill logs from the phone, which are the only ground truth for Kalshi queue dynamics.
- Keep the fixed-rule triple barrier (it matches the traded policy), but also store auxiliary targets: time to fill, max adverse excursion, 30/60/120 s markout. These are cheap multi-task targets and diagnostics, and a separate model on "did the stop hit" may be more stable than the net-cents regression.
- Do not use profit-weighting or a custom utility objective until the validation in Section 3 is in place. With ~11 training days, magnitude weighting increases the influence of a few large-move days, which raises variance.

### Gaps
- No study found compares fill-conditional markout, two-stage hurdle, triple-barrier and profit-weighted labels on the same data.
- No peer-reviewed meta-labelling replication with concrete precision/AUC numbers was found.
- No published fill or queue study for prediction-market or binary-option order books (thin, 1¢ ticks, bounded prices) was found. All fill-model evidence is from equities, Treasuries or crypto perpetuals.

## 2. Features for LOB prediction, deep vs hand-crafted, and generalisation (including settlement-index features)

### Takeaway
The most consistently useful short-horizon features are order-flow imbalance (OFI) at the best levels (and multi-level OFI), queue/volume imbalance at the touch (and the microprice built from it), and depth and spread. Benchmarks show deep LOB models win mainly on the FI-2010 benchmark and lose much of that edge on new data. On crypto order books, well-engineered inputs with logistic regression or XGBoost match deep models. The current model has none of the order-book features, which is the biggest feature gap. For a binary contract settled on an external index, BRTI-derived features (distance of the index from the strike, in volatility units, given the time left and the partially formed 60 s settlement average) are the analogue of "fundamental value". They are probably more important than anything in Kalshi's own prints.

### Cited Findings
- **OFI (Cont, Kukanov & Stoikov, J. Financial Econometrics 2014).** On 50 US stocks (NYSE TAQ), short-interval price changes are driven mainly by order-flow imbalance at the best bid/ask. The relation is linear with slope inversely proportional to depth, robust to seasonality, and stable across time scales and stocks. Trade-volume relations are noisier. — [arXiv 1011.6402](https://arxiv.org/abs/1011.6402); [IDEAS](https://ideas.repec.org/a/oup/jfinec/v12y2013i1p47-88.html)
  - The reported R² is "around 70%" per a secondary blog (≈65% average is commonly quoted). The figure was not verified against the paper. — [Towards Data Science](https://towardsdatascience.com/?p=130535)
- **Multi-level OFI** (Xu, Gould & Howison) extends OFI to deeper levels on Nasdaq stocks. — [arXiv 1907.06230](https://arxiv.org/pdf/1907.06230)
- **Queue imbalance (Gould & Bonart 2015).** Logistic regression of next mid-move direction on best-level queue imbalance was strongly significant in all 10 Nasdaq stocks tested. It gave "considerable" improvement over a null model for large-tick stocks and "moderate" improvement for small-tick stocks. Local logistic regression was slightly better but slower. — [arXiv 1512.03492](https://arxiv.org/pdf/1512.03492)
- **Microprice (Stoikov, Quantitative Finance 2018).** The microprice (mid plus an imbalance- and spread-dependent adjustment) is empirically a better short-term predictor than the mid or the size-weighted mid. — [IDEAS](https://ideas.repec.org/a/taf/quantf/v18y2018i12p1959-1966.html)
  - Contradicted by a Frankfurt master's thesis on DAX 40 stocks, which found that for most horizons the microprice was not better than the mid or the weighted mid. — [Goethe Univ. thesis listing](https://www.efinance.wiwi.uni-frankfurt.de/en/teaching/master-theses/selection-of-completed-theses/the-time-adjusted-micro-price-and-the-relevance-of-deeper-limit-order-book-levels.html)
- **Deep OFI (Kolm, Turiel & Westray, Mathematical Finance 2023; SSRN 2021).** On 115 Nasdaq stocks, simpler off-the-shelf networks trained on stationary inputs derived from the order book (order flow) reached state-of-the-art accuracy. Models using order flow beat most models trained on raw order books. The effective forecast horizon is about two average price changes, and accuracy declines beyond that. — [SSRN 3900141](https://papers.ssrn.com/abstract=3900141); [IDEAS](https://ideas.repec.org/a/bla/mathfi/v33y2023i4p1044-1081.html); [Quantpedia](https://vvv.quantpedia.com/tag/order-flow/)
- **DeepLOB (Zhang, Zohren & Roberts, IEEE TSP 2019).** On one year of LSE data with a 3-month test, accuracy was about 70.2% / 63.9% / 61.5% at k = 20/50/100 events on the training stocks. On five unseen stocks it was 68.6% / 63.4% / 61.5%. The authors argue that FI-2010 alone is too short and illiquid to show robustness. — [arXiv 1808.03668](https://arxiv.org/pdf/1808.03668)
- **LOBCAST benchmark (Prata et al., Artificial Intelligence Review 2024).** 15 deep LOB models were re-run. Many could not reproduce their claimed performance. The best are robust on FI-2010, but all show a significant performance drop on unseen LOBSTER data (LOB-2021/2022). Most overfit FI-2010. — [arXiv 2308.01915](https://arxiv.org/html/2308.01915); [Springer](https://link.springer.com/article/10.1007/s10462-024-10715-4)
- **TLOB (2025)** reports that stock-price predictability declined over time, with −6.68 absolute F1 points. — [arXiv 2502.15757](https://arxiv.org/html/2502.15757v2)
- **Briola, Bartolucci & Aste (Quantitative Finance 2025, "Deep LOB forecasting: a microstructural guide").** Stocks' microstructural characteristics determine whether deep learning works. Strong forecasting performance "does not necessarily correspond to actionable trading signals". Standard ML metrics fail to assess LOB forecasts, so the authors propose evaluating the probability of correctly forecasting complete transactions. — [IDEAS](https://ideas.repec.org/a/taf/quantf/v25y2025i7p1101-1131.html); [arXiv 2403.09267](https://arxiv.org/html/2403.09267v2)
- **Universality (Sirignano & Cont, Quantitative Finance 2019).** Deep models on billions of US-equity quotes and trades were stable out of sample over time. A single pooled model beat asset-specific models and worked on stocks outside training. Volatility, price and spread normalisation, and splitting by sector or tick size, did not help. Longer histories helped (path dependence). — [arXiv 1803.06917](https://arxiv.org/abs/1803.06917v1); [IDEAS](https://ideas.repec.org/a/taf/quantf/v19y2019i9p1449-1459.html)
- **Crypto (BTC/USDT, Bybit, 2025).** With preprocessing (Kalman / Savitzky–Golay filtering) and tuning, logistic regression and XGBoost match or exceed DeepLOB and Conv1D+LSTM on 100 ms to multi-second snapshots, with faster inference. The F1 numbers were not retrieved. — [Wang 2025, arXiv 2506.05764](https://arxiv.org/pdf/2506.05764)
- **Hand-crafted vs autoencoder features.** Ntakaris et al. compared hand-crafted features against LSTM-autoencoder features on liquid and illiquid stocks. — [Univ. Edinburgh record](https://www.research.ed.ac.uk/en/publications/feature-engineering-for-mid-price-prediction-with-deep-learning/)
- **Settlement-index / spot lead-lag in crypto up/down binaries.** A 2026 paper on Polymarket's BTC 5-minute up/down contract notes that it settles on a Chainlink oracle built from spot exchanges, and that the Binance mid sits about 2.5 bp from the oracle. Short windows leave spot price discovery little time to work. Manipulation signatures are sharp at 5 minutes and much weaker for the 15-minute contract. — [arXiv 2606.31675](https://arxiv.org/pdf/2606.31675)
  - BTC price discovery is often led by futures, with mixed results in high-volatility periods. — [arXiv 2506.08718](https://arxiv.org/pdf/2506.08718)
  - A 2026 write-up reports a ~16–19 ms median lead-lag and a "null result" for a 43-feature logistic model on Polymarket 15-min contracts. Not verified. — [alphaxiv 2607.26245](https://www.alphaxiv.org/abs/2607.26245)

### Inferences
Where these transfer to Kalshi:
- Large-tick findings transfer best. A 1¢-tick binary priced 1–99¢ behaves like a very large-tick instrument, often with a one-tick spread. That is the regime where Gould & Bonart found queue imbalance most predictive, so queue imbalance should be the first order-book feature added.
- Equity results (Cont, Kolm, Sirignano, DeepLOB, LOBCAST) come from deep, continuous books. Kalshi books are thin, and the price is bounded and converges to 0 or 100 at expiry, so the magnitude of the gains will not transfer.

Recommended feature additions, in priority order:
1. **BRTI/fair-value features.** Use the index level, or a proxy from Coinbase/Kraken/Bitstamp etc. if BRTI itself is not available live.
   - z = (index − strike) / (σ_short · √(time left)), and a model fair probability Φ(z) adjusted for the partially formed 60 s average.
   - Kalshi mid minus fair value: the mispricing, likely the dominant signal.
   - Index returns over 1/5/15/60 s, short realised volatility of the index at several horizons, and index-to-Kalshi lag features.
   - Within the last 60 s, use the fraction of the averaging window already fixed. It mechanically shrinks remaining variance.
2. **Best-level book features.** Queue imbalance (bid size − ask size)/(sum), microprice minus mid, depth at best and top-3 on each side, spread in ticks, OFI over 1/5/10/30 s (Cont-style, from book deltas, not trade prints), and contracts ahead of our order.
3. **Order-flow dynamics.** Cancellation rate at the touch (adds and cancels per second, ahead of us vs total), trade-arrival intensity (an EWMA of trade counts, a Hawkes-like proxy), and time since the last price change.
4. **Regime/time.** Time to close is already present. Add time-of-day/session and a BTC volatility-regime bucket. With ~11 days of data, these will be the features most at risk of overfitting.

Other recommendations:
- Use stationary, normalised inputs (imbalances, ratios, z-scores, returns) rather than raw levels. The Kolm et al. and Sirignano-Cont results both support this, and it is what makes pooling across many 15-minute markets work.
- Pool all 15-minute markets (and strikes) into one model, with moneyness and time-to-close as features, rather than per-strike models.

### Gaps
- No study found measures the BRTI-to-Kalshi lead-lag or the predictive value of the 60 s averaging window. This must be measured in-house.
- No LOB-prediction benchmark on prediction-market or binary-option books was found.
- The exact R² for Cont et al. OFI, the Gould & Bonart accuracy figures, and the F1 numbers from the Bybit study were not retrieved.

## 3. Small-data validation (purging/embargo, CPCV, walk-forward, day-block bootstrap, deflated Sharpe, days needed, GBDT regularisation, monotonic constraints, feature-selection stability)

### Takeaway
With 22 days, the independent unit is the day (or at best the 15-minute market), not the row. Tens of thousands of overlapping 120 s labels contain only a few hundred independent markets. Use purged and embargoed blocked CV with days or markets as groups. Combinatorial purged CV turns one test path into a distribution. Add a day-block bootstrap for intervals, and correct for the number of variants tried (deflated Sharpe / PBO). A single 11/4/7 split gives one path and cannot tell a robust model from a lucky one.

### Cited Findings
- **Purging and embargo.** Purged CV removes training samples whose label windows overlap the test set and embargoes a gap after it. CPCV splits data into contiguous groups and tests every combination of held-out groups (for example, 6 groups with 2 held out gives 15 combinations). The fold results can be recombined into many out-of-sample paths, giving a distribution of Sharpe ratios and a way to estimate the probability of backtest overfitting. — [Wikipedia: Purged cross-validation](https://en.wikipedia.org/wiki/Purged_cross-validation); [fynance CPCV docs](https://fynance.readthedocs.io/en/latest/generated/fynance.data.combinatorial_purged_cv.html)
- **Walk-forward** yields a single out-of-sample path, so the variance from that particular split is unmeasured. — [Wikipedia: Purged cross-validation](https://en.wikipedia.org/wiki/Purged_cross-validation)
- **What purging does not fix.** Purging does not fix leakage from feature engineering that used future data, or overfitting from trying many model variants. Those need a separate multiple-testing correction such as the deflated Sharpe ratio. — [Wikipedia: Purged cross-validation](https://en.wikipedia.org/wiki/Purged_cross-validation)
- **PBO / CSCV (Bailey, Borwein, López de Prado & Zhu, J. Computational Finance 2017).** Hold-out tests are "unreliable and inaccurate" for investment backtests. Combinatorially symmetric cross-validation (CSCV) estimates the probability of backtest overfitting with "relatively small errors". An R package, `pbo`, exists. — [SSRN 2326253](https://papers.ssrn.com/abstract=2326253); [Risk.net](https://www.risk.net/journal-of-computational-finance/2471206/the-probability-of-backtest-overfitting); [CRAN pbo](https://ftp.fau.de/cran/web/packages/pbo/readme/README.html)
- **Deflated Sharpe ratio (Bailey & López de Prado 2014).** It is the probabilistic Sharpe ratio evaluated against the expected maximum Sharpe of N unskilled trials. That hurdle grows roughly with √(log N), so honest counting of N matters. The minimum track record length is the number of observations needed for PSR to exceed a confidence level. — [LuxAlgo DSR concept](https://www.luxalgo.com/library/concept/deflated-sharpe-ratio/); [backtrader sharpe stats](https://backtrader.readthedocs.io/en/latest/api/analyzers/backtrader.analyzers.sharpe_ratio_stats.html)
- **LightGBM overfitting controls.** `min_data_in_leaf` is "a very important parameter to prevent over-fitting in a leaf-wise tree". Its value depends on sample count and `num_leaves`. Setting `num_leaves` near 2^max_depth overfits; for example, depth 7 with 127 leaves overfits, and 70–80 leaves can be better. Leaf-wise growth overfits more easily than depth-wise. — [LightGBM Parameters Tuning](https://lightgbm.readthedocs.io/en/latest/Parameters-Tuning.html)
  - `path_smooth` regularises leaves with few samples, and `extra_trees` uses randomised splits. Only brief guidance was found on either. — [LightGBM Parameters Tuning](https://lightgbm.readthedocs.io/en/latest/Parameters-Tuning.html)
- **Trees vs neural nets on small tabular data.** Tree ensembles remain state of the art on medium tabular data (~10k samples) across 45 datasets (NeurIPS 2022). — [Grinsztajn et al. 2022](https://arxiv.org/abs/2207.08815v1)

### Inferences
Protocol suggested for this case, building on the sources above:
1. **Groups and purging.** Use each 15-minute market, or each day, as a group. Purge any training row whose 120 s label window overlaps a test group, and embargo at least one market after each test block. Purging between markets is nearly free, because labels do not cross the settlement boundary.
2. **CPCV.** Use about 22 day-groups, test on 2 at a time (231 splits), or 6 blocks of about 3–4 days with 2 held out (15 splits). Report the distribution of cents/order across paths and the fraction of paths that are positive.
3. **Day-block bootstrap.** Resample whole days with replacement to get CIs on cents/order. The current "99% interval [+0.24, +0.47]" is very likely an order-level interval. Orders within a day are correlated, so a day-level interval will be much wider. With 7 test days, "7 of 7 positive" has a probability of 1/128 under a fair coin, which is meaningful but rests on only 7 independent draws.
4. **Count every variant.** Count every feature set, θ, barrier setting and hyperparameter tried as a trial N. Report a deflated Sharpe (or PBO via CSCV) on day-level P&L.
5. **Threshold in the loop.** Choose θ inside each training fold (nested), not on a single 4-day block. Otherwise the threshold is fitted to that block's regime.

Regularisation for ~11–20 independent days:
- Use shallower trees. The current mean depth is 10 with a max of 17 and 31 leaves. Try `num_leaves` 7–15, `max_depth` 4–6, and `min_data_in_leaf` large enough that each leaf spans many markets (hundreds to thousands of rows).
- Use `lambda_l2` > 0, `feature_fraction` 0.5–0.8, `bagging_fraction` about 0.7 with bagging by market where possible, `learning_rate` 0.02–0.05 with early stopping on purged validation, and optionally `extra_trees=true` and `path_smooth`.
- Add monotonic constraints where theory is clear. LightGBM supports a `monotone_constraints` parameter (not verified in this session's sources). Candidate constraints for this case:
  - Expected result non-increasing in contracts ahead in the queue.
  - Non-decreasing in (fair value − our bid).
  - Non-increasing in short-horizon volatility for a fixed 4¢ stop.
  Constraints cut variance and make the model safe to extrapolate.
- **Feature-selection stability.** Refit on each CPCV fold and keep only features whose gain or permutation importance ranks consistently, for example in the top-k in at least 80% of folds. Prefer grouped permutation importance by feature family, because the 5/10/30/60 s variants are highly collinear.

How many days are needed (rough arithmetic, not a sourced number):
- If the day-level SD of cents/order is s and the edge is μ, a t-stat of 2 needs about (2s/μ)² days.
- For example, μ = 0.17¢ over baseline and s = 0.25¢ gives about 9 days. s = 0.5¢ gives about 35 days.
- Measure s from the current data. Most likely the answer is "months, not weeks" to confirm a sub-cent edge across regimes. That argues for continuous logging and rolling retraining.

### Gaps
- No 2024–2026 empirical head-to-head of CPCV vs walk-forward on intraday HFT data was found.
- No published rule for the number of days needed for intraday ML strategies was found.
- The exact DSR and MinTRL formulas were not verified against the original paper (SSRN 2460551).
- LightGBM monotone-constraint documentation was not retrieved.

## 4. Model choice (GBDT vs small NNs, online learning and drift, ensembling, distillation)

### Takeaway
For tabular hand-crafted features with weeks of data, gradient boosting (or even logistic regression for the fill stage) is the right default. Deep LOB models do not generalise well on new data, and crypto-specific evidence shows XGBoost or logistic regression matching DeepLOB once inputs are good. Gains are more likely to come from better features, labels and validation than from architecture. Drift is best handled by frequent rolling retraining plus adaptive calibration, rather than true online tree learning.

### Cited Findings
- **Trees beat deep nets on medium tabular data.** Grinsztajn et al. (NeurIPS 2022) found this across 45 datasets with a 20,000 compute-hour hyperparameter search per learner. They attribute it to NNs' poor robustness to uninformative features and difficulty learning irregular functions. — [arXiv 2207.08815](https://arxiv.org/abs/2207.08815v1); [NeurIPS](https://neurips.cc/virtual/2022/poster/55627)
- **Deep LOB models generalise poorly.** LOBCAST found all 15 deep LOB models dropped significantly on unseen data. — [arXiv 2308.01915](https://arxiv.org/html/2308.01915)
- **Simple models match deep ones on crypto LOBs.** On BTC/USDT, simpler models (LR, XGBoost) match or exceed DeepLOB and Conv1D+LSTM with tuning and preprocessing. — [arXiv 2506.05764](https://arxiv.org/pdf/2506.05764)
  - An EmergentMind summary says DeepLOB gains only marginally over XGBoost/LR with Savitzky–Golay denoising, especially on crypto (secondary source). — [EmergentMind DeepLOB topic](https://www.emergentmind.com/topics/deeplob)
- **Simple nets on stationary inputs suffice.** Kolm et al. found simpler off-the-shelf networks on stationary OFI inputs matched state of the art. — [SSRN 3900141](https://papers.ssrn.com/abstract=3900141)
- **A linear fill model was enough.** A linear model achieved R² 0.946 for fill probability on Binance perpetuals (secondary summary). — [alphaxiv 2502.18625](https://alphaxiv.org/overview/2502.18625v1)
- **Pooling helps.** Sirignano & Cont found a single pooled model beat asset-specific models, and longer history helped. — [arXiv 1803.06917](https://arxiv.org/abs/1803.06917v1)
- **Predictability decays.** TLOB reports declining predictability over time (−6.68 F1 points). — [arXiv 2502.15757](https://arxiv.org/html/2502.15757v2)

### Inferences
- Recommended stack:
  - Fill stage: logistic regression or a small monotone GBDT.
  - Markout stage: shallow GBDT with monotone constraints.
  - Optionally a bagged ensemble of 5–10 GBDTs trained on different day-bootstrap samples. Averaging reduces variance, which matters most with few independent days, and the spread across members gives a free uncertainty estimate for gating trades.
- Small NNs (MLP, 1D-CNN over the last N seconds) are worth trying only after the book and BRTI features exist and there are a few months of data. Train them on the same stationary features. A 1D-CNN or GRU over raw event sequences is the kind of model LOBCAST showed failing to generalise.
- **Drift.** Retrain daily or every few days on a rolling window (for example, the last 20–40 days with exponential day-weights). Re-pick θ by purged CV, and monitor live calibration (Section 5).
  - LightGBM supports continued training from an existing model (`init_model`). That adds trees for the new data but does not remove old ones, so full rolling refits are cleaner.
  - Online logistic regression (SGD) for the fill stage is easy to run on-device and can adapt intraday.
- **Distillation.** If a large ensemble or NN wins offline, distil it into one shallow GBDT trained on the teacher's predictions over a large set of (possibly augmented) states. That keeps the phone model small and gives a smoother target than the noisy realised cents.

### Gaps
- No study found on online or incremental GBDT for HFT drift.
- No study found on distilling LOB models into trees.
- No evidence specific to prediction-market data.

## 5. Calibration and robust threshold choice

### Takeaway
The trade decision is "act if E[cents] > θ". The model needs to be calibrated in cents, in the region near θ, and that calibration should be maintained live. Isotonic regression on out-of-fold predictions, or a monotone 1-D GBDT, does this. Adaptive conformal inference (ACI) gives distribution-shift-robust lower bounds, so the rule can become "act if the lower bound > 0". Pick θ from a plateau of the P&L-vs-θ curve, pooled across CPCV folds, not from the peak on one validation block.

### Cited Findings
- **Isotonic regression in conformal methods.** It can be computed efficiently with XGBoost's univariate regression trees under monotonicity constraints, which is practical when the base learner is GBDT. — [Self-Calibrating Conformal Prediction, arXiv 2402.07307](https://arxiv.org/pdf/2402.07307)
- **In-sample isotonic calibration** yields conformal-style calibration guarantees. — [arXiv 2503.03841](https://arxiv.org/html/2503.03841v1)
- **Adaptive conformal inference (Gibbs & Candès, NeurIPS 2021).** ACI wraps any predictor to give prediction sets under distribution shift over time. Long-run coverage holds regardless of the data process, but performance depends on a step-size parameter. The 2024 JMLR follow-up handles unknown rates of change with small regret over all local windows. — [NeurIPS 2021](https://proceedings.neurips.cc/paper/2021/hash/0d441de75945e5acbc865406fc9a2559-Abstract.html); [arXiv 2106.00170](https://arxiv.org/abs/2106.00170v3); [follow-up arXiv 2208.08401](https://arxiv.org/pdf/2208.08401v2)
- **ML metrics are not trading metrics.** Briola et al. argue that ML metrics are poor for LOB forecast quality and evaluate operational transaction-level success. — [IDEAS](https://ideas.repec.org/a/taf/quantf/v25y2025i7p1101-1131.html)

### Inferences
- **Current threshold.** The current θ = −0.034¢ was the profit-maximising cut on 4 days. Since it is below zero, the model is acting on orders it predicts to be roughly break-even, which suggests its predictions are compressed (under-dispersed) or biased low. Isotonic recalibration of out-of-fold predictions against realised cents would put θ on an interpretable scale (θ ≈ 0 plus a margin) and make it portable across retrains.
- **Threshold choice.**
  1. Compute cents/order and orders/day for a grid of θ on every CPCV path.
  2. Take the θ that maximises the lower quantile (for example, the 10th percentile across paths/days) of total cents/day, or choose the centre of the widest θ range where all paths are positive.
  3. Shrink toward 0.
  This avoids the single-block peak.
- **Live calibration check.** On the phone, keep running reliability statistics: realised cents in prediction deciles, and a rolling ACI quantile. Auto-raise θ or pause when realised minus predicted drifts negative over the last N fills. This is cheap and catches regime change faster than retraining.
- **Two-stage model.** Calibrate P(fill) with Platt (logistic) or isotonic scaling against live fill logs. Most of the realised-vs-backtest gap will show up there.

### Gaps
- No published study found applying conformal or isotonic calibration to trading-threshold selection on intraday P&L.
- All guarantees cited are generic (exchangeability, or long-run coverage).

## 6. On-device inference speed (Kotlin/JVM trees, compiled trees, quantisation, TFLite / ONNX Runtime Mobile, incremental features)

### Takeaway
Model evaluation is not the bottleneck. 146 trees with about 6–10 node visits each is roughly 1–1.5k compare-and-branch steps. JIT/AOT-compiled Kotlin on a modern ARM core should do that in single-digit to low tens of microseconds; this is an estimate, since no Android tree benchmark was found. Network latency (tens of ms) and feature computation dominate. Code generation (if/else Kotlin, as m2cgen or lleaves do), flattened primitive arrays, float thresholds and fewer, shallower trees can cut that a few-fold if needed. Use TFLite or ONNX Runtime Mobile only if a NN is adopted. For a model this small, the JNI/runtime overhead probably exceeds the compute.

### Cited Findings
- **lleaves (LLVM compiler for LightGBM).** Self-reported speedup of at least 10x. NYC-taxi batch prediction drops from 12.77 s to 0.90 s. Single-row latency at batch size 1 is 52.3 µs for LightGBM, 28.0 µs for Treelite and 9.6 µs for lleaves; at batch size 100, it is 441 µs, 94 µs and 32 µs. Measured on an Intel i7-4770 (Haswell), minimum over 20,000 runs, through the Python API, so per-call overhead is included. — [lleaves on PyPI](https://pypi.org/project/lleaves/0.2.1/)
- **Database-perspective comparison (2023).** lleaves had the best CPU performance among most platforms for large models. — [arXiv 2302.04430](https://arxiv.org/pdf/2302.04430)
- **QuickScorer (Lucchese et al., SIGIR 2015).** Bitvector traversal with cache-aware layout and few branch mispredictions gave 2x–6.5x speedups over the best baselines, and 1.9x–6.6x in the extended version. No per-tree nanosecond figure was retrieved. — [Univ. Venice paper](https://iris.unive.it/bitstream/10278/3661259/7/paper.pdf)
- **Unofficial ports and compilers.** The LightGBM README lists m2cgen (code generation for many languages, including Java) and JPMML as unofficial appliers, and Treelite and lleaves as faster compilers. No m2cgen latency numbers were found. — [LightGBM README](https://github.com/lightgbm-org/LightGBM)
- **Single-row CPU vs GPU.** In one older RAPIDS benchmark, Intel DAAL beat GPU FIL for single-row inference. — [RTInsights](https://www.rtinsights.com/fast-gradient-boosting-tree-inference/)
- **ONNX Runtime Mobile vs TFLite.**
  - ONNX Runtime Mobile's package size is about equal (within ~1%) to TFLite's reduced build for MobileNet. An Android package (`onnxruntime-android`) exists. — [Microsoft Open Source blog 2020](https://opensource.microsoft.com/blog/2020/10/12/introducing-onnx-runtime-mobile-reduced-size-high-performance-package-edge-devices/); [ONNX Runtime mobile docs](https://onnxruntime.ai/docs/tutorials/mobile)
  - A third-party comparison claims TFLite has an edge on mobile, without measurements. — [Cactus compare](https://cactuscompute.com/compare/onnx-runtime-vs-tensorflow-lite)
  - INT8 runs faster on ARM phone CPUs. — [ODSC talk abstract](https://odsc.com/speakers/ml-inference-on-mobile-device-with-onnx-runtime-2/)
- **ART compiler.** ART (Android 8+) includes loop optimisations such as bounds-check elimination and vectorisation, which can help tight array loops. — [Android source: ART improvements](https://source.android.com/docs/core/runtime/improvements?hl=ja)

### Inferences
Concrete speed levers for the Kotlin evaluator, roughly in order of payoff:
1. **Shrink the model.** Shallower trees (depth 4–6) cut per-tree steps by about 2x and also regularise. If depth is fixed and the trees are symmetric (oblivious, CatBoost-style), evaluation becomes branch-free index arithmetic.
2. **Keep float.** Store thresholds as `FloatArray` and compare floats. The current code widens each feature to `Double` per comparison. Floats halve memory and match LightGBM's float32 training rows.
3. **Pack the nodes.** Use one contiguous `IntArray`/`FloatArray` for all trees (struct-of-arrays) instead of 146 `Tree` objects. That improves cache locality and removes per-tree object dereferences.
4. **Generate code.** Turn the model into Kotlin if/else at build time, as m2cgen does. This removes array loads and lets ART constant-fold thresholds. It can give a few-fold speedup (lleaves vs Treelite/LightGBM shows 3–5x on x86), but expect large methods (300 KB of JSON becomes many KB of code). Split it into one function per tree to stay under the 64 KB-per-method JVM/DEX limit.
5. **Quantise thresholds.** Binning features to 8- or 16-bit bin indices (LightGBM already uses ≤255 histogram bins) gives byte comparisons and smaller tables. It is worth it only if many models are evaluated per tick.

Feature computation:
- Keep O(1) incremental updates with ring buffers per window (5/10/30/60 s).
- Maintain running sums for volume, imbalance and counts, and running sums of squares for volatility, with expiry of old events.
- Use monotonic deques for max-print and min/max features.
- Avoid recomputing over all trades each tick and avoid allocation in the hot path (primitive arrays, no boxing).
- This typically matters more than tree evaluation.

Back-of-envelope latency (estimate, not measured):
- Assume about 1–3 ns per node visit on a modern big ARM core with warm caches.
- 146 trees × ~8 visits ≈ 1.2k visits ≈ 2–10 µs.
- A 5-model bagged ensemble would be about 10–50 µs.
- Either is negligible against 20–100 ms of exchange and network round trip.
- A tiny MLP (23→64→64→1, about 6k MACs) costs about 1–10 µs of compute in plain Kotlin. Runtime-call overhead in TFLite/ORT is likely of similar or larger order for such a model, so a hand-written Kotlin MLP is reasonable unless the model grows.

### Gaps
- No published Android/ART benchmark of GBDT inference or of TFLite vs ORT on tiny MLPs was found.
- The microsecond estimates above are my own and should be confirmed with an on-device microbenchmark (Jetpack Microbenchmark, warm-up, pinned big core).

## 7. Evaluation (realistic maker backtests: queue position, partial fills, latency; metrics beyond accuracy)

### Takeaway
Backtests that assume fills at the touch, or ignore queue position, systematically overstate maker P&L. Fills are adversely selected, and queue position determines both fill probability and markout. Model queue position explicitly (risk-averse and probabilistic queue models), add latency, and report cents/order and cents/day per day with day-level intervals. Then reconcile against live fills from the phone.

### Cited Findings
- **Queue modelling (hftbacktest).** Without queue modelling, backtests "optimistically assume instant fills when your price is reached", which "drastically overstates HFT profitability". The default no-partial-fill exchange requires the order to be at the queue front when trades print at its price. — [hftbacktest docs (mirror)](https://www.mintlify.com/nkaz001/hftbacktest/introduction); [hftbacktest Order Fill](https://hftbacktest.readthedocs.io/en/py-v2.4.2/order_fill.html)
  - **Risk-averse model:** cancellations happen only behind you, so queue position advances only on trades.
  - **Probabilistic model (`ProbQueueModel`):** cancellations can occur ahead of you with probability f(back)/(f(front)+f(back)), or a variant 1−f(front/(front+back)), with power-function f, for example `PowerProbQueueModel3(3)`. It is derived from a Quant.SE answer and Rigtorp's queue-position estimation.
  - With market-by-order (L3) data, the queue is tracked exactly.
  - The docs advise calibrating against live results. — [hftbacktest Probability Queue Models](https://hftbacktest.readthedocs.io/en/py-v2.1.0/tutorials/Probability%20Queue%20Models.html)
- **Hummingbot.** Exchanges don't reveal queue position, so "Don't trust absolute P&L numbers from backtests". — [Hummingbot Condor docs](https://condor.hummingbot.org/bots/backtesting)
- **Fill assumptions.** A 100% fill rate, and even exponential fill-rate models, are too optimistic because of negative drift. — [arXiv 2407.16527](https://arxiv.org/html/2407.16527v1)
- **Forecast quality is not tradability.** Briola et al.: forecasting accuracy ≠ actionable signals, so evaluate on transaction-level outcomes. — [IDEAS](https://ideas.repec.org/a/taf/quantf/v25y2025i7p1101-1131.html)
- **Unseen data.** LOBCAST includes profit analysis alongside F1 and shows drops on unseen data. — [arXiv 2308.01915](https://arxiv.org/html/2308.01915)

### Inferences
Backtest design for this case:
1. **Data.** Record Kalshi book snapshots and deltas plus trades (L2) on the phone or a server. Without book deltas you cannot run a queue model, and today's labels come from trade prints only.
2. **Queue model.** Place our order at the back of the displayed queue at the time of posting (plus a latency of, for example, the measured order-ack time). Advance it on trades at our price (risk-averse), and as a sensitivity also on cancellations with a power-law probabilistic model.
   - Report P&L under (a) front of queue (current), (b) risk-averse, (c) probabilistic with exponent 2–3.
   - Treat (b) as the deployable estimate until live fill logs calibrate the choice.
3. **Latency.** Add the decision-to-ack delay and cancel delay. A cancel that arrives after a trade-through still fills, and that is exactly the adverse case.
4. **Partial fills.** Simulate size, and allow partial fills when trade volume at our price after the queue ahead is less than our size.
5. **Live reconciliation.** Log every posted order on the phone with features, queue-ahead at post, fill time, fill size and realised exit. Fit the fill model and the queue-model exponent on these logs, and compare predicted vs realised cents per decile weekly.

Metrics to report:
- Cents/order and cents/day (P&L per day matters more than per order, because θ trades off frequency against edge).
- Orders/day and fill rate.
- Fraction of days positive, the worst day, and a day-block bootstrap CI.
- Deflated Sharpe or PBO across variants.
- A calibration slope of realised on predicted cents.
- P&L by time-to-close bucket and by moneyness. Edge concentrated in the last minute or at extreme prices is fragile.
- Adverse-selection diagnostics: 5/30/60 s markout after fills, split by queue position.

### Gaps
- No published realistic-backtest study of maker strategies on prediction-market or binary-option books was found.
- Kalshi queue dynamics (cancel rates ahead, whether displayed size is reliable) are unknown and must be measured from live logs.
- Equity, Treasury and crypto results on negative drift should transfer qualitatively, but their magnitudes are unverified for Kalshi.
