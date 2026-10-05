#!/usr/bin/env python3
"""No-network tests for ws_recorder (auth, parsing) and ws_lag (planted lag is recovered)."""
from __future__ import annotations

import base64
import gzip
import random
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ws_lag as lag  # noqa: E402
import ws_recorder as rec  # noqa: E402


def _rsa():
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                          serialization.NoEncryption()).decode()
    return k, pem


def test_signature_verifies_with_pss_sha256() -> None:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    k, pem = _rsa()
    key = rec.load_key(pem)
    h = rec.handshake_headers("kid", key, now_ms=1_790_000_000_123)
    assert h["KALSHI-ACCESS-KEY"] == "kid" and h["KALSHI-ACCESS-TIMESTAMP"] == "1790000000123"
    sig = base64.b64decode(h["KALSHI-ACCESS-SIGNATURE"])
    k.public_key().verify(sig, b"1790000000123GET/trade-api/ws/v2",
                          padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=padding.PSS.DIGEST_LENGTH),
                          hashes.SHA256())


def test_pem_survives_lost_newlines_and_literal_backslash_n() -> None:
    _, pem = _rsa()
    flat = pem.replace("\n", "")
    escaped = pem.replace("\n", "\\n")
    for variant in (pem, flat, escaped, "  " + pem + "\n\n"):
        assert rec.load_key(variant) is not None
    try:
        rec.normalize_pem("not a key")
        raise AssertionError("expected ValueError")
    except ValueError:
        pass


def test_parse_kalshi_dollars_and_cents() -> None:
    r = rec.parse_kalshi('{"type":"ticker","msg":{"market_ticker":"KXBTC15M-X","yes_bid_dollars":"0.4500","yes_ask":47,"price":46,"ts":1790000000}}', 5)
    assert r == [5, "k", "KXBTC15M-X", 0.45, 0.47, 0.46, 1790000000000], r
    t = rec.parse_kalshi('{"type":"trade","msg":{"market_ticker":"K","yes_price_dollars":"0.5100","count_fp":"3.00","taker_side":"NO","ts_ms":7}}', 6)
    assert t == [6, "kt", "K", 0.51, 3.0, "no", 7], t
    assert rec.parse_kalshi('{"type":"subscribed","msg":{}}', 1) is None
    assert rec.parse_kalshi("not json", 1) is None


def test_parse_coinbase() -> None:
    r = rec.parse_coinbase('{"type":"ticker","product_id":"BTC-USD","price":"84000.5","best_bid":"84000.4","best_ask":"84000.6","time":"2026-10-05T14:00:00.250000Z"}', 9)
    assert r[:6] == [9, "cb", "BTC-USD", 84000.5, 84000.4, 84000.6] and r[6] % 1000 == 250, r
    assert rec.parse_coinbase('{"type":"heartbeat"}', 1) is None


def _planted(delay_ms: int, seed: int = 4) -> list:
    """Coinbase updates every 50 ms with sharp jumps; Kalshi mid = same price shifted by `delay_ms`."""
    rng = random.Random(seed)
    t0_ms = 1_790_000_000_000
    price, prices = 84_000.0, []
    for i in range(0, 20 * 60 * 20):  # 20 minutes of 50 ms ticks
        if rng.random() < 0.004:
            price *= 1 + rng.choice([-1, 1]) * rng.uniform(0.0006, 0.0012)  # jump
        price *= 1 + rng.gauss(0, 0.00001)
        prices.append((t0_ms + i * 50, price))
    rows = [[ms * 1_000_000, "cb", "BTC-USD", str(p), "", ""] for ms, p in prices]
    times = [ms for ms, _ in prices]
    base = prices[0][1]
    last = None
    for ms, p in prices:
        # Kalshi quotes on a 100 ms cadence from the price `delay_ms` ago.
        if ms % 100:
            continue
        j = max(0, (ms - delay_ms - t0_ms) // 50)
        src = prices[int(j)][1]
        mid = round(0.5 + (src - base) / base * 25, 2)
        mid = min(0.9, max(0.1, mid))
        if mid != last:
            rows.append([ms * 1_000_000, "k", "KXBTC15M-T", str(mid - 0.005), str(mid + 0.005), ""])
            last = mid
    rows.sort(key=lambda r: r[0])
    return rows


def test_planted_300ms_lag_is_recovered() -> None:
    r = lag.analyze(_planted(300))
    assert r["markets"] == 1
    assert 200 <= r["peak_ms"] <= 400, r["peak_ms"]
    d = r["delays"][3.0]
    assert len(d) >= 5, r["events"]
    med = lag.q(d, 0.5)
    assert 150 <= med <= 600, med


def test_no_lag_has_a_near_zero_peak_and_no_slow_responses() -> None:
    r = lag.analyze(_planted(0))
    assert -100 <= r["peak_ms"] <= 200, r["peak_ms"]
    d = r["delays"][3.0]
    assert not d or lag.q(d, 0.5) < 300
    assert r["led"][3.0] >= 0.8 * r["events"][3.0], r["led"]


def test_roundtrip_through_gz_files_and_report() -> None:
    rows = _planted(300)
    with tempfile.TemporaryDirectory() as d:
        s = rec.Sink(Path(d))
        for r in rows:
            s.add([r[0], r[1], r[2], float(r[3]) if r[3] else None, float(r[4]) if r[4] else None, None, None])
        s.flush()
        loaded = lag.load_rows(d)
        assert len(loaded) == len(rows)
        text = lag.report(lag.analyze(loaded))
        assert "Peak lag" in text and "event study" in text


def test_missing_key_is_a_clean_noop() -> None:
    import os
    os.environ.pop("KALSHI_KEY_ID", None)
    os.environ.pop("KALSHI_PRIVATE_KEY", None)
    assert rec.main(["--out", tempfile.mkdtemp(), "--minutes", "0"]) == 0


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
