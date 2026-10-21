# Cursor and Massive research audit — 2026-10-21

Source branch: `Cursor/chat-llm-60s-0a6b`, commit `c9389008`. This is a separate app with substantial differences; it was not merged wholesale. The V3 chart's single-market and invalid-sample handling adapts the ideas in its `chart/ChartSeriesBuilder.kt`.

The Cursor `research/scalp-0340/REPORT.md` describes an Oct 2–9 held-out test using a spot-index proxy, simulated next-book execution, modeled fees and finite depth. The reported derived fair-gap scalp averaged -0.40 cents per contract with a window-clustered 95% interval of -2.04 to +1.22 cents; the report also flags 5,004 variants and selection bias. These are claims from that report, not an independently reproduced result or evidence of an edge.

## Massive historical data check

The connected Massive account returned 1,440 `X:BTCUSD` one-minute aggregate bars for 2026-10-20 UTC (00:00 through 23:59), with a reported low of 81,080.64 USD, high of 83,470.00 USD and total reported volume of 9,905.86867319 BTC. These are external spot aggregates, not the CF settlement index and not Kalshi executable quotes. Bars are labeled by their start; a bar's close must never be used before the bar completes. This one-day check cannot validate a model.

The same account returned `NOT_ENTITLED` for the latest BTC/USD trade endpoint. No Massive key or raw market dataset was added to the app. To use these bars in a future experiment, join only fully completed bars to timestamped Kalshi quotes and actual outcomes, preserve source and capture timestamps, compare against the market with a chronological held-out test, and model fees and executable depth. Do not substitute them for settlement data.

## LLM defaults

Before this audit `chat-GTP` contained no LLM client/provider default. Cursor's `LlmSettings.kt` defaults to xAI and its provider list includes paid model IDs, so copying it unchanged would contradict the request for free defaults. The new manual paper-research chat adapts Cursor's OpenAI-compatible client: Groq is the initial selection and Mistral is optional; neither makes a request until the user saves a key and taps Ask. Free-tier access and charges depend on the provider account and can change. No automatic provider fallback or order action is enabled.
