# Kashi 0.3.71: background stability

The reported symptom is closure after running in the background for a while. No device crash trace was available during this investigation; these changes address verified code risks, not a confirmed device-specific root cause.

- Cache V2 entry buckets and feedback instead of parsing persisted strings on every pending trade evaluation. Counts remain unlimited by default.
- Coalesce V2 entry/learning persistence on one delayed worker (1 second) using synchronous commit on that worker. Per-fill/per-close SharedPreferences.apply() calls formerly accumulated lifecycle work that Android may wait for on Main. Settings remain infrequent preference writes. Existing preference keys and learned data are preserved.
- Every completed trade still updates feedback immediately in memory. Disk commits batch the latest values; abrupt process death can lose the latest uncommitted second of feedback/counters. The SQLite trade ledger remains independently persisted. This is not model training or a profit guarantee.
- Skip dip-history grouping when the optional dip filter is disabled.
- Move session snapshot/database work out of ProcessLifecycleOwner.onStop and onto the application IO scope. Repair unclosed sessions from earlier processes on startup; the recovery timestamp is an upper bound and existing last-saved summary values are retained (lost final counts/P&L cannot be reconstructed exactly).
- Serialize concurrent scalp ledger flushes, conditionally remove queue entries, and retry failed writes. This prevents an older pending/open snapshot from overwriting a newer closed row. The writer never acquires the quote lock, avoiding inverted lock order when a quote triggers a flush.
- Record Android ApplicationExitInfo on the next startup (API 30+), off Main. More → Crash log can now show ANR, low-memory, native crash, signal, or user-stop reports that bypass Java's exception handler. Reports retain Android's actual reason/status and sampled PSS/RSS; signal kills are not relabeled as proven memory kills. ANR/native trace input is capped at 64 KiB (truncation is explicit); native tombstone protobuf bytes are retained as base64 text, historical reports are deduplicated and the existing 20-report cap applies. Nearby Java exception reports receive exit metadata instead of a duplicate report. Routine/ambiguous exits use the existing non-fatal telemetry marker. The dedup watermark lives outside the report directory so Clear does not resurrect historical exits.

Validation includes concurrent 20,000-entry traffic, per-trade feedback/reopen/dedup, bounded hourly counters, blocked concurrent ledger flush ordering/retry, bounded system-exit diagnostics, the full Android test suite, ML trainer tests and signed APK/certificate checks. A successful CI run cannot establish sustained behavior on the user's phone; an on-device background soak and the next crash/exit report are needed to verify the reported symptom is resolved.

References:
- Android SharedPreferences: https://developer.android.com/reference/android/content/SharedPreferences
- Android process exit reports: https://developer.android.com/reference/android/app/ApplicationExitInfo
