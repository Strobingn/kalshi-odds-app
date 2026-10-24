#!/usr/bin/env python3
"""Public-data BTC experiment. Simulated fills only; no trading credentials or POSTs."""
from __future__ import annotations

import argparse
import json
import math
import sqlite3
import statistics
import time
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from decimal import Decimal, ROUND_CEILING
from pathlib import Path

KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
COINBASE = "https://api.exchange.coinbase.com"
STRATEGY = "btc-market-anchor-v1"


@dataclass(frozen=True)
class Rules:
    initial_cash: float = 1000.0
    max_trade: float = 5.0
    cash_fraction: float = 0.005
    max_open_cost: float = 25.0
    daily_loss_limit: float = 20.0
    drawdown_limit: float = 100.0
    fee_rate: float = 0.07
    margin: float = 0.05
    anchor_weight: float = 0.25
    fill_delay: float = 1.0
    max_response_seconds: float = 1.0
    max_spot_age_seconds: float = 5.0
    earliest_elapsed_seconds: float = 180.0
    min_seconds_left: float = 120.0


def epoch(value: str) -> float:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def cents(value: Decimal) -> int:
    return int((value * 100).to_integral_value(rounding=ROUND_CEILING))


def cost(count: int, price: float, fee_rate: float = 0.07) -> tuple[int, int]:
    p = Decimal(str(price))
    if count < 1 or not Decimal("0") < p < Decimal("1"):
        raise ValueError("invalid count or price")
    fee = cents(Decimal(str(fee_rate)) * count * p * (1 - p))
    return cents(count * p) + fee, fee


def size(price: float, depth: float, cap_cents: int, fee_rate: float) -> int:
    if not math.isfinite(price) or not math.isfinite(depth) or not 0 < price < 1 or depth < 1:
        return 0
    count = min(int(depth), int(cap_cents / (price * 100)))
    while count > 0 and cost(count, price, fee_rate)[0] > cap_cents:
        count -= 1
    return count


def book_levels(payload: dict) -> dict[str, list[tuple[float, float]]]:
    fp = payload.get("orderbook_fp")
    raw = fp if fp is not None else payload.get("orderbook", {})
    result = {}
    for side in ("yes", "no"):
        values = raw.get(side + "_dollars")
        divisor = 1
        if values is None:
            values = raw.get(side, [])
            divisor = 100
        levels = [(float(p) / divisor, float(n)) for p, n in values]
        if any(not math.isfinite(p) or not math.isfinite(n) or not 0 <= p <= 1 or n < 0
               for p, n in levels):
            raise ValueError("invalid book level")
        result[side] = sorted((x for x in levels if x[1] > 0), reverse=True)
    if not result["yes"] or not result["no"]:
        raise ValueError("two-sided book unavailable")
    if result["yes"][0][0] + result["no"][0][0] > 1 + 1e-9:
        raise ValueError("crossed book")
    return result


def ask(book: dict, side: str) -> tuple[float, float]:
    opposite = book["no" if side == "YES" else "yes"]
    price = opposite[0][0]
    return round(1 - price, 6), sum(n for p, n in opposite if p == price)


def forecast(mid: float, spot: float, strike: float, seconds_left: float,
             candles: list, now: float, weight: float) -> float:
    # Only complete, contiguous minute bars. No settlement-minute look-ahead.
    closed = sorted((int(c[0]), float(c[4])) for c in candles if float(c[0]) + 60 <= now)
    closed = closed[-61:]
    if len(closed) < 61 or now - (closed[-1][0] + 60) > 120:
        raise ValueError("need 61 recent closed minute bars")
    if any(b[0] - a[0] != 60 for a, b in zip(closed, closed[1:])):
        raise ValueError("minute candle gap")
    values = (mid, spot, strike, seconds_left, weight, *(p for _, p in closed))
    if (not all(math.isfinite(v) for v in values) or not 0 <= mid <= 1 or
            not 0 <= weight <= 1 or min(spot, strike, *(p for _, p in closed)) <= 0 or seconds_left <= 0):
        raise ValueError("invalid spot, strike, or horizon")
    returns = [math.log(b[1] / a[1]) for a, b in zip(closed, closed[1:])]
    sigma_minute = statistics.stdev(returns)
    if sigma_minute <= 1e-8:
        raise ValueError("volatility unavailable")
    variance = sigma_minute ** 2 * seconds_left / 60
    z = (math.log(spot / strike) - variance / 2) / math.sqrt(variance)
    digital = 0.5 * (1 + math.erf(z / math.sqrt(2)))
    # Prespecified shrinkage toward market. This is a hypothesis, not fitted alpha.
    return min(0.999, max(0.001, mid + weight * (digital - mid)))


class Account:
    def __init__(self, path: Path, rules: Rules = Rules()):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.rules = rules
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS config (id INTEGER PRIMARY KEY CHECK(id=1), rules TEXT);
          CREATE TABLE IF NOT EXISTS observations
            (id INTEGER PRIMARY KEY, at REAL, kind TEXT, payload TEXT);
          CREATE TABLE IF NOT EXISTS trades
            (ticker TEXT PRIMARY KEY, side TEXT, count INTEGER, price REAL,
             cost_cents INTEGER, fee_cents INTEGER, opened REAL, closes REAL,
             probability REAL, outcome TEXT, settled REAL, pnl_cents INTEGER);
        """)
        serialized = json.dumps(asdict(rules), sort_keys=True)
        existing = self.db.execute("SELECT rules FROM config WHERE id=1").fetchone()
        if existing and existing[0] != serialized:
            raise ValueError("rules changed: use a new database for a new experiment")
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO config VALUES(1, ?)", (serialized,))

    def log(self, kind: str, payload: dict, at: float | None = None):
        with self.db:
            self.db.execute("INSERT INTO observations(at,kind,payload) VALUES(?,?,?)",
                            (time.time() if at is None else at, kind, json.dumps(payload, allow_nan=False)))

    def summary(self, now: float | None = None) -> dict:
        now = time.time() if now is None else now
        trades = list(self.db.execute("SELECT * FROM trades ORDER BY settled, opened"))
        initial = round(self.rules.initial_cash * 100)
        settled = [t for t in trades if t["outcome"] is not None]
        open_cost = sum(t["cost_cents"] for t in trades if t["outcome"] is None)
        pnl = sum(t["pnl_cents"] for t in settled)
        equity = peak = initial
        drawdown = 0
        for t in settled:
            equity += t["pnl_cents"]
            peak = max(peak, equity)
            drawdown = max(drawdown, peak - equity)
        day = datetime.fromtimestamp(now, timezone.utc).date()
        daily_pnl = sum(t["pnl_cents"] for t in settled
                        if datetime.fromtimestamp(t["settled"], timezone.utc).date() == day)
        return dict(strategy=STRATEGY, simulated=True, initial_cash=initial / 100,
                    cash=(initial + pnl - open_cost) / 100, open_cost=open_cost / 100,
                    realized_pnl=pnl / 100, settled=len(settled), open=len(trades)-len(settled),
                    wins=sum(t["pnl_cents"] > 0 for t in settled),
                    equity_at_cost=(initial + pnl) / 100, worst_drawdown=drawdown / 100,
                    current_drawdown=(peak - equity) / 100, daily_pnl=daily_pnl / 100,
                    fill_assumption="delayed visible-quote proxy; not a confirmed exchange fill")

    def capacity(self, now: float) -> int:
        s = self.summary(now)
        # Pending positions are treated as potential full losses for risk limits.
        if (-s["daily_pnl"] + s["open_cost"] >= self.rules.daily_loss_limit or
                s["current_drawdown"] + s["open_cost"] >= self.rules.drawdown_limit):
            return 0
        return max(0, math.floor(100 * min(
            self.rules.max_trade, s["cash"] * self.rules.cash_fraction,
            s["cash"], self.rules.max_open_cost - s["open_cost"],
            self.rules.daily_loss_limit + s["daily_pnl"] - s["open_cost"],
            self.rules.drawdown_limit - s["current_drawdown"] - s["open_cost"])))

    def seen(self, ticker: str) -> bool:
        return self.db.execute("SELECT 1 FROM trades WHERE ticker=?", (ticker,)).fetchone() is not None

    def fill(self, ticker: str, side: str, price: float, depth: float, probability: float,
             closes: float, now: float) -> bool:
        if (not ticker.startswith("KXBTC15M-") or side not in ("YES", "NO") or
                not math.isfinite(probability) or not 0 <= probability <= 1 or
                closes - now < self.rules.min_seconds_left):
            return False
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            if self.seen(ticker):
                return False
            n = size(price, depth, self.capacity(now), self.rules.fee_rate)
            if not n:
                return False
            debit, fee = cost(n, price, self.rules.fee_rate)
            chance = probability if side == "YES" else 1 - probability
            if chance - debit / (100 * n) <= self.rules.margin:
                return False
            self.db.execute("INSERT INTO trades VALUES(?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)",
                            (ticker, side, n, price, debit, fee, now, closes, probability))
        return True

    def settle(self, ticker: str, result: str, now: float) -> bool:
        if result not in ("yes", "no"):
            return False  # Never infer settlement from Coinbase or invent a result.
        with self.db:
            t = self.db.execute("SELECT * FROM trades WHERE ticker=?", (ticker,)).fetchone()
            if not t or t["outcome"] is not None or now < t["closes"]:
                return False
            payout = t["count"] * 100 if t["side"].lower() == result else 0
            self.db.execute("UPDATE trades SET outcome=?,settled=?,pnl_cents=? WHERE ticker=? AND outcome IS NULL",
                            (result, now, payout - t["cost_cents"], ticker))
        return True


class PublicFeed:
    def __init__(self, account: Account):
        self.account = account

    def get(self, url: str, timely: bool = True) -> dict | list:
        start = time.monotonic()
        request = urllib.request.Request(url, headers={"User-Agent": "DipHunterPaperLab/1.0", "Accept": "application/json"}, method="GET")
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.load(response)
        elapsed = time.monotonic() - start
        self.account.log("feed", {"url": url, "response_seconds": elapsed, "data": payload})
        if timely and elapsed > self.account.rules.max_response_seconds:
            raise ValueError("response too slow for a decision")
        return payload

    def book(self, ticker: str) -> dict:
        return book_levels(self.get(f"{KALSHI}/markets/{urllib.parse.quote(ticker, safe='')}/orderbook?depth=10"))


def cycle(account: Account, feed: PublicFeed):
    for trade in list(account.db.execute("SELECT ticker FROM trades WHERE outcome IS NULL")):
        payload = feed.get(f"{KALSHI}/markets/{trade['ticker']}", timely=False)
        market = payload["market"]
        if market.get("status") in ("settled", "finalized"):
            account.settle(trade["ticker"], market.get("result", ""), time.time())
    rules = account.rules
    if not account.capacity(time.time()):
        account.log("skip", {"reason": "risk budget exhausted"})
        return
    markets = feed.get(f"{KALSHI}/markets?series_ticker=KXBTC15M&status=open&limit=100")["markets"]
    now = time.time()
    eligible = [m for m in markets if m.get("status") in ("active", "open") and
                m.get("ticker", "").startswith("KXBTC15M-") and not account.seen(m["ticker"]) and
                0 < epoch(m["close_time"]) - epoch(m["open_time"]) <= 901 and
                now - epoch(m["open_time"]) >= rules.earliest_elapsed_seconds and
                epoch(m["close_time"]) - now >= rules.min_seconds_left]
    if not eligible:
        account.log("skip", {"reason": "no eligible BTC window"})
        return
    market = min(eligible, key=lambda m: epoch(m["close_time"]))
    if market.get("strike_type") != "greater" or market.get("cap_strike") is not None:
        raise ValueError("unsupported contract payoff; need a single greater-than strike")
    ticker = market["ticker"]
    strike = float(market["floor_strike"])
    closes = epoch(market["close_time"])
    candles = feed.get(f"{COINBASE}/products/BTC-USD/candles?granularity=60", timely=False)
    spot = feed.get(f"{COINBASE}/products/BTC-USD/ticker")
    if not 0 <= time.time() - epoch(spot["time"]) <= rules.max_spot_age_seconds:
        raise ValueError("stale spot ticker")
    book = feed.book(ticker)
    now = time.time()
    if now - epoch(spot["time"]) > rules.max_spot_age_seconds:
        raise ValueError("spot became stale while obtaining book")
    mid = (book["yes"][0][0] + 1 - book["no"][0][0]) / 2
    p = forecast(mid, float(spot["price"]), strike, closes-now, candles, now, rules.anchor_weight)
    choices = []
    for side, chance in (("YES", p), ("NO", 1-p)):
        price, depth = ask(book, side)
        n = size(price, depth, account.capacity(now), rules.fee_rate)
        if n:
            net = chance - cost(n, price, rules.fee_rate)[0] / (100*n)
            if net > rules.margin:
                choices.append((net, side, price))
    account.log("decision", {"ticker": ticker, "model_yes": p, "market_yes": mid,
                             "candidates": choices, "strategy": STRATEGY, "strike": strike,
                             "spot": float(spot["price"]), "seconds_left": closes-now})
    if not choices:
        return
    _, side, limit = max(choices)
    # Model a marketable IOC after measured decision latency, never same-quote fills.
    time.sleep(rules.fill_delay)
    fresh = feed.book(ticker)
    price, depth = ask(fresh, side)
    now = time.time()
    if now - epoch(spot["time"]) > rules.max_spot_age_seconds:
        account.log("unfilled", {"ticker": ticker, "reason": "signal expired"})
        return
    filled = price <= limit and account.fill(ticker, side, price, depth, p, closes, now)
    account.log("paper_fill" if filled else "unfilled", {"ticker": ticker, "side": side,
                  "limit": limit, "rechecked_ask": price, "rechecked_depth": depth})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "status", "once", "run", "export"))
    parser.add_argument("--db", type=Path, default=Path("paperlab.sqlite"))
    parser.add_argument("--interval", type=float, default=15.0)
    parser.add_argument("--minutes", type=float, default=60.0)
    args = parser.parse_args()
    if args.interval < 5 or not 0 < args.minutes <= 1440:
        parser.error("interval must be >=5 seconds; duration must be 0–1440 minutes")
    account = Account(args.db)
    if args.command in ("init", "status"):
        print(json.dumps(account.summary(), indent=2))
        return
    if args.command == "export":
        print(json.dumps({"rules": asdict(account.rules), "summary": account.summary(),
                          "trades": [dict(t) for t in account.db.execute("SELECT * FROM trades ORDER BY opened")]}, indent=2))
        return
    feed = PublicFeed(account)
    deadline = time.monotonic() + args.minutes * 60
    while True:
        try:
            cycle(account, feed)
            print(json.dumps(account.summary()), flush=True)
        except (ValueError, KeyError, TypeError, OSError) as error:
            account.log("error", {"message": str(error)})
            print(json.dumps({"error": str(error), "traded": False}), flush=True)
        if args.command == "once" or time.monotonic() + args.interval >= deadline:
            break
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
