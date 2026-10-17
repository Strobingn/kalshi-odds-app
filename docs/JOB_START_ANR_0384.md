# Kashi 0.3.84 — WorkManager onStartJob ANR (job 5040) + memory caps

Root cause: JobScheduler delivers onStartJob on the main thread. Even with MarketRefreshWorker on IO, the main
thread was still busy when the job started:
1. MarketRefreshWorker nudges LiveSignalsService; the service then posted the headless Autopilot host
   (OddsViewModel construction: paper book snapshot, ticket session, event wiring) to the main looper.
2. Application.onCreate did crash-log file I/O (mkdirs, breadcrumb append/rotate) and three NotificationManager
   binder calls on main before returning.
3. Under 412 MB peak / swap / LMK, those stalls (plus GC from unbounded per-ticker maps) exceeded the 16 s budget.

Fixes: Autopilot host built off main (main post only as fallback); onCreate disk/binder work moved to IO; periodic
work uses KEEP (MarketRefresh, Sync, watchdog) and the watchdog one-shot uses KEEP instead of REPLACE;
MarketRefreshWorker has an in-process single-flight guard and reuses the app's MarketRepository when built.
Memory: per-ticker in-memory maps (DecisionRuntime latest/lastWrite, SignalHub persist throttles, SettlementScorer
closeTimes, TickBook ts maps) capped via BoundedKeys (192, oldest windows evicted). No stored data touched.
Market list fetch was already paged (limit=10 per series, BTC/ETH/SOL only).
Gate: JobStartMainThreadGateTest.
