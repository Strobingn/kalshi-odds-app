# V2 paper scalpers — 0.3.69

Source: `kashi` at `8fb2701`. `main` does not contain V2. Original strategies, strategy IDs, the Kashi package and signing identity are preserved. Existing paper history is retained.

## Exit rules

Every V2 position has a protective stop from entry. Missing saved stops now resolve through the original variant, including explicit tweak stops. A stop wider than a cheap contract's purchase price is clamped to a reachable 1¢ bid. Stops trigger sales; price gaps, absent quotes or insufficient depth can cause larger losses or leave a remainder for official settlement.

At 60 seconds remaining, start exiting unless all of these hold: book age ≤3 seconds and no sequence gap; CF tick age ≤2 seconds; CF-implied probability for the held side ≥97%; executable bid ≥90¢; selling now would be profitable after both fees; and estimated settlement value exceeds the bid less its exit fee by over 1¢. The hold exception is re-evaluated on every quote. Protective and trailing stops take priority. This is an uncalibrated model-based hypothesis, not advance knowledge of the winner. Without CF data, exit. No new V2 entries fill in the final minute.

Exit decisions latch before simulated execution. Fills require at least 250 ms and a book timestamp later than the decision and last partial fill. Repeated callbacks on an identical book cannot repeatedly consume that book's size. Each fill respects displayed size and fees; x5/x10 copies retain depth walking. This still assumes that a later updated book's displayed depth is executable; it is not an exchange queue simulator. Peak bid, arming and last fill book timestamp are saved in the existing trade note, so restart does not reset profit protection. Existing note/row persistence uses the app's existing batched write pipeline; an abrupt kill before that batch commits can lose the newest update.

## Adaptive paper experiment

This is an on-device deterministic statistical controller, not an LLM. It uses each model's own saved results after fees. The quote path reads cached per-window aggregates; those refresh when the existing background writer flushes. No database history scan runs per quote.

Use the latest 24 completed windows available in the store's 48-window cache. Current/future windows and nonfinite values are excluded. Preserve base entry/stop/trail settings unless there are ≥30 closed trades across ≥3 windows, negative total net dollars, and a negative mean window dollars/trade plus two estimated standard errors. Equal window weighting reduces the influence of repeated correlated bets in one contract. This heuristic does not provide a formal confidence guarantee and ignores multiple-strategy selection effects.

Only for that sustained-loss case, test an entry cap 2¢ lower, a stop 20% smaller (never widened; 4¢ floor unless its original stop is already smaller), a trail capped at 2¢, and profit-trail arming capped at 4¢ net/contract. This is a bounded trial, not evidence the new settings improve return. No size escalation or repeated risk ratchet. Every new trade records keep/trial, evidence counts and exact parameters; its parameters remain frozen while open. As rolling evidence changes, subsequent trades can return to base settings. Historical and new trades remain mixed in existing all-time totals; use the trade notes and entry dates when assessing the update.

No phone trade ledger or historical depth replay was supplied with the repository. No individual strategy has been declared profitable from repository code alone. Paper forward observations must establish whether the trial helps. Original strategies and live-order routes are unchanged.

## Verification

`ScalpV2AdaptiveTest` covers positive/inconclusive preservation, loss thresholds, completed-window isolation, correct fallback stops, reachable cheap-contract stops, CF hold revocation, delayed/partial fills, duplicate-book callbacks, process restart, missing exit liquidity, and stale/gapped/late entries. Existing release gates continue to cover V2 catalog identities and unmodified base specs. Run the full Android unit suite and APK/signature build in GitHub Actions; local Gradle dependency download is blocked in this environment.
