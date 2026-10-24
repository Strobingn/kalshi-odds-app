# Kalshi Odds / Dip Hunter (Android)

Android app for **Dirk Diggler** that shows live Kalshi **crypto** prediction-market odds and fires **analysis-only** signal alerts.

**Default watchlist (Bitcoin-only):**

| Series | Asset | Role |
|--------|--------|------|
| `KXBTC15M` | Bitcoin 15-minute | The only live series |

`KXETH15M`, `KXSOL15M`, and extra tickers are recognized so stored rows can be filtered out of scorecard / signal history / paper P&L. They are never subscribed, polled, scored, alerted, or paper-traded. The live list is `CryptoMarkets.DEFAULT_SERIES`.

**WTI crude (`KXWTI15M`) and all non-crypto markets are excluded** from defaults, UI toggles, REST watchlists, WebSocket subscriptions, scoring, and alerts.

- **UI:** Jetpack Compose + Material 3
- **Network:** Retrofit REST poll + optional authenticated Kalshi WebSocket
- **Alerts:** local `NotificationCompat` HIGH channel via a foreground WS service
- **Offline:** last successful crypto snapshot cached in DataStore

Package: `com.dirk.kalshiodds` · version **0.3.15**

## 1.20.8 (Claude branch): the NPU can reach the DSP

The NPU still failed with "graph nodes assigned to the default CPU EP" after the graph was quantized, because the QNN
HTP backend never started: its driver opens the Hexagon DSP through the phone's `libcdsprpc.so`, and an app that
targets Android 12 or newer cannot load a vendor library unless the manifest asks for it with
`<uses-native-library>`. The manifest now declares it (optional, so every phone still installs). If the NPU still
cannot open, the error now says whether that library loaded and names the chip.

## 1.20.7 (Claude branch): settings backup, faster polling and charts

**Settings backup.** Upgrades already keep everything (same package, same signing key); only an uninstall wipes it.
Data → Settings backup now keeps a copy of every setting outside the app: both DataStores (signal settings, data hub
with the Supabase URL and key), the arena's preferences (knobs, engine, gate, paper record), the AI settings (minus
the call log and chat), the on-phone LLM settings, and all keys (Kalshi live and demo, Groq, Cerebras, Gemini,
Mistral, GitHub). The file is `Downloads/ClaudeBitcoin/claude-bitcoin-settings.dhset`, AES-256-GCM with a key from
your passphrase (PBKDF2-SHA256, 120,000 rounds). It is rewritten every 10 minutes and when you leave the app, only
when something changed. After a reinstall: type the passphrase, Restore settings, pick the file; the app restarts.

**Faster.** Kalshi REST polling is 400 ms ± 100 (was 750 ± 250), and 3 s while the WebSocket is live (was 15 s); the
background service's refresh is 15 s (was 45 s); a new window is looked for every 1.5 s (was 2.5 s, both the rollover and the market resolver). The cards'
charts redraw up to every 150 ms (was 250), are built off the main thread with the archive reads cached, and have
150 points (was 64). The Arena's live window and micro charts refresh 4 times a second (were once), its record twice
a second, its trade list every 1.5 s and its trade charts every 4 s.

## 1.20.6 (Claude branch): the NPU graph is quantized

On the phone the NPU refused the model ("graph nodes assigned to the default CPU EP, but fallback to CPU EP has been
explicitly disabled"): QNN's HTP backend runs only quantized graphs, and lists MatMul only for 8/16-bit integer
inputs, so the float32 graph's MatMuls were left to the CPU. `export_npu.py` now writes every tensor as
QuantizeLinear / DequantizeLinear with scale 1 (opset 21; bins and differences in 16 bits, tables and 0/1 masks in
8 bits). Every value is a small whole number, so it is exact: all 3,125 test rows pick the trees' leaves, and the
test checks that every compute node sits in a QDQ unit. The file is 207 KB (was 800 KB).

## 1.20.5 (Claude branch): choose Kotlin, ONNX CPU or the NPU; ONNX Runtime in its own process

- **AI → ML scalper model → "Run the model on"**: Kotlin / ONNX CPU / NPU (Snapdragon QNN HTP). Default ONNX CPU.
  Whatever you pick is checked against the Kotlin trees on every split before it is used.
- **ONNX Runtime is back on its QNN build, in a separate process** (`ml/MlService.kt`, `android:process=":ml"`). The
  app's own process never loads it (1.18/1.19 did, and the phone would not start). The trees and TimesFM run there
  through binder calls (`ml/MlClient.kt`); if that process crashes, the app keeps going on the Kotlin trees and the
  card says why. The ML process starts no app services (KalshiOddsApp skips everything there).
- Measured on the phone before this change: Kotlin trees 30 µs, ONNX CPU 55 µs a prediction. The NPU and the
  separate process each add a hop, so for a model this small neither is faster; the switch is there to compare.

## 1.20.4 (Claude branch): paper fills only what the book shows; Explain is facts only

- **Depth-aware paper fills** (`BookFill.kt`): a buy-now order walks the offers the book shows, cheapest first, at
  most 5¢ above the ask, and fills no more than is offered there (36,000 contracts against 2,992 offered now fills
  2,992 plus whatever sits within 5¢). Stops, time-outs and final-window sales walk the bids the same way; what the
  bids cannot take is sold 5¢ under the bid. Each piece pays the taker fee at its own price. With no sizes from the
  book, 3,500 contracts are assumed at the ask (the arena's unknown queue). The Paper AI's bets fill the same way.
  Legs on other markets shrink with a partial fill.
- **Explain this trade, facts only**: no AI-written paragraph any more. It lists the strategy's rule from the code,
  the entry and the book it saw (and flags an order bigger than the ask showed), the price moves (marked when they
  are not part of the rule), the exit, the result and the strategy's whole record. It says plainly that one trade
  cannot show skill or luck, and that the settings shown are today's.
- **Daily review**: told to say only what the numbers show, with no causes, skill or luck.
- **On-phone LLM** sampling temperature 0.8 (was 0.3).

## 1.20.2 (Claude branch): TimesFM no longer waits 5 minutes after every app start

The Bitcoin settlement index's one-second history lived only in memory, so every app start (or restart by
Android) emptied it and TimesFM waited 5 minutes for enough of it. The last hour of it is now saved every 15 s
(`filesDir/cf_index_btc.csv`) and put back on start; TimesFM starts from 2 minutes of index instead of 5 (the rest of
its 512-second context is padded). The volatility estimate and the final-minute average get the same warm start.

## 1.20.1 (Claude branch): three micro scalpers retired after the replay

The arena replay of the 12 recorded days (2026-09-28 → 10-16, 913 windows, run 40005335840) on their defaults:
BRTI pulse −$248.76 on 1,364 trades (1 of 11 days up), VWAP reversion −$261.89 on 745 (0 of 11), theta favourite
−$91.69 on 633 (2 of 11). Like every strategy that lost the replay they are retired: they still run in learning
mode (on by default) so the gate, the live tuner and the Scalping AI keep learning on them. The OBI5 maker made no
trades there (the recordings have no 5-level book) and stays in.

## 1.20.0 (Claude branch): four micro scalpers, a self-learning gate, a live tuner, live-window charts

Ideas from a Gemini review of the scalper code (2026-10-23), rebuilt on this app's own book, index and paper engine.
Paper only, like the rest of the arena.

- **Four micro scalpers** (`signal/scalper/micro/Micro.kt`, arena filter "4 micro"), each fee-aware and each
  with its own trigger in Arena → strategy → Settings:
  - *OBI5 maker*: the top five book levels lean 35%+ to one side and the index's Student-t fair beats the bid
    by 2.5¢+: rest a bid, sell 3¢ up.
  - *BRTI pulse*: the settlement index moved 0.75σ+ in 10 s and the fair ran 2¢+ further than the Kalshi book:
    pay the ask on a 2¢ edge over the fee, sell where the book should catch up.
  - *VWAP reversion*: the book 3¢+ off the last 30 s VWAP of the prints while the index stays calm: rest on the
    cheap side, sell at the VWAP.
  - *Theta favourite*: 90–240 s left, the leader's Student-t fair (on the settlement average) 80%+: pay the ask
    on a 2¢ edge over the fee, else rest; hold.
  The three scalps are sold at the bid in the last 15–20 s instead of riding into the settlement.
- **Self-learning gate** (`OnlineGate.kt`): one small online model per strategy, trained on every closed trade
  (price, spread, moves, time left, fair edge, book imbalance). After 30 trades it skips entries that look like
  that strategy's losers; its threshold rises after a loss it let through and falls after a win. AI tab switch.
- **Live tuner** (`LiveTuner.kt`): on the phone, no key needed. After every 4 closed trades of a strategy it makes
  a winner bigger (×1.25, up to 500 on its own) and looser, a loser smaller (×0.7) and stricter, inside the
  Scalping AI's hard bounds. AI tab switch.
- **The Scalping AI follows the window**: still every 60 s, and now also early (at most every 20 s) on a new
  window, a 4¢ move in 10 s or 6 closed trades. The Paper AI keeps its own minute.
- **Charts**: Arena → Overview gets an *AI live window follower* (Bitcoin − strike above, UP / DOWN bids and the
  fair chance of UP below) and *Order-book microstructure* (fair, micro-price, 30 s VWAP, OBI5 / OFI5);
  Analysis → Race gets *Risk and return* (win rate against net, bubbles sized by trades); the AI tab gets the live
  tuner's feed and the gate's learning chart (rolling win rate, threshold, predicted chance).

## 1.19.1 (Claude branch): app starts again

1.18.0 and 1.19.0 shipped ONNX Runtime's Snapdragon NPU build (about 170 MB of Qualcomm QNN libraries) and the
phone would not start. Back to the CPU build of ONNX Runtime; the NPU engine is off; opening ONNX Runtime is
crash-guarded (a start that dies there makes the next start use the Kotlin trees).

## 1.17.1 (Claude branch): Gemini and Cerebras keys; the rotation skips a provider over its limit

- **Four free providers** (AI → Keys): Groq, Cerebras, Gemini and Mistral, rotating every 500 answered calls in
  that order (only those with a key). Every AI uses them: the Scalping AI, the Paper AI, the chat, Explain and the
  daily review.
  - Gemini: the OpenAI-compatible endpoint with a Google AI Studio key; models tried in order
    `gemini-3.8-flash`, `gemini-flash-latest`, `gemini-3-flash-preview`, `gemini-3.1-flash-lite`,
    `gemini-flash-lite-latest`, `gemini-2.5-flash` (Google counts the free quota per model, so "Resource has been
    exhausted" moves on to the next model). Reasoning is set low so thinking does not eat the answer budget.
  - Cerebras: `gpt-oss-120b`, then `qwen-3.8-27b` (free trial: 5 requests a minute, 1M tokens a day per model).
- **An over-limit provider is skipped.** When the current provider fails 3 calls in a row (its free limit, a bad
  key), the rotation moves to the next one at once instead of trying it first on every call; an answer in between
  resets the count.

## 1.17.0 (Claude branch): on-phone AI, Explain this trade, daily reviews, a calibrated ML scalper

- **On-phone AI (AI → Keys → On-phone AI).** A small LLM that runs on the phone's CPU: llama.cpp (release
  `b11829`, built from source by `app/src/main/cpp/CMakeLists.txt`) behind a thin JNI layer
  (`llama_jni.cpp`, C API only). Any GGUF model works: one tap downloads Qwen2.5 0.5B or 1.5B Instruct (Q4_K_M;
  resumes after a break, checked against Hugging Face's SHA-256 before use), or add a `.gguf` file from the
  phone (checked for the GGUF magic). It is loaded only while it writes and freed 90 s after, so it never sits
  next to the live scalper. llama.cpp is built for every ARM CPU level (`GGML_CPU_ALL_VARIANTS`) and picks the
  best one the phone has at start-up. The card shows the model, its state, tokens a second (with a chart) and a
  Test button. "Use the on-phone model first" (on by default) makes Explain and the daily review use it, with
  the Groq / Mistral keys as the fallback (`AiWriter`). Nothing is sent anywhere by the on-phone model.
  Checked on this release with the real Qwen2.5 0.5B on a desktop build (load 0.35 s, streaming, early stop,
  emoji and accents both ways, a too-long prompt refused) and cross-compiled for arm64 with NDK 27.
- **Explain this trade.** Every trade card in the arena has an Explain button. The dialog shows the trade (net,
  ¢ a contract, hold, minute of the window), the book it saw (from the order file), and for the ML scalper the
  model's chance, its expected cents against its cut and what it made, and bars of what each input pushed
  (the path through every tree, `ScalperModel.contributions`, which add up exactly to the model's score). Then
  the AI's explanation streams in, written only from those numbers (`TradeExplainer`); Copy puts the text and the
  facts on the clipboard.
- **Daily review (AI → Reviews).** Once a UTC day is over the app writes it up by itself (switchable), or tap
  "Review today so far" / "Review yesterday". Each review keeps its numbers and draws them: the day's running net,
  exits (donut and bars), best and worst strategies, win rates, net by minute of the window, by entry price and by
  hour, and the ML scalper's predicted against made cents; "Day by day" charts every reviewed day. The AI writes
  four short paragraphs from those numbers (`DailyReview`), kept for 90 days in `reviews.json`.
- **ML scalper model: calibrated chance, depth ≤ 5, the 25 money makers** (`train_calibrated.py`, now the
  retrain workflow's trainer). Retrained on 30 days of
  KXBTC15M trades (train 2026-09-23 … 10-11, tuned 10-12 … 10-15, tested once on 10-16 … 10-22):
  - 171 trees of depth 5 (was 146 trees up to 17 deep). It now outputs the **calibrated chance** that a resting
    scalp makes money (isotonic calibration on the validation days); test-day calibration error 0.45%, each
    decile's chance within a point of its real win rate. The chance is mapped to expected cents through a curve
    measured on the validation days, so the cut and the queue penalty stay in cents.
  - **The 25 money makers**, kept from 51 candidates by dropping, round by round, the features whose shuffling
    cost the least after-fee profit on the validation days: distance of the mid from 50¢, spread, mid below its
    window high, 30 s volatility, hour of day, price changes in 120 s, mid above its window low, price changes
    in 60 s, bid, 300 s volatility, and 15 more (`report.money_makers` in the file).
  - Test days, front of the queue: its picks made **+0.314¢ an order** (79,465 orders, 95% by day
    [+0.27, +0.36], up 7 of 7 days) against +0.260¢ for the old model's picks on the same days and +0.058¢ for
    every order. Gate: promote. Full report: `docs/scalper-model-retrain.md`.
  - It uses whole-window features (high, low, first quote), so it acts only in windows the app watched from the
    open.
  The AI tab's dashboard has a new "ML scalper model" card: test-day calibration (chance it gave against the share
  that made money), expected against made cents, the money makers, splitting gain and the selection steps.
  - `PrintGrid` rebuilds every candidate by name (51 of them, `CANDIDATE_LOOKBACK`) and refuses a decision when
    the app has not watched the window long enough for the model's longest lookback (it builds the grid from the
    live stream, so a window it joined late lacks the history). Every candidate is checked against `build_ml.py`
    on the recorded window (`ScalperParityTest`).
  - The model file now says what kind it is; the older 23-feature regression file still loads. The gate scores
    each model on its own features.
  - The ML scalper (`ML_REST`) is still on the arena's retired list (a big loser in the 11-day replay), so it
    places no paper orders unless it is taken off that list.
- Native libraries ship for 64-bit phones and the x86_64 emulator only (the on-phone AI for phones only).

## 1.16.2 (Claude branch): the ML scalper runs on ONNX Runtime; Paper AI tops out at 85¢

- **ONNX Runtime.** The ML scalper's 146 trees now run on ONNX Runtime Mobile 1.31.0 (CPU, one thread, full
  graph optimisation) from `assets/scalper_model.onnx`. `tools/research/scalp/export_onnx.py` writes it from
  `scalper_model.json` as one TreeEnsembleRegressor; each double threshold becomes the largest float32 not above
  it, so every split lands where the Kotlin trees put it, and missing values go the same way.
  - The app opens it off the main thread, refuses a file built from other trees (the JSON's sha256 and the
    feature names are in the model's metadata), and switches over only after it matches the Kotlin trees on 65
    rows that sit on, just over and just under every split. If it is missing, stale, different or ever fails,
    the scalper stays on (or goes back to) the Kotlin trees. The ML scalper's settings card says which it runs
    on (`ScalperModel.engine`).
  - Checked against the trees on the recorded window's 130 rows and 3,000 threshold-edge rows
    (`test_export_onnx.py`, in the unit-test workflow) and on ONNX Runtime's JVM build (`OnnxTreesTest`).
    The retrain workflow rebuilds and checks the ONNX file with every new model.
- **Paper AI:** the highest price it buys is 85¢ (was 90¢). Its lessons now show on the AI tab and in its chat,
  each bet line shows the fair price, and a look-back that got no answer is tried again on the next minute.

## 1.16.1 (Claude branch): the Paper AI borrows ideas from TradingAgents

From [TauricResearch/TradingAgents](https://github.com/TauricResearch/TradingAgents) (a multi-agent LLM
stock-trading framework). Its full analyst team is too slow for 15-minute windows, so only the parts that fit
one call a minute are taken. Paper only.

- **Grounded numbers.** The AI feed now carries Kalshi's strike, how far the index is from it and the fair
  price of YES (`fairYesCents`, the same `ScalpFair` chance the late favourites trade on). Before, the Paper AI
  saw the spot price but never the strike, so it could not tell which side Bitcoin was on.
- **Bull vs bear.** Each bet must state the best case for UP and for DOWN before picking a side, in the same
  call (no extra cost). Both are saved on the bet.
- **Unlimited paper bets.** Any bet inside the limits (20–880 s into the window, ask 10–85¢, up to 50
  contracts) is placed, with no cap per window. Each bet also saves the fair price for its side and a
  `chanceCents` (`PaperAiCalibration`: its confidence cut to the share of claimed edge that has shown up,
  averaged with the fair price), for the review only; neither ever blocks a bet.
- **Lessons after the result.** The AI no longer writes a "lesson" while betting. After a window it bet on
  settles, it gets one call to look back (its bets, its cases, the fair price, how it finished) and writes one
  lesson; those feed the next bets as `lessonsFromResults`.

## 1.10.1 (claude/scalper-model-refresh): opening ride, late edge, contract checks

Two new arena strategies and a check of the two contract facts the edge report
(`reports/`, not in the repo) could not confirm. Paper only.

- **Opening ride · buy, +10¢ / trail 3¢.** In the window's first minute it buys
  the side the live Bitcoin price leans to (fair price from Kalshi's CF index
  51% or more; the market's own favourite, mid 52¢+, while the index is not in),
  at whatever it costs inside 40–65¢. Out at +10¢ on a resting offer, or sold
  at the bid once the bid falls 3¢ under its best bid since the entry (a
  trailing stop). Once a window; anything still open settles. The lean, target,
  stop, size and band are all in its settings.
- **Late edge · settle average, buy, hold** (report rank 1). With 5–45 s left,
  most of the settlement average is fixed. It buys any side whose fair price
  (prints fixed so far plus the live index) is 80%+ and beats the ask by the
  taker fee (rounded per order) plus 2¢, and holds to the settlement. Once a
  window.
- **Contract checks** (Arena → settings). Every 6 h the app reads
  `GET /series/KXBTC15M` for `fee_type`; if Kalshi ever lists maker fees, the
  arena charges 0.0175·C·P·(1−P) on every resting fill. Every 5 min it compares
  each settled window's `floor_strike` with the index average of the minute
  before the open, the minute after it and the price at the open, and
  `expiration_value` with the last minute's average, while Live signals was on.
- The arena now decides from the 2nd second (opening ride only) to 5 s before
  the close (late edge only); 37 strategies.

## 1.10.0 (claude/scalper-model-refresh): Bitcoin prices from Kalshi only, everywhere

- New setting **Bitcoin price from Kalshi only** (Settings → Trade feed, on by default). The CF Benchmarks
  index Kalshi streams to your API key (the index the 15-minute markets settle on) becomes the app's spot:
  it feeds the live spot book that re-scores markets (replacing the Coinbase WebSocket) and the spot
  features the signals and models read (replacing the Binance / Coinbase REST poll): 1- and 5-minute
  returns and the trainer's EWMA σ, from 1-minute bars of the index (`KalshiSpot`, `CfIndexBook` keeps an
  hour of it). No funding rate (Kalshi has none). Needs Live signals; the model's 60-bar σ is ready about
  an hour after the stream starts. Off: the Coinbase / Binance feeds as before.
- Still from Coinbase: the optional history backfill on the Data screen (Kalshi has no past spot prices).

## 1.9.9 (claude/scalper-model-refresh): the arena runs on Kalshi data only

- Every number the arena trades on now comes from Kalshi through your API key's WebSocket: the trade prints
  and the order book (as before), and for the late favourites the CF Benchmarks index Kalshi streams (the
  index these markets settle on), Kalshi's strike and, in the final minute, Kalshi's running settlement
  average. The index's volatility is now worked out from that same stream (1-second changes over the last
  15 minutes, `CfIndexBook.std1m`) instead of Coinbase, and there is no Coinbase fallback: while Kalshi's
  index is stale, the late favourites do not trade.

## 1.9.8 (claude/scalper-model-refresh): change every strategy's settings, reset the paper record

- **Per-strategy settings** (after grok-bitcoin's scalp sliders, but one set per strategy): open a strategy
  in the arena and its Settings card has an on / off switch and sliders for its main trigger (the dip size,
  the flow size, the favourite's fair-price floor, the ML scalper's cut, …), contracts an order, take profit,
  stop loss (holds can be given one), time-out, positions open at once, the price band and how often it
  looks; "Back to its defaults" undoes them. Saved on the phone (`ScalpTuning`); an untouched strategy runs
  exactly as shipped, and the rules text on its page shows your values.
- **Arena settings** (the sliders icon at the top of the arena): every strategy's on / off switch with its
  size and exits, "Put every strategy back to its defaults", and **Reset the paper record**, which archives
  the trade files and starts every strategy again from $0 (your settings stay).

## 1.9.7 (claude/scalper-model-refresh): Kashi's late favourites, and a cleaner arena

- **Five strategies copied from Kashi V2** (`ShockFav.kt` sf16 / sf19 / sf21 / sf23 / sf31), the only family
  there with an out-of-sample result above zero after fees (BTC, 2 s entry delay, 2026-10-05 → 10-10: sf19,
  sf21 and sf31 about +$1.40 a 10-contract trade, sf23 +$1.79; 10–16 trades each, so small). In the last
  minute they buy the side the live Bitcoin price makes a 90–97% favourite while its ask is still 3–5¢ under
  that, and hold it to the settlement; Kashi's stops (10–25¢ at the bid) are the only exit. The fair price
  comes from the CF Benchmarks index (Coinbase when it is stale), the stream's 1-minute volatility and, in
  the final minute, the settlement average so far (`ScalpFair`). 35 strategies now run at once.
- **The arena screen, rebuilt after the Swarm / Kashi scalp screens.** Four tabs (Overview, Strategies,
  Analysis, Trades) instead of seven chips; a hero card with the net after fees and a figure grid; one large
  bankroll chart; strategies as two-line rows with a P&L stripe, sparkline and net; trades with the strategy
  as a pill and monospace prices. The deeper charts sit under Analysis (Race, Breakdown, Edge, Execution,
  Timing) and behind "More charts" on a strategy's page; every chart's explanation opens from its ⓘ. Profit
  is the same green and loss the same red everywhere.
- Research: `docs/scalper-smart-exit.md` (selling scalps early does not help; wider stops did better).

## 1.9.6 (claude/scalper-model-refresh): review fixes for the 30-strategy arena

- *Skill or luck?* now resamples whole market windows, not single trades: trades in one window share its
  outcome, so the fan is honest about how wide the future is (as the leaderboard's luck test already was).
- Squeeze breakout and range floor fire only when both minutes of their two-minute range were actually quoted.
- Following a strategy now scopes the stats table and the price, day and minute heatmaps to it.
- Drawdown (strategy page and Overview) is read from every close, not the chart's sampled points.
- Rank by day starts from the first whole day when the chart's 3,000-trade slice cuts into the oldest one.
- A strategy page opens at its top; best / worst trade show the real extremes of a one-sided record.
## 1.9.6b (Claude branch): every call uses Kalshi data only

- **Card call (BET / NO BET).** Only the clear-lead rule can say BET now. The AI model's chance comes from an
  outside Bitcoin feed (Coinbase / CF Benchmarks), so it no longer makes calls, and its alerts are no longer sent.
- **Clear lead, Kalshi prices only.** The side Kalshi prices at 80–90¢ between minute 3 and minute 10 (it used
  to be "Bitcoin 0.10–0.20% from the start price" on Coinbase). History on 46 days of Kalshi 1-minute prices
  (`tools/research/clear_lead_kalshi.py`): +0.66¢ per contract in the first half, +0.48¢ in the second, neither
  clear of zero. The same size as the Coinbase version.
- **No automatic tickets** (hunter, long-shot, configured): they were picked by the model. Buy on a card, limit
  orders and the clear-lead ticket work as before.
- **Late-favorite tracker stops recording**: it needs a Bitcoin price. Its card keeps its record.
- Unchanged: the scalper and the strategy arena were already Kalshi-only. The AI % and the Bitcoin line on the
  card and chart are still shown, for information only.

## 1.9.5 (claude/scalp-insights): Compare all 30, and a page for every strategy

- **Compare** (a new section chip, second from the left): every strategy in one table: rank, net, trades,
  win rate and a fourth column that follows the sort.
  - Filter chips: *All · Buy now · Resting · Hold to close · New in 1.9.4 · Has traded*, each with its count.
  - Sort chips (or tap a column's name): net, trades, win rate, ¢ a contract, profit factor, drawdown, hold
    time, fill rate. Tap again to flip best-first / worst-first. Strategies with no number yet always sit at
    the bottom.
- **A page for every strategy.** Tap a row in Compare, or *Open* beside the strategy you follow. The page has:
  - its rules in words: signal, entry, exit, price band, size;
  - its net and luck verdict;
  - every number: trades, wins, ¢ a contract, profit factor, dollars won and lost, drawdown, hold, fees,
    best and worst trade, runs, posted and filled, how its trades ended;
  - its own charts: running profit, drawdown, per-trade histogram, rolling edge, the luck fan, calibration
    (holds), hold time, minute of the window and price band;
  - its last 20 trades.

  Back returns to the arena. Totals come from the ledger (every close), the per-trade numbers from the trade
  file. Numbers: `ArenaTable` (`ArenaTableTest`); drawing: `ui/ArenaCompareViews.kt` and `StrategyPage`.

## 1.9.4 (claude/scalp-insights): fifteen more strategies — 30 at once

The arena now runs **30** paper strategies side by side on the same live window, fills and fees. The fifteen new
ones each trade their own signal; none has history on this engine yet, so their leaderboard rows say
"new · Claude 1.9.4" until they trade. Existing records keep their history: the new rows simply start at zero.

| Strategy | Entry | Exit |
| --- | --- | --- |
| Stretch fade · buy now | mid 3¢+ under its own 60 s average, spread ≤ 2¢ | +2¢ / −3¢ / 45 s |
| Squeeze breakout · buy now | two quiet minutes (range ≤ 4¢), then the mid 1¢ above that range | +3¢ / −2¢ / 60 s |
| Range floor · resting | a 3–8¢ two-minute range, mid back on its low: bid at the floor | +2¢ / −3¢ / 60 s |
| Trend pullback · buy now | up 5¢+ over two minutes, down 1¢+ in the last 10 s | +3¢ / −3¢ / 60 s |
| Flow follow · buy now | takers bought this side 3:1, 500+ contracts in 30 s (flow fade's mirror) | +2¢ / −2¢ / 30 s |
| Whale follow · buy now | one taker print of 1,000+ contracts on this side in the last 5 s | +2¢ / −2¢ / 30 s |
| Quiet maker · resting | the mid has not moved for 30 s | +1¢ / −2¢ / 60 s |
| Opening drive · buy, hold | minutes 2–4, up 6¢+ since minute 1, 50–80¢ | settlement |
| Late tiebreak · buy, hold | minutes 12–13, 45–60¢, up 2¢+ in the last minute | settlement |
| Heavy favorite · buy, hold | minutes 12–13, 92–97¢ | settlement |
| Panic fade · buy now | down 4¢+ in 10 s with 300+ contracts sold into it | +3¢ / −3¢ / 60 s |
| Steady climb · buy now | up 1¢ / 3¢ / 4¢ over 10 / 30 / 60 s | +3¢ / −2¢ / 60 s |
| Penny scalper · resting | 1¢ spread and at most 200 contracts ahead on the bid (needs the live book) | +1¢ / −2¢ / 30 s |
| Book imbalance · buy now | the bid shows 3× the ask's size and 300+ contracts (needs the live book) | +2¢ / −2¢ / 30 s |
| Late comeback · buy, hold | minutes 10–13, an underdog at 20–40¢ up 4¢+ in 30 s | settlement |

Every chart, heatmap and table in the Arena covers all 30. Signals: `PaperScalper.decide` (new grid helpers
`PrintGrid.avgMid` and `biggestTakerBuy`); tests: `ArenaMoreStrategiesTest`, one per strategy, each with the case
that must not fire. Paper only. A larger field also means more chances for one strategy to look good by luck;
the leaderboard's luck verdict still needs 30 trades over 10 windows before it calls anything.

## 1.9.3 (claude/scalp-insights): an Execution section — how the orders are worked

A sixth chip, **Execution**, looks at how each strategy gets in, not just what it made:

- *Win often or win big?* The payoff map: each strategy at its win rate (across) and average win ÷ average loss
  (up), dot size by trades. The dashed curve is break-even (payoff = losses ÷ wins); above it made money. A 30%
  hitter can be fine if its wins are 3× its losses; a 70% hitter can lose if they are not.
- *Pay the ask or wait?* The dip, momentum and extreme-reversion signals each run twice, buying now and resting
  a bid. Side by side: result per contract after fees with ±2 standard-error whiskers, and how often the resting
  half filled.
- *How often bids fill.* Filled ÷ posted for every resting strategy.
- *Fast fills, good fills?* Strategy × how long the resting bid waited (<1s … 1m+). Bids that fill instantly are
  often the ones the market was leaving: adverse selection shows up as red on the left.
- *Place in line.* Strategy × contracts ahead in the queue when posted (front … 1k+).
- *Did the ML forecast hold?* The ML scalper's expected ¢ at posting, banded, against what its fills made, with
  the "forecast was right" diagonal. Only filled bids count, so a gap is partly the fills being worse than the
  average post.
- **Overview** gains *Rank by day*: every strategy's place on running profit at the end of each of the last 14
  days; the three leaders (or the one you follow) in colour. Drag to read a day.

Numbers: `ArenaExec` (`ArenaExecTest`); drawing: `ui/ArenaExecViews.kt`. Paper only.

## 1.9.2 (claude/scalp-insights): an Edge section — is any of this skill?

A fifth chip, **Edge**, asks the question the profit bars can't answer: is a strategy beating the market, or lucky?

- *Is the price right?* For trades held to the close: how often they won against the price paid (the market's
  own chance), by 10¢ band. The dashed diagonal is "won exactly as often as the price said", the short dash the
  break-even after the entry fee, the whisker ±2 standard errors. Blue = clearly beat the price, red = clearly
  lagged it, grey = can't tell yet (or under 5 trades).
- *Skill or luck?* The strategy you follow (or the leader), its own trades reshuffled 400 times: where the next
  200 could land (80% band and the middle path) and the share of paths that finish ahead. It says what the
  record implies, not what the future holds.
- *Is the edge holding?* Its average ¢ a contract over the last 30 trades, through time; the line turns red
  below zero.
- *Who trades alike.* Correlation of each pair's net, window by window (at least 8 shared windows). Two
  "winners" that move together are one bet, not two.
- **Timing** gains *When in the window*: strategy × minute of the 15-minute window the entry filled (0–14).

The section chips now scroll sideways instead of squeezing. Numbers: `ArenaEdge` (`ArenaEdgeTest`); drawing:
`ui/ArenaEdgeViews.kt`. Paper only.

## 1.9.1 (claude/scalp-insights): more scalping charts and data, in four sections

The Arena tab now has one row of section chips (**Overview · Strategies · Timing · Trades**) under the headline,
and the "follow a strategy" filter applies across all of them.

- **Overview.** *Live window*: the Bitcoin window trading now, its UP price second by second, with a ring at
  every paper entry and a dot at every exit (blue made money, red lost, grey still open) joined by a thin line;
  DOWN trades sit on the UP line (a DOWN entry at 40¢ is drawn at 60¢); tap a mark for the trade. Then the profit
  bars, the race, the leaderboard, the bankroll and a new *drawdown* chart (how far below its best the arena, or
  the strategy you follow, sits).
- **Strategies.** A *stats table* per strategy: trades, ¢ a contract after fees, profit factor (won ÷ lost),
  deepest drawdown, average hold, longest win / loss streaks. A *price heatmap*: strategy × entry price (10¢
  bands of the side bought), blue made money, red lost, grey ≈ 0, outlined = under 3 trades. Then exits, win
  rate and fees.
- **Timing.** A *day-by-day heatmap* (strategy × the last 14 days), the hourly columns and *how long to hold*
  (¢ a contract by time in the trade: under 10 s … over 5 min, and held to the close).
- **Trades.** The per-trade histogram, the totals and every trade.

Numbers: `ArenaStats` (`ArenaStatsTest`); live data: `PaperScalper.liveWindow` (prints per second +
`ScalperEngine.openPositions`); drawing: `ui/ArenaInsightViews.kt`. Paper only, as before.

## 1.9.0 (Claude branch): the Strategy Arena tab, 15 strategies at once, paper only

Claude Bitcoin 1.9.0 installs over 1.8.8 and keeps your key, settings and paper records. It also carries
`Mis_bitcoin`'s edge-model work (13-feature contract, resumable training, `mis-bitcoin-edge-model`) and:

- **Arena tab** in the bottom bar (second from the left). It holds a leaderboard of all 15 strategies, best paper
  P&L after fees on top, each with a verdict: *N of 30 trades needed*, *within luck: no winner yet*, *ahead by
  more than luck so far* or *losing by more than luck*. Below it: the totals, the bankroll chart, the breakdowns
  and every trade. The Home card **Strategy arena · 15 at once** opens the same screen.
- **Not running?** The tab says why and fixes it in one tap: the arena needs **Live signals** on and a **Kalshi API
  key** saved (the trade feed is the authenticated WebSocket; REST has no trade prints). Live signals is off by
  default, which is why the 1.8.4–1.8.8 scalper showed nothing on a fresh install.
- **Charts (Arena tab only).** Tap any strategy (in the profit bars or the leaderboard) to follow it; one
  "Show all" resets.
  - *Who is winning:* profit after fees per strategy, diverging around $0 (blue gain, red loss).
  - *The race:* running profit of all 15 from the same $0 line over the last 3,000 closed trades; the top three
    and the one you follow in colour, the rest grey; drag across to read every coloured line at one moment.
  - *Leaderboard sparklines:* each strategy's running profit in one small line.
  - *Paper bankroll:* one line with a light wash; drag to read any moment.
  - *What one trade makes:* histogram of the result per contract after fees (2¢ bins, open ends for holds);
    red bins lost money; all strategies or the one you follow.
  - *Best and worst hours:* profit by hour of the day.
  - *How trades end:* target / stopped / timed out / held to close, per strategy.
  - *Win rate:* per strategy, with the 50% line.
  - *What the fees take:* profit before and after the taker fee, per strategy (dumbbell).

  Colours are the validated reference palette (blue/orange/aqua/yellow for the race and the exit mix; blue ↔ red
  for gain / loss), stepped for the dark and the light theme and checked for colour-blind separation. Every chart
  has a legend or labels, and every value is also in a list on the same screen. Numbers: `ArenaCharts`
  (`ArenaChartsTest`); drawing: `ui/ArenaChartViews.kt` (`ScalperResultsScreenshotTest` renders each chart).
- **Eight new strategies** next to the seven from 1.8.4, taken from the other branches' apps. They all run on the
  same live window, the same real trade prints, the same conservative fills (resting orders wait for the queue
  ahead) and the same taker fee, 10 contracts an order:

| Strategy | From | Entry | Exit |
|---|---|---|---|
| Bounce · buy now | codex `ScalpEngine` | 5¢ fall from the 60 s high, first 1–3¢ back, spread ≤ 2¢ | +8¢ / −6¢ / 120 s |
| 50¢ breakout · buy now | grok `breakout_50` | side crosses up through 50¢ within 10 s, spread ≤ 2¢ | +5¢ / −3¢ / 90 s |
| 1¢ better · resting | grok `CentBetter` | spread ≥ 2¢, minute 3+, bid 1¢ above the best bid | +2¢ / −4¢ / 60 s |
| Flow fade · resting, hold | grok `FlowFade` | takers bought the other side 3:1, ≥ 500 contracts in 30 s | settlement |
| Late favorite · buy, hold | grok `LateFavoriteRule`, kashi D3 | minutes 10–13, 80–95¢ | settlement |
| Early favorite · buy, hold | Claude clear lead (price form) | minutes 3–10, 65–85¢ | settlement |
| Momentum ride · buy, hold | kashi forecast ride, swarm | +4¢ in 30 s, minute 5+, 50–85¢ | settlement |
| Long shot · buy, hold | app HUNTER_VALUE, grok `penny_press` | minute 5+, 10–20¢ | settlement |

**What the other branches show about money.** None of the 11 branches surveyed has a strategy with a positive,
out-of-sample result after fees: every scalper backtest is negative (kashi long history: dip −4.44¢,
momentum −4.77¢, extreme reversion −2.53¢ per contract over 38 weeks; swarm: 0 of 2,100 dip combos positive), the
app's pick lost −3.3% on 1,451 bets, and the 15-scalper labs on grok-bitcoin and miss-bitcoin committed no results
(miss-bitcoin's paper fills also charge no fee). The closest to break-even were the late favorite (+$0.059 a bet,
99% CI [−0.017, +0.141]) and 1¢ better (+$41.73 on 17 fills), so both are in the arena. The arena is how to find out
on live data; it cannot place an order.

| Control | Code | Test |
|---------|------|------|
| 15 strategies, bands, holds | `ScalpStrategy`, `ScalperEngine.inBand`, `onTrade` / `onClock` skip holds | `ArenaStrategiesTest.holdStrategiesHaveNoExitAndRideToTheSettlement`, `eachStrategyTradesOnlyInsideItsOwnPriceBand` |
| New signals | `PaperScalper.decide`, `PrintGrid.highMid` / `lowMid` / `takerBought` | `ArenaStrategiesTest.aFavoriteLateInTheWindow…`, `flowFade…`, `oneCentBetter…`, `aFiveCentDrop…` |
| Leaderboard and verdict | `ScalperResults.leaderboard` / `verdict` / `leaderLine` | `ArenaStrategiesTest.everyStrategyHasItsOwnRow`, `theVerdictSaysWhenALeadIsMoreThanLuck` |
| Status panel | `arenaFeed` (ScalperResultsScreen.kt) | `ArenaStrategiesTest.theStatusPanelNamesWhatStopsTheArena` |
| Arena tab | `AppRoutes.PRIMARY`, `DipApp` bottom bar | `ArenaStrategiesTest.theArenaIsABottomTab` |
| Update channel | `AppUpdate.BRANCH = "Claude"` (tags matched on the branch suffix) | `AppUpdateTest` |

## 1.8.8 (Claude branch): the app stops telling you to bet without a reason

Reported with a screenshot: `BET UP BTC · $5 wins $2330.00 profit · closes in 1:11` with UP at 0.2¢ and
"Model 4% vs market 0% · edge +3 pts". The 4% was the model's floor (its chance is clamped to 2–98%), so
any side priced at a fraction of a cent showed a few points of "edge".

- **No call under 5¢** (`TicketBuilder.MIN_CALL_ASK`). The card says NO BET with the reason, the hunter
  and long-shot tickets are not offered there, whatever the model says. Your own Buy and limit orders
  still work at any price.
- **The model has to earn BET** (`EdgeAutoTuner`). It may call a bet only when its own settled record has
  enough calls, beats Kalshi's prices on both scores, and its average result per call is above zero by two
  standard errors. Before, a model with no record was allowed to call bets and any positive total counted.
  With no record, a losing one, or one within chance, the card says NO BET and why. A fresh install starts
  silent. The clear-lead rule (your rule) is separate and unchanged.

## 1.8.7 (Claude branch): limit orders at your own price, paper and real

Buy UP / Buy DOWN (or Sell on a position) → **LIMIT ORDER** / **LIMIT SELL** on the confirm sheet opens an
editor (`LimitOrderDialog`, rules in `signal/limit/LimitOrder.kt`): pick the price (1¢ steps from 10¢ to
90¢, 0.1¢ outside), the contracts and how long the order may rest (30 s, 2 min, 5 min, or until 30 s
before the close). It shows the best bid and ask, the size already resting at your price, and the cost
and payout if it fills.

- **Resting, post-only.** Kalshi rejects the order instead of letting it cross, so it never pays the taker
  fee. A price at or through the other side is refused in the editor ("use Buy").
- **Paper** (`PaperLimitBook`): the order waits against the real book and fills only from real trade
  prints, after the size ahead of it has traded (or a print goes through its price). The size ahead can
  only shrink as the live book shows fewer contracts at that price. No fee. Needs Live signals on. Paper
  resting orders are dropped if the app restarts.
- **Real**: sent only by the editor's REAL MONEY button, through the same Approve path, $5 all-in cap and
  daily cap as every live buy. Every resting order carries Kalshi's `expiration_time`, so it ends on
  Kalshi's side even if the app is closed; the app cancels it 2 s earlier when it is running.
- **Limit sells** rest above the bid for no more than is held. Kalshi allows reduce-only only on
  immediate-or-cancel orders, so a resting sell is not reduce-only: while one rests on a window the app
  will not send another sell there.
- **Limit orders resting** (Home card): every real and paper resting order with its Cancel. Before this
  version a resting real order could not be cancelled from the screen.
- The min-profit-if-win setting switches off the buy-now ticket above about 31¢; a limit order at your own
  price is not held to it, and the editor says so.

Not checked against Kalshi: this build was unit tested (request shape, caps, expiry, paper fills) but no
real or demo order was sent from here. A limit order does not jump the queue: see sections G–I of the
scalping write-up for what joining the best price did on recorded books.

## 1.8.6 (Claude branch): the scalper card shows real-order-book history

The figures in brackets on the Paper scalper card were "22 days of trade prints, back of the queue to
front". They are now what a filled scalp of each kind made on 7 days of recorded order books with the
real queue counted (all negative, see below). No trading logic changed.

## 1.8.5 (Claude branch): scalper results screen, every paper order saved

Home → Paper scalper card → **See full results, chart and every trade** (`ScalperResultsScreen`):

- profit or loss after fees, dollars won against dollars lost, win and loss counts, average and biggest
  win and loss, total fees paid;
- a chart of the paper bankroll over time (it starts from a $1,000 reference; the scalper has no real balance);
- breakdowns by strategy, by coin and by hour of the day;
- every closed scalp, newest first, with its entry, exit, fees and net.

Every paper order is now kept on the phone under `filesDir/scalper/` (`ScalpTradeLog`):
`scalps_YYYY-MM-DD.csv` has one row per closed scalp; `scalp_orders_YYYY-MM-DD.csv.gz` has one row per
order, filled or not, with the book the scalper saw, the price moves and the model's inputs. "Export all
scalper data (zip)" on the results screen saves them all. "Reset paper scalper" archives the files instead
of deleting them. A record made by 1.8.4 is replaced by a new one, because 1.8.4 kept no per-trade file
and no won / lost / fee totals. Still paper only: no order path.

The cloud recorder's files and the study's trade tape are now also kept in the `scalper-data` release
(`export-recordings.yml`, `archive-tape.yml`, `record.yml`), and the recorder keeps 15 book levels a side.

**Real order books** ([`docs/scalping-2026-10-09.md`](docs/scalping-2026-10-09.md) sections G–I): on 7
days of recorded books with the real size ahead counted, every rule the paper scalper runs lost on every
day (ML scalper −1.6¢ per order sent, resting signals about −2¢, buy-now about −3.5¢), a model given the
book and one-second Bitcoin found nothing worth trading, and a short queue turned out to be the worst
place to join. The card's figures in brackets are these numbers now.

**Patient orders** (section J, 2026-10-10): a bid placed 1–5¢ below the best bid and left to wait, on two
days of 15-level book data. All 20 versions lost (about −1.4¢ to −2.4¢ per order sent). Waiting roughly
halves the queue ahead but most fills come from the price falling through the order. Two days is a small
sample; the app was left alone.

## 1.8.4 (Claude branch): paper scalper, aggressive, paper only

Home card **Paper scalper · aggressive · PAPER ONLY** runs seven scalping strategies at once on the live Bitcoin window and scores each one: the ML scalper (a trained model picks calm moments to rest a bid and offer it 1¢ higher), and dip-hunter, momentum-sniper and extreme reversion, each in a buy-now form and a resting form. Both sides, several positions per strategy, 10 contracts an order, no caps, exits in 30–120 s. It holds no trade client and no credentials: nothing it does can reach Kalshi.

- **Real fills, real queue.** Orders are filled by the public trade prints. A resting order fills only when a print trades through its price or the size shown ahead of it plus its own size has traded at its price. Buy-now entries and stop / time-out exits pay the exact taker fee.
- **Scoreboard.** P&L, scalps and win rate per strategy, with what the strategy did on 22 days of history beside it. For the ML scalper, also the split by queue ahead and the orders its queue penalty says should pay.
- **What history says** ([`docs/scalping-2026-10-09.md`](docs/scalping-2026-10-09.md) sections E and F): the buy-now strategies lost 3.1–3.7¢ a contract on all 22 days; the resting versions were about zero at the front of the queue; the ML scalper made +0.35¢ at the front (99% interval [+0.24, +0.47]) and lost past about 250 contracts ahead.
- Needs Live signals on (the trade prints and the book). Settings → **Paper scalper** turns it off and resets the record.

| Control | Code | Test |
|---------|------|------|
| Features from trade prints | `PrintGrid.features` | `ScalperParityTest.featuresRebuiltFromPrintsMatchThePythonStudy` |
| Model in the app | `ScalperModel.predict` (`assets/scalper_model.json`) | `ScalperParityTest.modelOutputsMatchThePythonStudy` |
| Fills, exits, fees | `ScalperEngine.onTrade` / `onClock` / `settle` | `ScalperEngineTest.restingBidFillsOnlyAfterTheQueueAndItsOwnSizeHaveTraded`, `stopSellsAtTheBidAndPaysTheTakerFee`, `buyNowStopAndTimeOutPayBothFees` |
| Strategies and cadence | `PaperScalper.decide` | `ScalperEngineTest.aSharpDropFiresTheDipHunterAndExtremeReversionOnThatSideAndMomentumOnTheOther`, `mlScalperPostsOnBothSidesEveryFiveSecondsFromTheRealBidAndQueue` |
| Scoreboard | `ScalperLedger.apply` → `ScalperSummary.of` → `ScalperCard` | `ScalperEngineTest.scoreboardSplitsByStrategyAndByQueue` |
| Feed | `SignalHub.ingestTick` → `PaperScalper.onTrade` / `onClock`; `AppContainer.onMarketSettled` → `settle` | `ScalperEngineTest.otherCoinsAndBadPrintsAreIgnored` |

## Scalping study, 2026-10-09

**A scalp on this market pays only from the front of the queue.** Buying at the ask and selling at the bid lost 1.6–4.3¢ a round trip under every rule tried (67 cells over 46 days of quotes and 22 days of the full trade tape), and a LightGBM model could rank those scalps but not find a positive group. Resting in and resting out earned +0.18¢ per order at the front of the queue and lost 2.9¢ at the back; a model that picks calm stretches raised the front-of-queue figure to +0.44¢ (99% interval [+0.36, +0.52], 7 of 7 test days). Break-even is about 250 contracts ahead; the usual queue is 2,000–10,000. Write-up: [`docs/scalping-2026-10-09.md`](docs/scalping-2026-10-09.md); tools: `tools/research/scalp/`.

## 1.8.2 (Claude branch): Clear lead, Dirk's rule

The app now bets the way Bitcoin is going, once it is clearly ahead. When Bitcoin is 0.10–0.20% from the window's start price between minute 3 and minute 10, the Bitcoin card says **BET UP** or **BET DOWN** on the side Bitcoin is on, and Buy opens a $5 all-in ticket on that side. Approve is still required and the $5 cap and daily cap are unchanged.

- **Why that band.** Over 4,264 settled windows (2026-08-24 → 10-08), betting the side Bitcoin is on won 75.7% of the time at an average 75.2¢, −0.6¢ per contract after the fee. The 0.10–0.20% band in minutes 3–10 came out ahead in both halves: +2.0¢ per contract [+0.0, +3.9] in the first 23 days and +0.9¢ [−1.3, +2.8] in the last 23, about +8¢ per $5 bet overall. A lead, not proof. Write-up: [`docs/clear-lead-2026-10-09.md`](docs/clear-lead-2026-10-09.md); tool: `tools/research/clear_lead_study.py`.
- **Nothing else gates it.** The rule does not use the model, so the sit-out switch does not silence it. Its tickets are 60–95¢ buys, so the $10 min-profit-if-win setting (which allows nothing above 31¢) does not apply to its side. Every other ticket keeps its gates.
- **Paper record.** Home card **Clear lead (your rule) · PAPER RECORD** logs the first call in every window as a $5 buy at the ask and settles it, approved or not. About 1,000 settled windows are needed before the record means anything.
- **Switch.** Settings → **Clear lead rule**, with a reset for the record.

| Control | Code | Test |
|---------|------|------|
| The rule | `ClearLeadRule.signal` | `ClearLeadTest.distanceBandIsTenInclusiveToTwentyExclusive`, `timeBandIsMinuteThreeToMinuteTen` |
| Engine → card | `ScoringEngine.Score.clearLeadSide` → `MarketUiModel.withSignalScore` | `ClearLeadTest.engineScoreCarriesTheCallIntoTheMarketModel` |
| Headline and ticket | `BetCall.clearLeadCall` → `TicketBuilder.proposeClearLead` → `proposeManual` | `ClearLeadTest.headlineIsBetUpWithAnApprovableEightyCentTicket`, `theBuyButtonBuildsTheSameTicketAndTheOtherSideKeepsItsGate` |
| Not silenced by sit-out | `BetCall.decide` | `ClearLeadTest.sitOutDoesNotSilenceTheRuleButTheSettingDoes` |
| Paper record | `SignalHub.maybeClearLead` → `ClearLeadRule.decide` → `LateFavoriteLedger.record` | `ClearLeadTest.ledgerRecordsOncePerWindowAndSettles` |

## 1.8.0-mis.1 (Mis_bitcoin branch, Mis Bitcoin)

Fork of the Claude 1.8.0 build with a hardened model-promotion pipeline: the trainer now publishes a market-block bootstrap 90% P&L CI at the ask and `promotion_eligible` (>= 20 EV-at-ask trades AND CI excluding zero); `ModelActivation` refuses activation on `promotion_eligible: false` even when Brier/log-loss beat the market. Separate package `com.dirk.kalshiodds.mis`, its own model release tag `mis-bitcoin-edge-model`, retrains on every push to `Mis_bitcoin` that touches `ml/`.

## 1.3 (Claude branch, Bitcoin Claude)

**No way of betting this market came out positive.** A study of Kalshi's own public trade history (952 settled `KXBTC15M` windows, 33.3M trades, 2026-09-24 → 2026-10-04) found that buying at the ask lost 1.01¢ per contract (−2.1% of stake, 95% CI [−1.14, −0.87]), almost all of it the fee. Resting orders earned +0.29¢ per fill only at the front of the queue and lost 0.9–1.5¢ behind the 2,000–10,000 contracts that normally sit at the best bid. Full write-up: [`docs/tape-study-2026-10-04.md`](docs/tape-study-2026-10-04.md). 1.3 does not add a live strategy; it shows the cost, limits the damage, and paper-tests the one lead that turned up.

- **What this bet costs, on the REAL MONEY sheet.** Every live buy now shows the win rate it needs to break even (all-in cost per contract) next to the win rate the price implies, the fee as a share of the bet, and what past buyers at that price and with that much time left got in the study (for example, buyers at 20–30¢ lost 10.0% of their stake; with 10–15 min left buyers lost 5.2%). Display only: it never sizes, gates or places an order. `TakerCost` / `TakerCostTest`.
- **Daily live cap.** Live buys stop once the day's all-in total reaches the cap (default **$50**, Settings → Live Approve tickets, 0 = off). A buy that would pass it is not sent and the reason is shown. Sells and paper are never blocked. The count resets at midnight on the phone; the part of a resting order Kalshi confirms as cancelled is given back. `LiveDailyCap` / `LiveDailyCapTest`.
- **Trade-tape study tool.** `tools/research/tape_study.py` (`pull`, `report`, `makersim`, `hourly`, `streaks`) reproduces the write-up from public data, no API key.
- **Flow fade, paper only.** Signal: takers buying one side at least 3:1 over the last 30 s. Buying the other side at the ask looked good on the 11 days it was found on and **failed on the 12 days before** (774 bets, −2.9¢ per contract; all 23 days: −0.0¢), so it is retired. What the home card **Flow fade (resting bid) · PAPER** runs instead is a resting no-fee bid on the side takers are not buying, counted as filled only after the queue ahead has traded: +1.8¢ per fill with 2,000 contracts ahead and +1.0¢ with 5,000 over 23 days, both with a 95% interval that includes zero. Not proven; paper only; needs Live signals on. Write-up: [`docs/flow-fade-2026-10-04.md`](docs/flow-fade-2026-10-04.md); tool: `tools/research/flow_study.py`. `FlowFadeRule` / `MakerPaperBook` / `FlowMakerTest`.
- **Update from inside the app.** Settings → **App update** shows this build's number, checks GitHub for a newer `Mis_bitcoin` build when Settings opens or on tap, and Download hands the APK to the browser. Every CI APK is signed with the same committed debug key, so it installs over the old one and keeps settings and the Kalshi key. `AppUpdate` / `AppUpdateTest`.

| Control | Code | Test |
|---------|------|------|
| Cost lines on the confirm sheet | `TakerCost.of` → `TakerCostLines` | `TakerCostTest` |
| Daily cap before a live buy | `OddsViewModel.approveLiveWithinCap` → `LiveDailyCap.blockReason` | `LiveDailyCapTest.buysFitUntilTheCapThenBlockWithAReason` |
| Cap slider | `LiveDailyCapSetting` → `LiveDailyCapStore.setCapUsd` | `LiveDailyCapTest.storePersistsAndRollsOverAtMidnight` |
| Cancel gives the cap back | `OddsViewModel.cancelWorkingOrder` → `LiveDailyCap.cancelledCostOf` | `LiveDailyCapTest.cancelReleasesOnlyWhatKalshiReportedCancelled` |
| Flow-fade resting paper bid | `SignalHub.maybeFlowFade` → `FlowFadeRule.order` → `MakerPaperBook.post`; fill: `SignalHub.ingestTick` → `MakerPaperBook.onTrade` → `LateFavoriteLedger.record` | `FlowMakerTest` |
| Check for update | `AppUpdateSetting` → `AppUpdateChecker.check` → `AppUpdate.decide` | `AppUpdateTest` |

## 0.3.15

- **Sticky "Window closed" after rollover.** 0.3.14 kept MANUAL / SELL tickets from a closed 15-minute window forever (`preservedManuals` / `voidTickers`) and set `lastError = Window closed` on every refresh. Once a Buy/Sell card was open across a rollover, the error stuck on the fresh window and the dead card never left. A closed window now shows a one-time notice (`That window closed. Nothing was sent.`), voids the ticket so Approve cannot place, then drops it after ~5s / the next refresh and clears `lastError`. `lastOrderError` is recorded once per voided ticket. An in-flight Submitting order is left alone.
- **Buy / Paper UP / Paper DOWN after rollover.** Taps resolve the current live contract via `MarketLifecycle.resolveActionWindow` (`currentOpenWindow` / `resolveLive`). If there is no open window, the UI shows `Next window loading` — never `Window closed` or `Market closed` on a live window.

## 0.3.14

- **Full scorecard empty-state contradiction.** The 0.3.13 screen showed `Picked side: 12/18 correct` and `settled 18` while the bottom read `No settled samples yet`. The empty-state was gated on `ScorecardMetrics.Snapshot.perSeries.isEmpty()` (and the Paparazzi fixture left `perSeries` empty while stuffing honest / window counts). Calibration-sample copy (`the first 20 outcomes unlock calibration`) was a third, unrelated collection. Every count, empty state, and section now comes from `ScorecardCopy` → `ScorecardMetrics.settledScoredPicks` + the same paper P&L as the home line. `No settled picks yet` appears only when that settled list is empty (`ScorecardCopyTest`, `KnownIssuesRegressionTest.fullScorecardEmptyStateUsesSettledPicksNotPerSeriesOrCalibration`).
- **Cleaner layout.** Top summary (W-L, win rate, paper P&L, settled count), 4-hour ET time-of-day buckets, then recent settled picks collapsed by default. Per-coin breakdown is gone (Bitcoin-only). Removed duplicate Today / 7-day / all-time cards, per-series, Brier / policy / adapter / guardrail / Extended-AI debug banners, and fabricated bucket P&L. Empty buckets render `—`, never `0%`. Green/red stay Kalshi UP/DOWN only; P&L and win rate are neutral `+/−` text.
- **Bitcoin-only (KXBTC15M).** Home shows the BTC card plus This window. SOL / ETH cards, watch toggles, extra tickers, WS subscriptions, scoring, alerts, and new paper fills are off. Scorecard and signal history filter stored ETH/SOL rows (DB rows are not deleted). `homeKeeps0312Layout` asserts exactly one BTC card.
- **Rollover cannot leave BTC stuck.** A failed resolve (empty listing or 429) stays in `retrying` and keeps polling — including mid-window and after close-time polling would have ended. `Retry-After` is respected even when longer than the 10s backoff cap. `successor` accepts a re-found open market when `previous == null` even if `close == lastCloseMs`. `btcCardRecoversFromFailedResolveMidWindow` reproduces the 0.3.13 S24 “Next window loading” stuck state and proves recovery.
- **This window sit-out copy.** Home never shows Brier / log-loss. Sit-out reads `NO BET this window. The model hasn't beaten Kalshi's prices in testing, and this bet's expected value is negative.`
- **Home tiles: $10 profit + larger AI %.** Each UP / DOWN tile shows net profit if the owner bought that side with $10 at the live best ask (`contracts×ask + ceil_cent(0.07×C×P×(1−P)) ≤ $10`, profit = `C×$1 − cost`). Missing ask → `$10 wins —`. Display only — live Approve stays $5 all-in. AI % is headline-sized, still green/red and summing to 100. Paparazzi covers 360dp + fontScale 1.3.
- **Paper UP / Paper DOWN always on the Bitcoin card.** 0.3.13 hid paper Buy once a Kalshi key was saved: keyed Approve became LIVE $ and the only Paper control lived on the ticket confirm sheet, so LIVE $ showed only Buy anyway / LIVE $ and Sell. Both modes now show green **Paper UP** and red **Paper DOWN**. Tap logs a $10 paper fill at the live ask (same math as the tile line) into `PaperBook` — never a real order or portfolio GET. Snackbar confirms contracts / cost / profit-if-win. Open fill reads `Paper: UP 14 @ 70c`. Settles with the window and feeds paper P&L on the home line and scorecard.

## 0.3.11

- **Paper never blocks Live.** `ApproveRouter.Intent.Live` + live positions (`GET /portfolio/positions`). A paper fill on ticker X cannot stop a live order on X (`LiveApprovePaperIsolationTest`). Keyed Approve is Live even when the Paper toggle is ON (`ApproveRouterTest.paperOnWithKeyIsLiveNotPaper`); the Paper button is the only paper path.
- **$5 all-in live size** at the final order-build step (`LiveOrderSizer` + `KalshiTradeClient.enforceLiveCap`). Default **Min profit if win $10**. Tickets below that stay disabled. A leftover $50 win-target cannot resize a LIVE order above $5. Sizing tests at 1¢, 5¢, 10¢, 25¢, 31¢, 50¢, 63¢.
- **Scorecard:** hits and Brier both use 0–1 probability (`ForecastUnits`). 0/5 with Brier 0.003 was a percent-vs-unit mix.
- **UI:** BET UP / BET DOWN / NO BET from the same ticket decision. PAPER vs LIVE $ on buttons. Live confirm sheet says **REAL MONEY** with count, price, fee, total cost, profit if win. Settings opens with **Kalshi API key** first: status is `Live trading ready` / `Key saved - tap Test connection` / `No key - live orders disabled`. Home banner **Add your Kalshi API key to place real bets** (only when no key) opens Settings scrolled to that section. Version `DipHunter v<BuildConfig.VERSION_NAME> (<VERSION_CODE>)` at the top of Settings and the bottom of the home screen.
- Reuses 0.3.10 (PR #20): PEM paste, Test connection, Last order error, consistent quotes, keyed Approve → Live, toolbar insets.

## 0.3.10

- **Live Approve after API key:** Paper trading ON (default) no longer reroutes a keyed Live Approve into the paper book. Paper fills stay on the Paper button. Manual Buy is a fee-inclusive $5 (Settings stake) GTC limit — not resized to the $50 win target.
- **PEM paste:** PKCS#1 `BEGIN RSA PRIVATE KEY` and PKCS#8 `BEGIN PRIVATE KEY` accept CRLF, whitespace, one-line pastes, and missing newlines. Key ID alone is rejected on-screen (`CredentialWriteGuard.REJECT_KEY_ONLY`).
- **Settings → Test connection:** signed `GET /trade-api/v2/portfolio/balance` shows cash or the exact Kalshi body (401 `INCORRECT_API_KEY_SIGNATURE`, clock skew, missing PEM). Copyable Last order error panel.
- **4xx/5xx verbatim:** create-order failures keep the Kalshi JSON on the ticket, snackbar, SQLite ticket attempt, and Settings last-error.
- **Consistent live book:** WS ticker is YES-only; NO bid/ask are derived from the same update (`yes_bid = 1 − no_ask`). Stops the 0.3.9 mixed book (UP 55/63 + DOWN 37/50).

## 0.3.9

- **Chart after kill/reopen:** Bid + spot ticks persist in SQLite (`chart_ticks`, DB v5), trimmed to active/recent 15m windows. A new process restores the current window and gap-fills from Kalshi `GET /series/{series_ticker}/markets/{ticker}/candlesticks` (`start_ts` / `end_ts` Unix seconds, `period_interval=1`, `*_dollars` FixedPointDollars). Spot backfill uses the existing Coinbase 1m candles.
- **One quote source:** Header, chart labels, buttons, and payout multiple all read [MarketQuoteView] from the live book. 0¢ / missing asks render as **—** / **Buy UP|DOWN**.
- **Payout multiple:** `C × $1 / (C×P + orderFee)` at the $5 ticket (`C = floor(stake / P)`). 1¢ @ $5 → **93.46x** (not 99x). Fee is paid on top of the buy. Never divides by zero.

| Control | Code | Test |
|---------|------|------|
| Chart tick persist | `AsyncResultsWriter.enqueueChartTick` | `ChartWindowRestoreTest` |
| Chart restore after process death | `ChartWindowService.restoreWindow` | `ChartWindowRestoreTest.persistAndRestoreTicksAcrossNewRepository` |
| Candlestick backfill | `LiveWindowBackfill.candles` | `ChartWindowRestoreTest.parseDocAccurateCandlestickSample` |
| Header / labels / buttons | `MarketQuoteView.of` | `MarketQuoteViewTest` + `ChartWindowRestoreTest.labelsHeaderButtonsShareOneQuoteSource` |
| Payout multiple | `KalshiQuoteDisplay.multiplier` + `KalshiFee.payoutMultiple` | `MarketQuoteViewTest.oneCentAtFiveDollarsIsHandComputed93_46x` |

## 0.3.8

- **Paper Buy fix:** Paper mode Approve / Paper tap fills the paper book with no Kalshi key. Win-target size above paper cash is capped, not blocked. Failures show a reason on the card.
- Weekly edge-model retrain (`edge-model-latest`) + one-tap **Get latest model** (activate only if holdout beats the market; rollback kept).
- Auto-tune edge threshold from settled history; sit out when the model loses to the market (manual override in Settings).
- Optional Supabase sync of History / bets / signals / settings (never the Kalshi key).
- Notifications when a Long-shot or $50 win-target card appears (Approve still required).
- Adaptive launcher icon + per-coin / 4-hour ET scorecard breakdowns.

## Stable debug signing (0.3.7)

Sideloaded 0.3.5 / 0.3.6 debug APKs were signed with **different** Android Debug certs (one per cloud VM). Android then refused in-place update, uninstall wiped EncryptedSharedPreferences + SQLite, and the Kalshi API key had to be re-entered.

`apksigner verify --print-certs` on the GitHub release assets:

| Release | Signer DN | SHA-256 |
|---------|-----------|---------|
| v0.3.5-debug | `C=US, O=Android, CN=Android Debug` | `983e5822da123c66c2425980974778c33ee9fadcef8965f90a67a3440bfab1cf` |
| v0.3.6-debug | `C=US, O=Android, CN=Android Debug` | `6613054f5eae9cab41bce2ac2c57b23601898b35b62feb591d18b18647dd287b` |
| 0.3.7+ (`app/signing/diphunter-debug.jks`) | `CN=DipHunter Debug, OU=Sideload, O=Dirk` | `64e2a43a6897c4556a36b82ea31dc89550c65e56b053658e1438bdf3c4cc4608` |

All **0.3.7+** debug APKs use that committed JKS (debug-only, private sideload). `./gradlew :app:assembleDebug` / `:app:verifyDebugCert` fail if the APK cert does not match. Override via `DIPHUNTER_DEBUG_*` Gradle properties or env/GitHub secrets. Back up the key from **Data → Back up credentials** (passphrase + SAF) before any uninstall. Encrypted credential prefs are excluded from Android Auto Backup.

**0.3.7** puts UP (YES) and DOWN (NO) best bid/ask — in cents, high contrast — on every live market, replaces the sparkline with a two-line Canvas chart (tap for full-screen scrub), and adds a **Data** screen: streamed CSV/JSON import, Kalshi settled-window backfill (WorkManager, resumable), Coinbase spot backfill, optional Supabase restore, and imported logistic weights from `python3 ml/train_edge.py`. A volatility digital-option fair value sits on each card; the imported model blends with the market and only flags an edge past fees + a confidence margin. Scorecard shows model vs market Brier and a “not enough data yet” state under 100 settled signals. Still approve-gated; no unsupervised auto-bets; Heavy ML stays throttled. **0.3.6** stops Live Approve from showing a page-level `No ask to size a limit on <ticker>` when a rolling 15m window has just closed (or one side has no sellers). Expired/closed markets drop or move hunter/manual tickets to the current live window; a missing ask is a disabled ticket card (`Market closed` / `No sellers on YES right now`). Asks come from documented `*_dollars` fields (including deci-cent `"0.0060"`), the opposite-side bid, and the WS book. Approve is still the only path that hits Kalshi; paper stays isolated. **0.3.5** retires the Kalshi **v1 create-order fallback** that produced HTTP 410 `deprecated_v1_order_endpoint` on Live Approve, and adds a visible **paper book** ($100 start / $5 AI fills) on the home screen. **0.3.3 fixes two live 0.3.2 bugs:** (1) light-mode / Extended AI `ConcurrentModificationException` from iterating a live order-book TreeMap (and unsynchronized flicker / flow maps) while WS deltas mutated them — fail-soft snapshots + thread-safe structures; OverlayThrottle and the UP/DOWN hero stay. (2) inverted NO/DOWN recommendations when spot was hundreds of dollars **above** the 15m target and climbing — `delta = fair − mid` was a fade-the-expensive-YES rule that ignored `sign(spot − strike)`. Direction now locks to YES=UP / NO=DOWN for Kalshi crypto 15m “price up?” markets. **0.3.2** added latest-wins overlay throttle + live UP/DOWN hero. **0.3.1 stops remaining mid-session crashes after 0.3.0 Heavy ML** (book-delta scoring flood, tick-thread DataStore rewrites, unsynchronized ensemble, confirmed 256MB `OutOfMemoryError` on Galaxy S24 Ultra SM-S928U) and **persists results to SQLite + `results.log` + CSV export**. Default is **light mode** (0.2.x blend). One OOM immediately persists Heavy ML off; other failures auto-disable after 3. **0.3.0 added on-device heavy ML** (sequence CNN/LSTM, GBM, ensemble, uncertainty gate, continual calibration, policy-eval scorecard) **plus extended AI 10–19**. **0.2.4 stops mid-session crashes** from the 0.2.3 keep-alive path (shared TFLite, live order-book races, specialUse FGS). **0.2.3 keeps live odds alive in the background.** **0.2.2 added approve-gated limit tickets.** There is no unsupervised auto-bet, no background auto-fire, and no order without an in-app **Approve**. The RL sizer is **advisory only**. **Not financial advice. High variance — you can lose the full stake.**

## Closed 15m windows + ask parsing (v0.3.6)

**Bug (user screenshot, 0.3.5):** Live Approve showed a red page-level

`No ask to size a limit on KXBTC15M-26SEP241645-45`

at `Updated 4:45:40 PM` while Recent signals still listed that ticker (AI 95% vs mkt 98%, Lean DOWN / NO).

**Cause:** `KXBTC15M-26SEP241645-45` is the 4:45 PM EDT 15-minute window. At 4:45:40 that contract is past `close_time`. Kalshi GET `/markets?status=open` then lists the next window (`KXBTC15M-26SEP241700-…`, `status=active`). The stale hero/card survived the last REST/cache snapshot; Buy called `TicketBuilder.proposeManual`, `bestAsk` was empty, and `OddsViewModel.buyMarket` `failSoft`’d a generic section error. Manual tickets were also preserved across `replaceProposals` after the ticker left the live set.

**Fix:** `close_time` / `status` (`active` vs `closed`/`determined`/`finalized`) prune and remap tickets to the current live 15m window. Closed markets never size a Kalshi order — the card reads **Market closed**. Open markets parse `yes_ask_dollars` / `no_ask_dollars` (and `yes_ask_size_fp`); `"0.0000"` / `"1.0000"` are not quotes; a one-sided book still implies the ask as `1 − opposite bid` or from the WS book. If that side really has no sellers, the ticket itself says **No sellers on YES right now** and Approve stays off. No more page-level “No ask to size” banner.

**Positions + sell:** Home shows **Your positions** from signed GET `/portfolio/positions` (`market_positions`, `position_fp` >0 YES / <0 NO). Sell opens the same approve-gated V2 GTC path (`side=ask` to sell YES, `side=bid` to sell NO, documented `reduce_only`, never v1 `action` / `/portfolio/orders`). Limit defaults to the best bid; count is capped at held. No bid → **No buyers right now**, Approve off. Buying the opposite side of a hold shows “This will close N of your UP/DOWN shares.” Paper fills have a labeled **Paper sell**. Still approve-gated; paper never hits the order API.

## V2 Live Approve + paper book (v0.3.5)

**Bug (user screenshot, 0.3.4):** tapping Approve on a hunter ticket (`KXBTC15M-26SEP241445-45` YES @ 1¢) showed

`HTTP 410 — {"error":{"code":"deprecated_v1_order_endpoint","message":"Please switch to the V2 endpoints",…}}`

**Cause:** `KalshiTradeClient.createLimit` posted V2 `POST /portfolio/events/orders` first, then on HTTP 404 fell back to legacy **`POST /portfolio/orders`**. Kalshi retired that v1 write (changelog: returns “Please switch to the V2 endpoints”). The 410 body in the screenshot is that fallback — not a successful V2 ack.

**Fix:** Live Approve is **V2 only**. Create `POST /trade-api/v2/portfolio/events/orders` with documented fields (`ticker`, `side` = `bid`/`ask`, `count`, `price`, `time_in_force`, `self_trade_prevention_type`, `client_order_id`). Cancel `DELETE /trade-api/v2/portfolio/events/orders/{order_id}`. Primary host `external-api.kalshi.com`; elections host is V2 fallback only. **`/portfolio/orders` is never called.** 410/404 surface a readable error; the pending card stays so you can retry. Still approve-gated — no unsupervised auto-bets. Auth (Key ID + PEM signing) is unchanged (`timestamp + METHOD + /trade-api/v2/portfolio/events/orders`).

**Paper trading:** 0.3.4 only had an advisory “bankroll” for suggested size — no paper ledger, so AI/LiveCall never recorded simulated fills. Home now shows a **PAPER BOOK** card (start/reset **$100**, **$5** per AI hunter / LiveCall fill). Paper never hits the Kalshi order API. **Paper $5** vs **Live Approve** are labeled separately.

**Checklist contrast:** Pre-trade checklist rows and lower market stats (Spread / Volume / OI / 24h / Liquidity / Time left) used `MaterialTheme.colorScheme.onSurface` on a hardcoded dark card. With a light system theme that is near-black on black — the “No info” screenshot. Those rows now use Material3 `onSurface` / `onSurfaceVariant` against `colorScheme.surface`, with a WCAG-ish fallback so text cannot disappear. Missing stats render as **—**.

## Light-mode CME + direction lock (v0.3.3)

- **CME:** Scoring no longer holds a live `LocalOrderBook`. Imbalance / depth / pulse are copied under `TickBook`’s lock (`BookView`). `LocalOrderBook` mutators and iterators are synchronized. `ExtendedAiRuntime.evaluate` is synchronized and wrapped in `SafeMl` (`label=extended`); flicker uses `ConcurrentHashMap`; rival-flow centroids are synchronized. The banner `Light mode: ConcurrentModificationException: extended` was this race (exception message is null, so the guard used the `"extended"` label).
- **Direction:** For KXBTC/ETH/SOL 15m “price up?” contracts, YES=UP and NO=DOWN. If spot is above the Kalshi `floor_strike` (or parsed target) and rising, the app leans **UP / YES** even when market YES is already expensive. Displayed UP/DOWN percentages are clamped to the same side of 50% as that recommendation.

## Persistence + crash harden (v0.3.1)

Results now survive a process kill:

- **SQLite** (`diphunter_results.db`): scored snapshots, alerts, scorecard / policy-eval rows, Approve-ticket attempts (advisory; never unsupervised).
- **`results.log`**: rolling append-only text in app files.
- **Export results** (Settings or Scorecard): CSV under app Downloads / MediaStore `Download/DipHunter`.
- Cold start reloads recent SQLite rows into Recent signals / Saved results.

**What was killing 0.3.0 mid-session**

Confirmed on a Galaxy S24 Ultra (`SM-S928U`) at 2026-09-23 20:11:27.432-0400: `java.lang.OutOfMemoryError` allocating 32 bytes with ~1.79MB free, **heap growth limit 256MB**, victim thread `CancellableContinuationImpl` / EventLoop / DefaultExecutor. Historical (already partly 0.2.4): `LocalOrderBook` CME, `ForegroundServiceDidNotStopInTimeException`, `ForegroundServiceStartNotAllowedException`.

1. Every Kalshi **order-book delta** launched a scoring coroutine and ran the full Heavy ML + Extended AI stack (CNN/LSTM, 3× MC-dropout, GBM, 48-path Monte Carlo) and then rewrote the entire prediction-log DataStore JSON. Deltas arrive tens of times per second → allocation storm → **OOM** → process death. Book mutations apply on the WS thread; scoring is **latest-wins** (one drain coroutine, not one-per-delta) and throttled to ≥400ms per ticker. DataStore writes are single-in-flight on IO; SQLite/text persist even if DataStore is skipped.
2. Sequence / news / ensemble allocations were unbounded. Caps now: 12 tickers × 80 raw frames, reused CNN/LSTM scratch, RSS ≤48KB / 12 titles, 16 path-sim paths, no stored backbone/tabular, no 3× MC-dropout. Heap ≥80% skips Heavy ML for that tick; ≥90% or any `OutOfMemoryError` latches light mode and persists via SharedPreferences (not DataStore).
3. **`HeavyMlRuntime` was not synchronized.** WS ticks and REST refresh shared `heads` / `stack` / a growing `lastActivation` map → `ConcurrentModificationException` and native-adjacent corruption.
4. A single infer/OOM/TFLite failure could escape. Infer is now fail-soft to the **0.2.x MLP blend**. One OOM persists Heavy ML off across process restarts; other failures trip after 3 (or a recent crash breadcrumb mentioning OOM/TFLite).
5. Compose overlaid every score immediately — book floods hit the main thread. Overlay is a 250ms latest-wins throttle (apply now if the interval elapsed, else one trailing apply). Debounce reset on every WS delta and never fired.

`largeHeap` was **not** added — the 256MB limit is cut by bounding allocations, not by asking for a bigger heap.

**Light mode:** Settings → Light mode (default for new installs and a one-time 0.3.1 reset). Turn Heavy ML back on if the phone can take it; that also clears the persisted OOM latch.

## Heavy ML (v0.3.0)

Analysis + existing approve-gated tickets only. Heavier models use more CPU/battery — turn them off in Settings to force the 0.2.x MLP blend.

1. **Sequence model.** Temporal CNN + TinyLSTM over the last 1–5 min of mid, size, imbalance, aggressor flow, and spot (30 × 10s bins). The 8-feature MLP stays in the stack.
2. **GBM second opinion.** Pure-Kotlin tree ensemble on 16 tabular features, stacked with the neural outputs.
3. **Continual fine-tune + calibration.** Settlement replay updates the YES last layer and ensemble weights. Platt + isotonic per series × TTE regime. Cold start is identity.
4. **Multi-task heads.** P(YES/NO), time-to-move, mid vol, P(fill at limit) — shown on cards and the checklist.
5. **Cross-market backbone.** Shared encoder + BTC/ETH/SOL series embeddings for lead–lag transfer.
6. **Teacher distill.** `python3 ml/train_heavy.py` exports `heavy_ml_student.json` (and optional `diphunter_seq.tflite`). See `ml/DISTILL.md`. Phone stays on the student.
7. **Uncertainty gate.** Ensemble variance or MC-dropout proxy. Alerts and tickets (when gates are on) require uncertainty ≤ Settings threshold.
8. **Microstructure embeddings.** 8→4 autoencoder over book snapshots, fed into GBM / scorer.
9. **Policy eval.** Scorecard counterfactual: simulated ROI / Brier if every alert were taken at stake X (default $5). Not live P&L.
10. **Regime classifier.** Softmax session / weekend / news-shock tags; reweights the ensemble per regime (does not mutate persisted stack weights).
11. **Anomaly / spoof detector.** Cancel storms, quote stuffing, fake depth — downrank or block.
12. **Survival / hazard.** P(YES wins | time left, path); votes into fair after ≥8 ticks.
13. **RL sizer (advisory).** Softmax stake fraction from settlements. Shown on cards / tickets. **Never places an order.** Respects $5 default / $25 hard cap. Ticket stake stays the configured Settings value.
14. **News / social pulse.** 16-d hash embed + bull/bear lexicon for BTC/ETH/SOL. Fail-soft RSS cache.
15. **Rival-flow clustering.** Online centroids (mixed / chase / smart-like); boosts when flow aligns.
16. **Bayesian MM shadow.** Latent fair + inventory from the book; ensemble voter.
17. **Conformal prediction sets.** Coverage-guaranteed {YES}, {NO}, or {YES,NO}. Hide when ambiguous and the bag is ready.
18. **Meta-labeling.** Secondary take/skip on top of the primary side. Cold start always takes.
19. **Synthetic path simulator.** 16×10 Monte Carlo mids; P(edge survives to expiry). Blocks when that probability is <35% and history is warm.

Cold start: if the sequence window is short and the stack has not been fine-tuned, scoring is the **0.2.x blend** (MLP + microstructure). Extended-AI fair voters join only after ≥8 ticks. Conformal / meta never skip while cold. New models drop in as history arrives.

### Settings knobs (v0.3.0)

| Knob | Default | Role |
|------|---------|------|
| Light mode | on (0.3.1) | Heavy ML + Extended AI off — safer on device |
| Heavy ML | **off** | Master switch. Off = 0.2.x blend. One OOM persists off; other failures after 3 |
| Sequence model | on | Temporal CNN / TinyLSTM |
| GBM second opinion | on | Tabular booster |
| Continual fine-tune | on | Last-layer + regime cal from settlements |
| Uncertainty gate | on | Block alerts / tickets when ensemble disagrees |
| Max uncertainty | 0.12 | Stddev in probability units |
| Policy-eval stake | $5 | Scorecard counterfactual size |
| Extended AI | **off** | Master switch for capabilities 10–19 |
| Regime classifier | on | Session / weekend / news-shock reweight |
| Anomaly / spoof gate | on | Block on cancel-storm / stuffing / fake depth |
| Survival / hazard | on | P(YES \| TTE, path) voter |
| RL sizer | on | Advisory stake only — never auto-bets |
| News pulse | on | Cached headline prior; fail-soft offline |
| Rival-flow clustering | on | Smart-like flow boost |
| Bayesian MM shadow | on | Book-implied fair voter |
| Conformal sets | on | Skip when {YES,NO} and bag is ready |
| Meta-label take/skip | on | Precision gate after primary side |
| Path simulator | on | Monte Carlo P(edge survives) |

Heavier models may increase battery and CPU. Disable Heavy ML, Extended AI, or individual pieces if the phone runs hot.

## Background live odds (v0.2.3 / crash-hardened 0.2.4)

Android was treating Dip Hunter as a normal Activity: leaving the app or turning the screen off let the process die, which tore down the WebSocket and scoring loop. Live signals now own a persistent foreground service.

**How to keep it alive**

1. Turn **Live signals** on (home screen card or Settings). Leave it on.
2. Allow the ongoing **“DipHunter live signals”** notification. Do not swipe it away or deny notification permission.
3. Optional on aggressive OEMs: Settings → **Allow background** → set Dip Hunter battery usage to **Unrestricted**.

The service restarts after process death (`START_STICKY`), after swipe-from-recents (`stopWithTask=false` + `onTaskRemoved`), after reboot / app update, and via a 15-minute WorkManager watchdog. Trading is unchanged: tickets still need an in-app **Approve**.

## Approve-gated tickets (v0.2.2)

Human **Approve** is required for **that** ticket before any order is sent.

1. **Stake.** Default **$5 USD**. Settings may lower it. Raising above $5 requires typing `RAISE`. Hard cap **$25** so this cannot become a large auto-bot.
2. **Payout gate.** A ticket is proposed only when max settlement payout for the proposed stake is **≥ $100**.
   - Kalshi binaries settle at **$1.00** per winning contract.
   - `contracts = floor(stakeUsd / conservativeLimitPrice)`
   - `maxSettlementPayout = contracts × $1.00`
   - Propose iff `maxSettlementPayout ≥ $100`
   - At the $5 default that means a conservative limit **≤ $0.05** (5¢) **and** enough visible size at/under that price. If the ask is too high or the book is too thin, no ticket.
3. **Quality gates (default ON).** Skip filter, not muted, and alerts not paused by streak/drawdown. Settings can disable these for tickets only.
4. **Limit only.** GTC limit at the conservative ask. Never a market order. Ticket shows side, ticker, size, estimated fill, max payout, net EV when available.
5. **Auth.** Uses the Key ID + PEM already in Settings (`EncryptedSharedPreferences`). PEM is never logged. Soft-fail with a clear error. Create: `POST /trade-api/v2/portfolio/events/orders` (V2 only — never `/portfolio/orders`). Cancel: `DELETE /trade-api/v2/portfolio/events/orders/{order_id}`.

## Decision support (v0.2.1)

Advisory layer on top of the v0.2.0 predictability stack. Still **never** calls Kalshi trade/order endpoints.

1. **Bankroll & size.** Settings: bankroll, Kelly fraction (default quarter-Kelly, 5% cap) or fixed-fraction. Each card shows **“N contracts max”** from edge, price, bankroll, liquidity, and spread. Advisory size is separate from the $5 ticket stake.
2. **Net EV after fees/spread.** Kalshi-style taker fee `feeRate × P × (1−P)` (default 7%, configurable) plus half-spread. Ranked opportunities and alerts use **net EV** when that toggle is on; raw edge still shows.
3. **Series / regime auto-mute.** Rolling 7-day hit rate below a Settings floor (default 40%, ≥8 samples) mutes that series, regime, or TTE window — no alerts, downranked, **Muted** chip. Disable in Settings.
4. **On-device adapter.** Settlements reweight blend channels (EMA) and fit a slope/intercept on `logit(p)` — not just temperature. Cold start is identity; weights persist in DataStore. No remote training.
5. **Streak / drawdown guard.** Pause alerts after N wrong in a row (default 4) or after a one-contract P&L-proxy drawdown (default $50). Banner: **alerts paused — streak guard**. Resume in Settings or on the next session.
6. **External spot features.** Public Binance (spot + 1m klines + USDT-M funding) with Coinbase REST fallback for BTC/ETH/SOL. Timeouts, cache, fail-soft. Market data only — no exchange orders.
7. **Pre-trade checklist.** Side, suggested size, net EV, confidence, regime, TTE, skip-filter. One-tap copy as plain text.

### Settings knobs (v0.2.1)

| Knob | Default | Role |
|------|---------|------|
| Bankroll (USD) | 1000 | Size suggestions only |
| Kelly / fixed fraction | quarter-Kelly 0.25 | Clip as % of bankroll |
| Max clip | 5% | Hard cap |
| Fee rate | 7% | Kalshi-style `P(1-P)` |
| Rank by net EV | on | Filter/rank after fees+spread |
| Auto-mute | on | Weak series/regimes |
| Hit-rate floor | 40% | Mute threshold |
| Streak N | 4 | Pause after N wrong |
| Drawdown $ | 50 | Pause on proxy DD |
| Resume on new session | on | Clear pause at process start |

## Predictability stack (v0.2.0)

End-to-end analysis upgrades on top of the v0.1.9 crypto WS pipeline. Still **never** calls trade endpoints.

1. **Live settlement feedback + recalibration.** Each watched market stores ticker/series, model P(YES), mid at signal time, predicted side, edge, confidence, regime, TTE bucket. On settle (YES/NO/void) the log is scored. After **20** outcomes a temperature scalar + reliability bins calibrate displayed P(YES) and edge; cold start uses the raw blend.
2. **Richer microstructure.** Aggressor/taker side (trade feed), depth near mid (3¢) vs depth decay (15¢), quote pulls and large cancel spikes from the local book. Folded into fair value with documented weights in `ScoringEngine`.
3. **Cross-asset lead–lag.** Time-aligned BTC ↔ ETH/SOL mids. BTC move over the last ~3s (minus 0.4s) vs follower move over the last 0.4s; reverse when the target is BTC.
4. **Time-to-expiry regimes.** Early window vs last ~3 minutes (`LATE`). Late blend shifts weight from AI/related/lead–lag to velocity, imbalance, depth, and cancels. Cards show `Early window` / `Last 3 min`.
5. **Confidence + skip filter.** Alerts and ranked opportunities require min confidence (default 45%), min liquidity (volume / OI / near-mid depth, default 500), and max spread (default 8¢). Weak/filtered edges are hidden when **Hide weak** is on (Settings).
6. **Regime tags.** Vol spike / quiet / trend / chop from recent mid vol + velocity. Shown on cards; they nudge blend weights and confidence.
7. **Scorecard.** Top-bar assessment icon → daily / rolling 7-day / all-time hit rate, Brier, avg edge when right vs wrong, per-series accuracy, sample count. Calibration status is on that screen.

### How to use the scorecard and new Settings

- Home → **scorecard icon** (or tap the scorecard hint under the HUD).
- **Settings → Skip filter:** min confidence, min liquidity, max spread, hide-weak toggle.
- Defaults live in `app/src/main/assets/default_signal_config.json`.

## Live signals (v0.1.9+)

Event-driven fair-value alerts on crypto ticks.

1. Create a Kalshi API key: [Account → API Keys](https://kalshi.com/account/profile) → Create Key. Save the **Key ID** and the **private key PEM** (RSA or Ed25519). The PEM cannot be downloaded again.
2. In the app: **Settings** → paste Key ID + PEM → **Save key**. The PEM is stored in `EncryptedSharedPreferences` and is **never logged**.
3. Enable **Notifications** (Android 13+ will prompt for `POST_NOTIFICATIONS`) and **Live signals (WS foreground)**.
4. The foreground service opens:

   `wss://external-api-ws.kalshi.com/trade-api/ws/v2`

   (fallback host: `wss://api.elections.kalshi.com/trade-api/ws/v2`)

   Handshake headers (required even for public `ticker`): `KALSHI-ACCESS-KEY`, `KALSHI-ACCESS-TIMESTAMP` (ms), `KALSHI-ACCESS-SIGNATURE` over `timestamp + "GET" + "/trade-api/ws/v2"` using RSA-PSS/SHA-256 or Ed25519.

5. The client subscribes to `ticker`, `orderbook_delta` (snapshot then incremental deltas), and optionally `trade` **only for watched crypto markets**.
6. If keys are missing: fast REST poll continues and the status chip reads **“WS needs API key — using REST”**. Local book imbalance is then unavailable and that blend weight is dropped.

**Notifications:** channel `diphunter_signal_alerts` (HIGH). Foreground ongoing: `diphunter_live_signals` (LOW). Target path is tick-receive → notify in under 1s while the service is running. Killed-app remote push would need FCM later; this release is **local instant alerts** only.

## What the app does

1. Fetches the open **Bitcoin** 15m market (`KXBTC15M` only).
2. Displays each market as a card (AI YES/NO, edge, bid/ask, volume, OI).
3. Probability rule: mid = `(yes_bid + yes_ask) / 2` else `last_price`.
4. Scoring engine (dedicated tick dispatcher):

   `fairValue = blend(ensemble(MLP, TCNN, LSTM, GBM), volume-flow+aggressor, related mid, velocity, imbalance, lead–lag, depth/decay, cancel/pull)`  
   then **calibrate** (global + per series/TTE) when enough settlements exist.  
   `delta = calibratedFair − marketMid` (percentage points)

   Alerts fire only when `|delta|` ≥ threshold, the skip filter passes, **and** uncertainty is below the Settings cap (10s debounce per ticker).

## Dip Hunter AI (TFLite)

On-device YES/NO via the 8-feature **TensorFlow Lite** MLP (`Input(8) → Dense(32) → Dense(16) → Dense(2, softmax)` = `[P(NO), P(YES)]`) **plus** the 0.3.0 sequence/GBM ensemble. Features: see `ml/FEATURES.md`. The historical trainer still includes settled WTI rows; **runtime inference and the live watchlist are crypto-only**.

**Edge hunting:** Dip Hunter edge (AI−market pp), ranked opportunities, stance text (Lean UP/YES or DOWN/NO — no orders). When spot vs strike is known, the recommended side matches that delta; a fade vs a rich YES mid cannot invert it.

## Open in Android Studio

1. Install Android Studio with SDK **35** and JDK 17+.
2. **File → Open** this folder.
3. Run the `app` configuration on API 26+.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/DipHunter-debug.apk
```

## API notes

- Public REST base: `https://api.elections.kalshi.com/trade-api/v2`
- Authenticated trade writes: `https://external-api.kalshi.com/trade-api/v2` (elections as V2-only fallback)
- WS: `wss://external-api-ws.kalshi.com/trade-api/ws/v2` (elections host as alternate)
- Public REST (no auth): `GET /markets?series_ticker=KXBTC15M&status=open`
- Authenticated create (after Live Approve): `POST /portfolio/events/orders` (V2 bid/ask, GTC limit). Cancel: `DELETE /portfolio/events/orders/{order_id}`. Never `POST /portfolio/orders`.
- Example tickers: `KXBTC15M-26SEP231600-00`, `KXETH15M-26SEP231645-45`, `KXSOL15M-26SEP231645-45`

## Project layout

```
app/src/main/java/com/dirk/kalshiodds/
  data/api/          Retrofit KalshiApi + authenticated KalshiTradeApi (create/cancel)
  data/dto/          MarketDto / MarketsResponse
  data/local/        DataStore MarketCache (btc/eth/sol/extra)
  data/repo/         MarketRepository (crypto series only)
  domain/            MarketUiModel, CryptoMarkets (allowlist / WTI reject)
  prediction/        DipHunterModel, FeatureVector, settlement scoring
  signal/
    config/          DataStore prefs + EncryptedSharedPreferences + default JSON
    ws/              KalshiWsAuth, KalshiWsClient, KalshiWsMessages
    engine/          ScoringEngine, TickBook, LocalOrderBook, MarketRegime, SkipFilter
    ml/              Sequence, TCNN, TinyLSTM, GBM, ensemble, uncertainty, fine-tune, policy eval
    feedback/        Calibrator, ScorecardMetrics, OnlineAdapter, Allowlist, Guardrails
    sizing/          PositionSizer, NetExpectedValue (advisory)
    trade/           PayoutGate, TicketBuilder, TicketSession (Live Approve)
    paper/           PaperBook ($100 / $5 AI fills, never Kalshi)
    external/        Public Binance/Coinbase spot · vol · funding
    checklist/       Pre-trade checklist + copy text
    notify/          SignalNotifier (HIGH alerts + ongoing FGS)
    service/         LiveSignalsService + keep-alive / boot / battery prompt
    model/           MarketTick, SignalAlert, WsConnectionState
    SignalHub.kt     tick dispatcher → UI + notifications
  ui/                OddsScreen, SettingsScreen, ScorecardScreen, DataScreen, ViewModels, MarketCard, BidChart
  worker/            MarketRefreshWorker (15 min) + BackfillWorker
  MainActivity.kt
  KalshiOddsApp.kt
  AppContainer.kt
app/src/main/assets/default_signal_config.json
app/src/main/assets/heavy_ml_student.json
ml/README.md             one-command edge trainer
ml/train_edge.py         walk-forward logistic → compact JSON
ml/DISTILL.md            teacher → student weight refresh
ml/train_heavy.py        export student JSON / optional TFLite
```

## Configuration (Settings + DataStore)

- Watch toggles: BTC 15m / ETH 15m / SOL 15m
- Extra **crypto** ticker list (non-crypto values are dropped)
- Edge threshold (pp), notifications on/off, Live signals on/off
- Skip filter: min confidence, min liquidity, max spread, hide weak opportunities
- Bankroll, Kelly / fixed-fraction, fee rate, net-EV ranking
- Auto-mute floor, streak / drawdown guard, resume
- Ticket stake ($5 default, $25 hard cap), quality gates for tickets
- Paper trading toggle (home-screen $100 book / $5 AI fills; default on)
- Heavy ML, sequence, GBM, uncertainty cap, continual fine-tune, policy-eval stake
- Extended AI master + regime / anomaly / survival / RL / news / flow / MM / conformal / meta / path-sim
- Kalshi API Key ID + private key PEM (secure storage; WS + Approve only)

Defaults live in `app/src/main/assets/default_signal_config.json` (`watchBtc/Eth/Sol: true`, threshold 5pp, live signals off, min confidence 45%, min liquidity 500, max spread 8¢).

## Secrets

- **Never** commit Kalshi private keys, PEM files, or API Key IDs.
- Do not embed secrets in BuildConfig.
- This app stores user-pasted keys on-device only.

## Requirements

| Item | Value |
|------|--------|
| minSdk | 26 |
| targetSdk / compileSdk | 35 |
| Kotlin | 2.0.21 |
| AGP | 8.7.2 |
| Compose BOM | 2024.10.01 |

## License / ownership

Built for Dirk Diggler. Prediction-market data © Kalshi; this app is an unofficial client.
