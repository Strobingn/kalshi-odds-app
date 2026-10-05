"""Shared helpers for the Kalshi daily high-temperature research tools.

Used by tools/research/weather_scan.py (live "decided-but-still-open" scanner)
and tools/research/weather_study.py (historical calibration). Stdlib only.

What lives here
  * HTTP + fixture clients for Kalshi (public trade-api v2), api.weather.gov and
    Open-Meteo (previous-runs forecast archive).
  * Series discovery (category "Climate and Weather" + seed fallback).
  * Series -> NWS station mapping read from the series' settlement_sources URL /
    the market's rules text (never a bare guess; the basis is reported).
  * Bucket parsing (yes_sub_title first, strike_type/floor/cap as fallback).
  * Climate-day windows (NWS climate day = local STANDARD time midnight..midnight,
    i.e. 01:00-01:00 local clock time while daylight saving is in effect).
  * Observation -> lower bound of the integer-degF CLI daily maximum.
  * NWS Daily Climate Report (CLI) text parsing.

API shapes assumed (public, unauthenticated):
  Kalshi  GET /series?category=Climate and Weather -> {"series":[{ticker,title,settlement_sources:[{name,url}],...}]}
          GET /series/{t}                         -> {"series":{...}}
          GET /events?series_ticker=&status=open&with_nested_markets=true -> {"events":[{event_ticker, markets:[...]}],"cursor"}
          GET /markets?series_ticker=&status=settled&min_settled_ts=&max_settled_ts= -> {"markets":[...],"cursor"}
          GET /historical/markets?series_ticker=   (markets older than /historical/cutoff)
          GET /series/{s}/markets/{t}/candlesticks?start_ts&end_ts&period_interval=60
          GET /historical/markets/{t}/candlesticks?...
          GET /markets/{t}/orderbook
  NWS     GET /stations/{id}                -> {"properties":{"timeZone","name"},"geometry":{"coordinates":[lon,lat]}}
          GET /stations/{id}/observations?start&end -> {"features":[{"properties":{timestamp,
                 temperature:{value(degC),qualityControl}, rawMessage}}]}
          GET /products/types/CLI/locations/{XXX} -> {"@graph":[{"id","issuanceTime"}]}
          GET /products/{id}                -> {"productText": "..."}
  Open-Meteo GET https://previous-runs-api.open-meteo.com/v1/forecast?latitude&longitude
          &hourly=temperature_2m_previous_day1,temperature_2m_previous_day2
          &temperature_unit=fahrenheit&timezone=GMT&start_date&end_date
          -> {"hourly":{"time":["YYYY-MM-DDTHH:MM",...], "<var>":[...]}}
"""
from __future__ import annotations

import json
import math
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "backtest"))

from pipeline import FEE_RATE, kalshi_total_cost  # noqa: E402,F401

KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
NWS = "https://api.weather.gov"
OPEN_METEO_PREV = "https://previous-runs-api.open-meteo.com/v1/forecast"
UA = "DipHunterWeatherResearch/0.1 (read-only research; +https://github.com/Strobingn/kalshi-odds-app)"
CATEGORY = "Climate and Weather"

# Fallback only: used when discovery returns nothing. Each is still verified via
# GET /series/{t} and its station is still read from settlement source / rules.
SEED_SERIES = ("KXHIGHNY", "KXHIGHCHI", "KXHIGHMIA", "KXHIGHAUS", "KXHIGHLAX", "KXHIGHDEN", "KXHIGHPHIL")

# Last-resort name -> station table, applied only to the market's own rules
# text (basis is reported as "rules-text name"). Not used when the settlement
# source URL names the station.
NAME_STATIONS = (
    ("central park", "KNYC"),
    ("midway", "KMDW"),
    ("o'hare", "KORD"),
    ("ohare", "KORD"),
    ("miami international", "KMIA"),
    ("austin bergstrom", "KAUS"),
    ("austin-bergstrom", "KAUS"),
    ("bergstrom", "KAUS"),
    ("los angeles international", "KLAX"),
    ("los angeles airport", "KLAX"),
    ("denver international", "KDEN"),
    ("philadelphia international", "KPHL"),
)

# Used only when api.weather.gov station metadata is unavailable.
FALLBACK_TZ = {
    "KNYC": "America/New_York", "KLGA": "America/New_York", "KJFK": "America/New_York",
    "KMDW": "America/Chicago", "KORD": "America/Chicago", "KMIA": "America/New_York",
    "KAUS": "America/Chicago", "KLAX": "America/Los_Angeles", "KDEN": "America/Denver",
    "KPHL": "America/New_York",
}
FALLBACK_STD_OFFSET_H = {"America/New_York": -5, "America/Chicago": -6, "America/Denver": -7,
                         "America/Los_Angeles": -8, "America/Phoenix": -7}
FALLBACK_LATLON = {
    "KNYC": (40.7789, -73.9692), "KMDW": (41.7842, -87.7553), "KMIA": (25.7881, -80.3169),
    "KAUS": (30.1831, -97.6799), "KLAX": (33.9381, -118.3889), "KDEN": (39.8466, -104.6562),
    "KPHL": (39.8733, -75.2268), "KORD": (41.9602, -87.9316),
}

MONTHS = {m: i + 1 for i, m in enumerate(
    ("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"))}


class ApiError(RuntimeError):
    pass


# --------------------------------------------------------------------------- HTTP


class Http:
    """Rate-limited JSON GET with retries/backoff (pattern of tools/backtest/fetch._get)."""

    def __init__(self, rps: float = 8.0, retries: int = 5, timeout: float = 30.0):
        self.min_gap = 1.0 / max(rps, 0.1)
        self.retries = retries
        self.timeout = timeout
        self.last = 0.0
        self.calls = 0

    def get(self, url: str, accept: str = "application/json", retries: int | None = None) -> object:
        tries = self.retries if retries is None else retries
        last: object = None
        for i in range(tries):
            wait = self.min_gap - (time.monotonic() - self.last)
            if wait > 0:
                time.sleep(wait)
            self.last = time.monotonic()
            self.calls += 1
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept})
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    return json.loads(resp.read().decode())
            except urllib.error.HTTPError as e:
                last = f"HTTP {e.code}"
                if e.code == 404:
                    return None
                back = 1.0 * (2 ** min(i, 4))
                if e.code == 429:
                    ra = e.headers.get("Retry-After")
                    try:
                        back = max(back, float(ra)) if ra else max(back, 2.0)
                    except ValueError:
                        pass
                if e.code in (429, 500, 502, 503, 504):
                    time.sleep(back)
                    continue
                raise ApiError(f"GET {url}: HTTP {e.code}") from e
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ConnectionError, OSError) as e:
                last = e
                time.sleep(1.0 * (2 ** min(i, 3)))
        raise ApiError(f"GET {url} failed: {last}")


class LiveClient:
    def __init__(self, rps: float = 8.0):
        self.http = Http(rps=rps)
        self.nws = Http(rps=4.0, retries=4)

    @property
    def calls(self) -> int:
        return self.http.calls + self.nws.calls

    # Kalshi ------------------------------------------------------------------
    def series_list(self, category: str = CATEGORY) -> list:
        body = self.http.get(f"{KALSHI}/series?{urllib.parse.urlencode({'category': category})}") or {}
        return body.get("series") or []

    def series(self, ticker: str) -> dict | None:
        body = self.http.get(f"{KALSHI}/series/{urllib.parse.quote(ticker)}")
        return (body or {}).get("series")

    def events(self, series: str, status: str = "open", max_pages: int = 10) -> list:
        out, cursor = [], None
        for _ in range(max_pages):
            q = {"series_ticker": series, "status": status, "with_nested_markets": "true", "limit": "200"}
            if cursor:
                q["cursor"] = cursor
            body = self.http.get(f"{KALSHI}/events?{urllib.parse.urlencode(q)}") or {}
            out.extend(body.get("events") or [])
            cursor = body.get("cursor") or None
            if not cursor:
                break
        return out

    def orderbook(self, ticker: str) -> dict | None:
        return self.http.get(f"{KALSHI}/markets/{urllib.parse.quote(ticker)}/orderbook")

    def cutoff(self) -> dict:
        try:
            return self.http.get(f"{KALSHI}/historical/cutoff", retries=2) or {}
        except ApiError:
            return {}

    def settled_markets(self, series: str, min_s: int, max_s: int, historical: bool = False,
                        max_pages: int = 50) -> list:
        out, cursor = [], None
        path = "/historical/markets" if historical else "/markets"
        for _ in range(max_pages):
            q = {"series_ticker": series, "limit": "1000" if not historical else "200"}
            if not historical:
                q.update(status="settled", min_settled_ts=str(min_s), max_settled_ts=str(max_s))
            if cursor:
                q["cursor"] = cursor
            body = self.http.get(f"{KALSHI}{path}?{urllib.parse.urlencode(q)}") or {}
            out.extend(body.get("markets") or [])
            cursor = body.get("cursor") or None
            if not cursor:
                break
        return out

    def candles(self, series: str, ticker: str, start_s: int, end_s: int, period: int = 60,
                historical: bool = False) -> list:
        q = urllib.parse.urlencode({"start_ts": start_s, "end_ts": end_s, "period_interval": period})
        if historical:
            url = f"{KALSHI}/historical/markets/{urllib.parse.quote(ticker)}/candlesticks?{q}"
        else:
            url = f"{KALSHI}/series/{series}/markets/{urllib.parse.quote(ticker)}/candlesticks?{q}"
        body = self.http.get(url) or {}
        return body.get("candlesticks") or []

    # NWS ---------------------------------------------------------------------
    def station(self, sid: str) -> dict | None:
        return self.nws.get(f"{NWS}/stations/{sid}", accept="application/geo+json")

    def observations(self, sid: str, start: datetime, end: datetime) -> list:
        q = urllib.parse.urlencode({"start": iso(start), "end": iso(end), "limit": "500"})
        body = self.nws.get(f"{NWS}/stations/{sid}/observations?{q}", accept="application/geo+json") or {}
        return body.get("features") or []

    def cli_products(self, issuedby: str, n: int = 3) -> list:
        body = self.nws.get(f"{NWS}/products/types/CLI/locations/{issuedby}", accept="application/ld+json") or {}
        graph = body.get("@graph") or []
        texts = []
        for item in graph[:n]:
            pid = item.get("id")
            if not pid:
                continue
            prod = self.nws.get(f"{NWS}/products/{pid}", accept="application/ld+json") or {}
            if prod.get("productText"):
                texts.append({"issuanceTime": prod.get("issuanceTime") or item.get("issuanceTime"),
                              "productText": prod["productText"]})
        return texts

    # Open-Meteo ----------------------------------------------------------------
    def previous_runs(self, lat: float, lon: float, start_date: str, end_date: str) -> dict:
        q = urllib.parse.urlencode({
            "latitude": f"{lat:.4f}", "longitude": f"{lon:.4f}",
            "hourly": "temperature_2m_previous_day1,temperature_2m_previous_day2",
            "temperature_unit": "fahrenheit", "timezone": "GMT",
            "start_date": start_date, "end_date": end_date,
        })
        return self.http.get(f"{OPEN_METEO_PREV}?{q}", retries=3) or {}


class FixtureClient:
    """Offline client reading JSON files from a fixture directory.

    Layout: series_list.json {"series":[...]}, series/<T>.json {"series":{}},
    events/<S>.json {"events":[...]}, orderbooks.json {ticker: body},
    stations/<ID>.json, observations/<ID>.json {"features":[...]},
    cli/<XXX>.json [{"issuanceTime","productText"}], settled/<S>.json {"markets":[...]},
    candles/<ticker>.json {"candlesticks":[...]}, openmeteo/<ID>.json, cutoff.json.
    Missing files behave like 404 (None / empty).
    """

    def __init__(self, directory: Path):
        self.dir = Path(directory)
        self.calls = 0

    def _load(self, rel: str):
        self.calls += 1
        p = self.dir / rel
        if not p.is_file():
            return None
        return json.loads(p.read_text())

    def series_list(self, category: str = CATEGORY) -> list:
        return (self._load("series_list.json") or {}).get("series") or []

    def series(self, ticker: str) -> dict | None:
        return (self._load(f"series/{ticker}.json") or {}).get("series")

    def events(self, series: str, status: str = "open", max_pages: int = 10) -> list:
        return (self._load(f"events/{series}.json") or {}).get("events") or []

    def orderbook(self, ticker: str) -> dict | None:
        return (self._load("orderbooks.json") or {}).get(ticker)

    def cutoff(self) -> dict:
        return self._load("cutoff.json") or {}

    def settled_markets(self, series: str, min_s: int, max_s: int, historical: bool = False,
                        max_pages: int = 50) -> list:
        if historical:
            return []
        return (self._load(f"settled/{series}.json") or {}).get("markets") or []

    def candles(self, series: str, ticker: str, start_s: int, end_s: int, period: int = 60,
                historical: bool = False) -> list:
        rows = (self._load(f"candles/{ticker}.json") or {}).get("candlesticks") or []
        return [c for c in rows if start_s <= int(c.get("end_period_ts") or 0) <= end_s]

    def station(self, sid: str) -> dict | None:
        return self._load(f"stations/{sid}.json")

    def observations(self, sid: str, start: datetime, end: datetime) -> list:
        return (self._load(f"observations/{sid}.json") or {}).get("features") or []

    def cli_products(self, issuedby: str, n: int = 3) -> list:
        return (self._load(f"cli/{issuedby}.json") or [])[:n]

    def previous_runs(self, lat: float, lon: float, start_date: str, end_date: str) -> dict:
        body = self._load("openmeteo/index.json") or {}
        key = body.get(f"{lat:.4f},{lon:.4f}")
        return (self._load(f"openmeteo/{key}") if key else None) or {}


# --------------------------------------------------------------------------- small utils


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_ts(raw) -> datetime | None:
    if not raw:
        return None
    try:
        dt = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def fnum(x) -> float | None:
    if x is None or x == "":
        return None
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


# --------------------------------------------------------------------------- discovery & stations


def is_high_temp_series(s: dict) -> bool:
    t = (s.get("ticker") or "").upper()
    title = (s.get("title") or "").lower()
    if t.startswith("KXLOW") or "lowest" in title or "low temp" in title:
        return False
    if t.startswith("KXHIGH"):
        return True
    return bool(re.search(r"\b(high(est)?|max(imum)?)\b.*\btemp", title))


def discover_series(client, log=lambda s: None) -> tuple[list, list]:
    """Returns (series dicts, notes). Discovery first; seeds verified only as a fallback."""
    notes, found = [], {}
    try:
        for s in client.series_list(CATEGORY):
            if is_high_temp_series(s) and s.get("ticker"):
                found[s["ticker"]] = s
        notes.append(f"discovery: GET /series?category={CATEGORY} -> {len(found)} high-temperature series")
    except Exception as e:  # noqa: BLE001
        notes.append(f"discovery failed ({e}); falling back to seed list")
    if not found:
        for t in SEED_SERIES:
            try:
                s = client.series(t)
            except Exception as e:  # noqa: BLE001
                notes.append(f"seed {t}: error {e}")
                continue
            if s:
                found[t] = s
            else:
                notes.append(f"seed {t}: not found (404)")
        notes.append(f"seed fallback -> {len(found)} series verified")
    else:
        missing = [t for t in SEED_SERIES if t not in found]
        if missing:
            notes.append("seed tickers not in discovery (not added): " + ", ".join(missing))
    return [found[k] for k in sorted(found)], notes


ISSUEDBY_RE = re.compile(r"issuedby=([A-Za-z0-9]{3,4})", re.I)
ICAO_RE = re.compile(r"\b(K[A-Z]{3})\b")


def station_for(series: dict, market: dict | None = None) -> dict:
    """{'station','cli','basis','source'} or station None. Never a bare guess."""
    srcs = series.get("settlement_sources") or []
    for src in srcs:
        url = (src or {}).get("url") or ""
        m = ISSUEDBY_RE.search(url)
        if m:
            cli = m.group(1).upper()
            st = cli if len(cli) == 4 else "K" + cli
            return dict(station=st, cli=cli[-3:] if len(cli) == 4 else cli,
                        basis=f"settlement source URL issuedby={cli}", source=url)
    texts = []
    if market:
        texts += [market.get("rules_primary") or "", market.get("rules_secondary") or ""]
    texts += [(s or {}).get("url") or "" for s in srcs] + [(s or {}).get("name") or "" for s in srcs]
    blob = " ".join(texts)
    m = ISSUEDBY_RE.search(blob)
    if m:
        cli = m.group(1).upper()
        st = cli if len(cli) == 4 else "K" + cli
        return dict(station=st, cli=st[1:], basis=f"rules text issuedby={cli}", source=excerpt(blob))
    m = ICAO_RE.search(blob)
    if m:
        st = m.group(1)
        return dict(station=st, cli=st[1:], basis=f"rules text station id {st}", source=excerpt(blob))
    low = blob.lower()
    for name, st in NAME_STATIONS:
        if name in low:
            return dict(station=st, cli=st[1:], basis=f"rules-text name '{name}' (name table)", source=excerpt(blob))
    return dict(station=None, cli=None, basis="not found in settlement source / rules", source=excerpt(blob))


def excerpt(s: str, n: int = 220) -> str:
    s = re.sub(r"\s+", " ", s or "").strip()
    return s if len(s) <= n else s[: n - 1] + "…"


class StationInfo:
    def __init__(self, sid: str, tz: str, lat: float | None, lon: float | None, name: str, basis: str):
        self.sid, self.tz, self.lat, self.lon, self.name, self.basis = sid, tz, lat, lon, name, basis
        self.cli = sid[1:] if sid.startswith("K") and len(sid) == 4 else None


def station_info(client, sid: str) -> StationInfo:
    try:
        body = client.station(sid) or {}
    except Exception:  # noqa: BLE001
        body = {}
    props = body.get("properties") or {}
    coords = ((body.get("geometry") or {}).get("coordinates") or [None, None])
    tz = props.get("timeZone")
    if tz:
        lon, lat = coords[0], coords[1]
        return StationInfo(sid, tz, lat, lon, props.get("name") or sid, "api.weather.gov station metadata")
    lat, lon = FALLBACK_LATLON.get(sid, (None, None))
    return StationInfo(sid, FALLBACK_TZ.get(sid, "America/New_York"), lat, lon, sid, "fallback table (NWS unavailable)")


# --------------------------------------------------------------------------- time / climate day


def _zone(tz: str):
    try:
        from zoneinfo import ZoneInfo
        return ZoneInfo(tz)
    except Exception:  # noqa: BLE001
        return None


def std_offset(tz: str, d: date) -> timedelta:
    """Local STANDARD-time UTC offset (no DST) for the zone."""
    z = _zone(tz)
    if z is not None:
        probe = datetime(d.year, d.month, d.day, 12, tzinfo=z)
        return probe.utcoffset() - (probe.dst() or timedelta(0))
    return timedelta(hours=FALLBACK_STD_OFFSET_H.get(tz, -5))


def local_offset(tz: str, when: datetime) -> timedelta:
    z = _zone(tz)
    if z is not None:
        return when.astimezone(z).utcoffset()
    return timedelta(hours=FALLBACK_STD_OFFSET_H.get(tz, -5))


def climate_window(d: date, tz: str) -> tuple[datetime, datetime]:
    """NWS climate day: 00:00-24:00 local standard time, as UTC datetimes."""
    start = datetime(d.year, d.month, d.day, tzinfo=timezone.utc) - std_offset(tz, d)
    return start, start + timedelta(days=1)


def local_clock(d: date, hour: int, tz: str) -> datetime:
    """UTC instant of local *civil* clock time `hour`:00 on day d."""
    z = _zone(tz)
    if z is not None:
        return datetime(d.year, d.month, d.day, hour, tzinfo=z).astimezone(timezone.utc)
    return datetime(d.year, d.month, d.day, hour, tzinfo=timezone.utc) - std_offset(tz, d)


EVENT_DATE_RE = re.compile(r"-(\d{2})([A-Z]{3})(\d{2})(?:$|-)")


def event_date(ticker: str | None) -> date | None:
    """KXHIGHNY-25OCT05 -> 2025-10-05."""
    m = EVENT_DATE_RE.search((ticker or "").upper())
    if not m or m.group(2) not in MONTHS:
        return None
    try:
        return date(2000 + int(m.group(1)), MONTHS[m.group(2)], int(m.group(3)))
    except ValueError:
        return None


# --------------------------------------------------------------------------- buckets


SUB_BETWEEN = re.compile(r"(-?\d+)\s*°?\s*(?:F\s*)?(?:to|-|–)\s*(-?\d+)\s*°?")
SUB_BELOW = re.compile(r"(-?\d+)\s*°?\s*(?:F\s*)?or\s+(?:below|less|lower)", re.I)
SUB_ABOVE = re.compile(r"(-?\d+)\s*°?\s*(?:F\s*)?or\s+(?:above|more|higher)", re.I)
SUB_LT = re.compile(r"^\s*<\s*(-?\d+)")
SUB_GT = re.compile(r"^\s*>\s*(-?\d+)")


def _sub_interval(sub: str) -> tuple | None:
    s = (sub or "").strip()
    if not s:
        return None
    if (m := SUB_BELOW.search(s)):
        return (None, int(m.group(1)))
    if (m := SUB_ABOVE.search(s)):
        return (int(m.group(1)), None)
    if (m := SUB_LT.search(s)):
        return (None, int(m.group(1)) - 1)
    if (m := SUB_GT.search(s)):
        return (int(m.group(1)) + 1, None)
    if (m := SUB_BETWEEN.search(s)):
        a, b = int(m.group(1)), int(m.group(2))
        return (min(a, b), max(a, b))
    return None


def _strike_interval(m: dict) -> tuple | None:
    """Integer-degF interval from strike fields: between=[floor,cap] inclusive,
    greater=(floor, inf) strict, less=(-inf, cap) strict (Kalshi temperature convention, ASSUMED)."""
    st = (m.get("strike_type") or "").lower()
    fl, cp = fnum(m.get("floor_strike")), fnum(m.get("cap_strike"))
    if st == "between" and fl is not None and cp is not None:
        return (math.ceil(fl), math.floor(cp))
    if st in ("greater", "greater_or_equal") and fl is not None:
        return (math.floor(fl) + 1 if st == "greater" else math.ceil(fl), None)
    if st in ("less", "less_or_equal") and cp is not None:
        return (None, math.ceil(cp) - 1 if st == "less" else math.floor(cp))
    return None


def bucket_interval(m: dict) -> tuple:
    """(lo, hi, basis): integer degF interval of the CLI max that pays YES; lo/hi None = unbounded.
    yes_sub_title wins; strike fields are a cross-check. (None, None, reason) when unusable."""
    a = _sub_interval(m.get("yes_sub_title") or m.get("subtitle") or "")
    b = _strike_interval(m)
    if a and b and a != b:
        return (None, None, f"conflict: subtitle {a} vs strikes {b}")
    if a:
        return (a[0], a[1], "yes_sub_title" + (" (strikes agree)" if b else ""))
    if b:
        return (b[0], b[1], "strike fields (subtitle unparsed)")
    return (None, None, "unparsed")


def fmt_interval(lo, hi) -> str:
    if lo is None and hi is None:
        return "?"
    if lo is None:
        return f"≤{hi}°F"
    if hi is None:
        return f"≥{lo}°F"
    return f"{lo}–{hi}°F" if lo != hi else f"{lo}°F"


# --------------------------------------------------------------------------- observations


T_GROUP = re.compile(r"\bT([01])(\d{3})([01])(\d{3})\b")
MAX6_GROUP = re.compile(r"(?<=\s)1([01])(\d{3})(?=\s|$)")
BAD_QC = ("X", "Q", "B")


def c_to_f(c: float) -> float:
    return c * 1.8 + 32.0


def f_floor_from_c(c: float, precision_c: float) -> int:
    """Smallest integer degF consistent with a reading `c` +- precision (ASOS works in whole degF)."""
    return int(math.ceil(c_to_f(c - precision_c) - 1e-9))


def f_ceil_from_c(c: float, precision_c: float) -> int:
    return int(math.floor(c_to_f(c + precision_c) + 1e-9))


def _obs_reading(props: dict) -> tuple | None:
    """(degC, precision_degC, source) for one observation, or None."""
    raw = props.get("rawMessage") or ""
    m = T_GROUP.search(raw)
    if m:
        c = int(m.group(2)) / 10.0 * (-1 if m.group(1) == "1" else 1)
        return (c, 0.05, "METAR T-group")
    t = props.get("temperature") or {}
    if (t.get("qualityControl") or "") in BAD_QC:
        return None
    unit = t.get("unitCode") or "wmoUnit:degC"
    v = fnum(t.get("value"))
    if v is None:
        return None
    if unit.endswith("degF"):
        v = (v - 32.0) / 1.8
        return (v, 0.03, "value degF")
    if abs(v - round(v)) < 1e-9:
        return (v, 0.5, "whole degC (±0.5)")
    return (v, 0.1, "decimal degC (±0.1)")


def _max6(props: dict, ts: datetime) -> float | None:
    """6-hour max group (1sTTT, tenths degC) from synoptic-hour METAR remarks."""
    raw = props.get("rawMessage") or ""
    if "RMK" not in raw:
        return None
    near = min(abs((ts - ts.replace(hour=h, minute=0, second=0, microsecond=0)).total_seconds())
               for h in (0, 6, 12, 18))
    # also consider the next day's 00Z for 23:5x reports
    nxt = ts.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)
    near = min(near, abs((nxt - ts).total_seconds()))
    if near > 15 * 60:
        return None
    m = MAX6_GROUP.search(raw.split("RMK", 1)[1] + " ")
    if not m:
        return None
    return int(m.group(2)) / 10.0 * (-1 if m.group(1) == "1" else 1)


def obs_summary(features: list, win_start: datetime, win_end: datetime) -> dict:
    """Observed temperatures inside the climate-day window.

    cli_floor: smallest integer degF the CLI daily max can be, given the readings
      (each reading -> smallest whole degF consistent with it). A lower bound
      because hourly/5-min samples can miss the peak; CLI corrections and
      sensor/QC differences are NOT bounded (hence the scan's safety margin).
    est_hi: largest whole degF consistent with the highest reading (not a bound).
    """
    rows = []
    rejected = 0
    six = []
    for f in features or []:
        p = (f or {}).get("properties") or {}
        ts = parse_ts(p.get("timestamp"))
        if ts is None or not (win_start <= ts < win_end):
            continue
        r = _obs_reading(p)
        if r is None:
            rejected += 1
            continue
        rows.append((ts, r[0], r[1], r[2]))
        mx = _max6(p, ts)
        if mx is not None and ts - timedelta(hours=6) >= win_start - timedelta(minutes=10):
            six.append((ts, mx))
    out = dict(n=len(rows), rejected=rejected, six_hour_groups=len(six), cli_floor=None, est_hi=None,
               max_f=None, max_time=None, latest_time=None, latest_f=None, support=0, sources=[])
    if not rows and not six:
        return out
    floors = [(f_floor_from_c(c, pr), ts, c, pr) for ts, c, pr, _ in rows]
    floors += [(f_floor_from_c(c, 0.05), ts, c, 0.05) for ts, c in six]
    best = max(floors, key=lambda t: (t[0], -t[1].timestamp()))
    out["cli_floor"] = best[0]
    out["max_time"] = iso(best[1])
    out["max_f"] = round(c_to_f(best[2]), 1)
    out["est_hi"] = max(f_ceil_from_c(c, pr) for _, _, c, pr in floors)
    out["support"] = sum(1 for fl, *_ in floors if fl >= best[0])
    if rows:
        last = max(rows, key=lambda t: t[0])
        out["latest_time"] = iso(last[0])
        out["latest_f"] = round(c_to_f(last[1]), 1)
    out["sources"] = sorted({s for *_, s in rows})
    return out


# --------------------------------------------------------------------------- CLI (Daily Climate Report)


CLI_DATE_RE = re.compile(r"CLIMATE SUMMARY FOR\s+([A-Z]+)\s+(\d{1,2})\s+(\d{4})", re.I)
CLI_MAX_RE = re.compile(r"^\s*MAXIMUM\s+(-?\d+|MM)", re.M)


def parse_cli(text: str) -> dict | None:
    """{'date', 'max_f' (int|None), 'final' (bool)} from a CLI product, or None."""
    if not text:
        return None
    m = CLI_DATE_RE.search(text)
    if not m:
        return None
    mon = m.group(1).upper()[:3]
    if mon not in MONTHS:
        return None
    try:
        d = date(int(m.group(3)), MONTHS[mon], int(m.group(2)))
    except ValueError:
        return None
    up = text.upper()
    i = up.find("TEMPERATURE (F)")
    mx = CLI_MAX_RE.search(up, i if i >= 0 else 0)
    val = None
    if mx and mx.group(1) != "MM":
        val = int(mx.group(1))
    header = None
    if i >= 0:
        for line in up[i + len("TEMPERATURE (F)"):].splitlines():
            if line.strip():
                header = line.strip().split()[0]
                break
    valid_as_of = bool(re.search(r"VALID\s+(TODAY\s+)?AS\s+OF", up))
    # Final = the overnight report on the completed day ("YESTERDAY" block).
    final = header == "YESTERDAY" and not valid_as_of
    return dict(date=d, max_f=val, final=final, header=header)


# --------------------------------------------------------------------------- candles


def _px(obj, key: str) -> float | None:
    if not isinstance(obj, dict):
        return None
    v = fnum(obj.get(f"{key}_dollars"))
    if v is not None:
        return v
    v = fnum(obj.get(key))
    if v is None:
        return None
    return v / 100.0 if v > 1.0 else v


def candle_quote(c: dict) -> tuple:
    """(end_ts, yes_bid_close, yes_ask_close) in dollars; None where absent/placeholder."""
    end = int(c.get("end_period_ts") or 0)
    yb = _px(c.get("yes_bid"), "close")
    ya = _px(c.get("yes_ask"), "close")
    if yb is not None and not (0.0 < yb < 1.0):
        yb = None
    if ya is not None and not (0.0 < ya < 1.0):
        ya = None
    return end, yb, ya


def quote_at(candles: list, ts: int, max_stale_s: int = 6 * 3600) -> tuple:
    """Last candle with end <= ts (within max_stale_s): (yes_bid, yes_ask)."""
    best = None
    for c in candles:
        end, yb, ya = candle_quote(c)
        if end <= ts and end >= ts - max_stale_s and (best is None or end > best[0]):
            best = (end, yb, ya)
    return (best[1], best[2]) if best else (None, None)


def per_contract_cost(price: float, contracts: int = 10, rate: float = FEE_RATE) -> float:
    """All-in $/contract buying `contracts` at `price` (exact Kalshi taker fee rounding)."""
    return kalshi_total_cost(contracts, price, rate) / contracts
