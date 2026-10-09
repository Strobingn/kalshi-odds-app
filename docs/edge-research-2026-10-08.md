# Edge research — 2026-10-08 (7 days, 2,712 settled markets)

Data: live Kalshi KXBTC/KXETH/KXSOL 15m markets (Oct 1–8), 1,342 scored
markets, 17,446 decision minutes, Coinbase 1m spot (10,139 bars/coin).

## Baseline (taker at the ask, $5 all-in)

| Strategy | OOS n | OOS PnL | Win rate |
|---|---|---|---|
| app_shipped (5pp gate) | 369 | −$130.80 | 74.5% |
| cheap side | 774 | −$621.85 | 22.9% |
| always favorite | 777 | −$191.96 | 60.4% |
| random side | 777 | −$144.30 | 51.1% |

Win rates track prices within ~1pp in every 10¢ bucket. The loss is the
~1¢ spread + fee per fill, not bad picks.

## Model diagnostics

- Model fair vs market mid (all minutes): Brier 0.15936 vs 0.15742,
  log-loss 0.48079 vs 0.47203. The blended "fair" is mid + tanh noise —
  worse than the market in every TTE bucket.
- Spot-based digital fair (Black–Scholes on Coinbase price + 15m realized
  vol): Brier 0.16173 vs market 0.15742 — worse in every TTE bucket.
- Optimal blend `w·digital + (1−w)·mid`: best w = 0.00 in every bucket.
  The spot information is fully priced in. No blend weight beats the mid.

## Hypothesis battery (taker, first qualifying minute, $5)

- Momentum fade (3¢/3m reversal): negative in every TTE bucket
  (−$0.16 to −$1.03 per bet).
- Cheapest side ≤10¢ inside 5m: n=3,080, −$0.86 per bet.
- 8pp net-edge gate: the only non-negative taker config
  (OOS +$2.42, n=7, 86% wr) — shipped in PR #60.

## Maker entries (resting bid, conservative fill model)

- Endgame maker on the likely-winner side (join bid −1 tick, fill only if
  a later candle trades through the price):
  - 2–4m left, entry 80–99¢: IS −$0.0004/bet (n=1,250),
    OOS +$0.037/bet (n=1,755). The only positive cell, but the IS/OOS
    asymmetry means it is not proven robust.
  - All other TTE × price cells: negative.

## Conclusions

1. This market is efficient at the 15m horizon for every signal family we
   can compute from public candles + spot. There is no detectable taker edge.
2. The reliable "way to win" is structural, not predictive: stop paying the
   spread (maker entries) and only bet when the modeled edge clears the
   full cost by a wide margin (8pp gate). Both shipped in PR #60.
3. Any real edge must come from information faster than 1-minute candles
   (WebSocket trade tape, sub-second spot) — the app already records WS
   ticks; a future study on tick-level data is the next step if one is
   wanted.
4. The honest expectation for the shipped config: roughly breakeven to
   slightly positive with far fewer bets, instead of a guaranteed grind
   down. Paper-trade it and let the scorecard/auto-tuner (30+ settled
   samples) decide when the model has earned live size.

## Reproduction

```
cd tools/backtest
python3 run.py --days 7          # fetch + simulate + report
```
