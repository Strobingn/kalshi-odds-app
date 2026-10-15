# ML / AI changes — 0.3.18 (2026-09-27)

Implements `docs/ml-review-2026-09-27.md` and the follow-up review. Every
change is mirrored in the backtest port (`tools/backtest/pipeline.py`) and was
checked on a fresh 28-day replay: **7,968 settled BTC/ETH/SOL 15m markets,
103,584 decision minutes**, in-sample 2026-08-31 → 09-18, out-of-sample
09-19 → 09-28. Fill = close ask, $5 all-in, Kalshi fees. Sanity and
settlement audits pass.

## Result, honestly

| All decision minutes | Log-loss | Brier |
|---|---:|---:|
| **Kalshi mid** | **0.4744** | **0.1588** |
| Blend, 0.3.10 logic | 0.4825 | 0.1606 |
| Blend, 0.3.18 | 0.4750 | 0.1590 |
| Digital Φ(d2), 16-bar σ → EWMA σ | 0.4819 → 0.4793 | |
| Market-offset edge model (OOS) | 0.4634 vs mid 0.4620 | gain CI [−0.0027, +0.0001] |

The blend's gap to the market shrank ~93%, but **nothing beats the mid**.
The fitted market-offset model does not either, so **no edge model ships in
the APK** and the gate refuses to activate one.

| App pick (alert-gated) | Bets | P&L | $/bet 95% CI |
|---|---:|---:|---|
| 0.3.10 logic, OOS | 1,281 | −$179.77 | [−0.33, +0.05] |
| 0.3.18, OOS | 167 | +$185.82 | [−1.19, +3.80] |
| 0.3.18, in-sample | 431 | −$122.33 | [−1.15, +0.79] |
| 0.3.18, OOS, worse-of-close/high fill | 167 | −$197.65 | [−2.39, +0.19] |

The app now alerts ~8× less and no longer buys the priced-right favorite by
default. There is **still no demonstrated edge**: the CIs include zero, the
in-sample half lost, and a worse fill turns it negative. Do not size up.

**Do not apply the "ask ≤ 31¢, profit ≥ $10" rule to the new pick.** Taken at
the first cheap minute without the EV alert gate, it lost $1,444 on 1,194 OOS
bets (CI below zero).

## What changed

1. **Trainer can no longer publish fake models.** `ml/train_edge.py` exits
   non-zero without enough real data instead of training on its synthetic
   fixture (which scored Brier 0.024 vs 0.186 and would have activated).
   Fixture output is flagged `"fixture": true`; the app refuses it.
2. **Edge model schema 2 — model the market's error.**
   `logit P = logit(mid) + b + w·z`; zero weights = the mid. Trained on every
   decision minute from the backtest cache (was ~360 rows / ~15 h), fit to
   convergence (Newton + L2). Activation needs lower OOS Brier *and* log-loss,
   ≥ 300 held-out markets and a market-clustered bootstrap CI of the log-loss
   gain above 0. Schema-1 models (incl. the current `edge-model-latest`
   release) no longer load. Time of day is UTC on both sides (was UTC hour in
   training vs New York minutes on the phone).
3. **"AI" MLP weight 0** (`W_AI`, `W_AI_LATE`). Trained on 1,120 BTC + WTI
   rows, inputs defined differently on the phone, validation leaked (random
   row split, 4 rows/market). Still shown, not blended.
4. **Related-crypto channel removed** (another coin's YES mid ≠ this market's).
5. **Blend nudges are log-odds nudges.** The net pp nudge is scaled by
   4·m·(1−m) and fair is bounded 0.5–99.5% (was a 2% floor). The raw version
   showed ~13% at a 5¢ market — fake longshot EV.
6. **Side = best net EV at the real ask** (`NetExpectedValue.pick`), the
   probability lean only when neither side is positive. Alerts need net EV ≥
   threshold (never |fair − mid|). Tickets use this side; the tape hero is
   only a fallback.
7. **DirectionSanity uses Φ(d2)** — halfway between blend and digital — not
   `max(fair, tanh(gap))`, which ignored time and σ (SOL 0.2% over strike,
   14 min: 87% vs ~70%). Best of the four lock variants tested.
8. **σ = EWMA** (half-life 10) of completed 1m returns over 60 bars
   (`DigitalOptionFairValue.sigmaFromCloses`). Beat the 16-bar std IS and
   OOS; inflating σ was worse.
9. **Spot**: Coinbase **USD** first (strikes are USD; trainer and backtest use
   it), Binance USDT only as fallback. Live ticker price instead of the
   lagging 1m candle; returns over exactly 60 s / 300 s; in-progress bars
   excluded. New `SpotStream` (Coinbase public WebSocket) overlays live price
   and returns on every score; it closes after 2 min unused.
10. **Calibration**: the log now stores the raw blend (`rawFairYes`,
    `rawFairEarly`); `Calibrator` fits that, per time bucket (EARLY / LATE),
    and cold buckets stay identity. The second Platt layer (OnlineAdapter
    slope) is no longer applied. Old log rows without raw values are ignored.
11. **OnlineAdapter** waits for 100 settlements (was 8), learns slower, and
    shrinks weights toward 1.0.

## Not done / still open

- Heavy ML / extended AI: still untrained and default-off. Record live book,
  taker-side trades and spot ticks for a few weeks before revisiting.
- The settlement-index averaging in the last minute is only modeled by the
  separate last-minute BRTI strategy, not the main scorer.
- Retrain the MLP on the backtest data with spot features (or drop it).
- Pre-existing test failure: `ClosedWindowPathAuditTest` compares paths with
  `/` and fails on Windows (`\`). Unrelated to these changes.

## Repro

```bash
python3 tools/backtest/fetch.py --days 28 --cache tools/backtest/cache
python3 ml/train_edge.py --cache tools/backtest/cache
python3 ml/test_train_edge.py && python3 tools/backtest/test_parity.py
./gradlew :app:testDebugUnitTest
```
