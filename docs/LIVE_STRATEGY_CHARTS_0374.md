# Kashi 0.3.74: individual live strategy windows

Tap a strategy row or an open position's “live chart” button. Its detail screen now starts with that strategy's live 15-minute contract window, rather than all-time summary cards. UP / YES and DOWN / NO have separate bid paths and markers. The horizontal axis always spans the full 15 minutes and the vertical axis is 0–100 cents. A dashed line shows the 50-cent buy ceiling.

- Hollow circles show the signal/order observation, triangles show actual buy fills, and squares show sell execution batches. Tap a marker to read its price, contract count and timestamp. Depth-walk execution batches use their actual weighted fill price.
- Every new sell batch, including partials, is recorded in the persisted trade note with time, quantity, price and fee. Notes survive the existing database and sync path; no destructive migration is needed. Settlements are labeled separately.
- Older trades without execution records display their historical average exit and explicitly disclose missing partial-fill times. Price gaps over 15 seconds break the path; missing history is blank.
- The current window's individual trade cards show signal, buy, partial sells, fees, realized/open P&L and protective exits. Window metrics contain only the selected strategy and ticker. All-time metrics and paged older trades follow below.
- The detail refreshes on an IO coroutine once per second only while the screen is started. Window reads use the existing ticker index and price paths are bounded to 360 points. All models share the existing recorded market quotes; no new market-data feed is opened per model.
- Follow live automatically rolls to the next BTC window. Selecting a recent window pins it until Follow live is chosen. Quotes show LIVE / STALE or Window complete.

Validation includes exact partial-sale times/quantities across restart, strategy/window/side isolation, persisted SQLite window reads, settlement versus sale markers, pending/no-fill behavior, and rendered live/empty strategy screens. The 50-cent ceiling, stops, unlimited V2 count options and trade feedback remain unchanged.
