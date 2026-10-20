# Phone recordings

Days the app recorded on the phone (DipHunter recorder export), in the same
format as the `scalper-data` release: `trades_`, `book_`, `spot_` (gzip,
the last member may be cut off) and `settle_` (plain CSV) per UTC day.

The Arena replay reads them with `source=phone` (or `both`):
Actions → "Arena replay (Claude)" → Run workflow.
