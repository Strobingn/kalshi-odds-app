"""Train / valid / test days for the scalper model (train_compact, queue_ml, export_app_model, gate_scalper).

Default: the original fixed split of the 2026-10-09 study (first 11 days train, next 4 valid, the rest test),
so the published numbers rebuild exactly. With SCALP_TEST_DAYS=n set (the weekly retrain), the split rolls
with the data: the last n days test, the SCALP_VALID_DAYS (default 4) before them valid, everything older train."""
import os


def split(days):
    days = sorted(days)
    n = os.environ.get("SCALP_TEST_DAYS")
    if not n:
        return set(days[:11]), set(days[11:15]), set(days[15:])
    n = int(n)
    v = int(os.environ.get("SCALP_VALID_DAYS", "4"))
    if len(days) < n + v + 5:
        raise SystemExit(f"only {len(days)} days of data: a rolling split needs at least {n + v + 5}")
    return set(days[:-(n + v)]), set(days[-(n + v):-n]), set(days[-n:])
