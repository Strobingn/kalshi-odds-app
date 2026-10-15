"""Shared helpers: Kalshi fee/cost model, $5 sizing, walk-forward folds, bootstrap CIs and the evaluation gate."""
import math, os, numpy as np
FEE_ROUND = os.environ.get("FEE_ROUND", "6dp")   # "6dp" = Kalshi fee_rounding doc (default, original); "cent" = ceil(0.07*C*P*(1-P)) to whole cents

TAKER_RATE, MAKER_RATE = 0.07, 0.0   # KXBTC15M/KXETH15M/KXSOL15M: fee_type 'quadratic', multiplier 1 (GET /series); maker fees only for 'quadratic_with_maker_fees'
MAX_STAKE = float(os.environ.get("MAX_STAKE", "5.00"))   # 2026-09-27: env override (Dirk: $10 max stake)
BUCKETS = [(">10m", 601, 10**9), ("5-10m", 301, 600), ("2-5m", 121, 300), ("<=2m", 0, 120)]   # by seconds to close

def ceil_to(x, q):
    return math.ceil(round(x / q, 9)) * q

def all_in_cost(C, P, rate=TAKER_RATE):
    """Cash leaving a non-direct member's balance for one fill of C contracts at price P (dollars):
    trade fee = ceil_6dp(rate*C*P*(1-P)); balance change floored to the cent (docs.kalshi.com/getting_started/fee_rounding).
    (The per-order accumulator can rebate part of the rounding across multiple fills; a single fill gets none, so this is conservative.)"""
    if FEE_ROUND == "cent":   # sensitivity (2026-09-27): fee itself rounded UP to a whole cent per order, then added
        return round(C * P + ceil_to(rate * C * P * (1 - P), 0.01), 6)
    fee = ceil_to(rate * C * P * (1 - P), 1e-6)
    return ceil_to(C * P + fee, 0.01)

def size_bet(P, rate=TAKER_RATE, stake=MAX_STAKE):
    """Largest whole-contract count whose all-in cost <= stake. Returns (C, cost)."""
    if P <= 0 or P >= 1: return 0, 0.0
    C = int(stake / P)
    while C > 0 and all_in_cost(C, P, rate) > stake + 1e-9: C -= 1
    return C, (all_in_cost(C, P, rate) if C > 0 else 0.0)

_cache = {}
def size_bet_cached(P, rate=TAKER_RATE):
    k = (round(P, 4), rate)
    if k not in _cache: _cache[k] = size_bet(round(P, 4), rate)
    return _cache[k]

def folds(days, train=14, test=3):
    """Walk-forward: yields (train_days, test_days) with train strictly before test, rolling by `test` days."""
    days = sorted(days); i = 0
    while i + train + test <= len(days):
        yield days[i:i + train], days[i + train:i + train + test]
        i += test

def boot_ci(pnl, n=5000, seed=0):
    pnl = np.asarray(pnl, float)
    if len(pnl) == 0: return (0.0, 0.0)
    rng = np.random.default_rng(seed)
    s = np.array([pnl[rng.integers(0, len(pnl), len(pnl))].sum() for _ in range(n)])
    return float(np.percentile(s, 2.5)), float(np.percentile(s, 97.5))

def day_block_ci(pnl, day, n=5000, seed=1):
    """Bootstrap resampling whole days (bets within a day are correlated)."""
    pnl = np.asarray(pnl, float); day = np.asarray(day)
    if len(pnl) == 0: return (0.0, 0.0)
    ud = np.unique(day); tot = np.array([pnl[day == d].sum() for d in ud])
    rng = np.random.default_rng(seed)
    s = np.array([tot[rng.integers(0, len(tot), len(tot))].sum() for _ in range(n)])
    return float(np.percentile(s, 2.5)), float(np.percentile(s, 97.5))

def summarize(bets):
    """bets: list of dicts with pnl, win, coin, day, tau, cost."""
    if not bets: return {"n": 0}
    pnl = np.array([b["pnl"] for b in bets]); win = np.array([b["win"] for b in bets])
    day = np.array([b["day"] for b in bets])
    lo, hi = boot_ci(pnl); dlo, dhi = day_block_ci(pnl, day)
    tot = pnl.sum()
    dayp = {d: pnl[day == d].sum() for d in np.unique(day)}
    maxday = max(dayp.values()) / tot if tot > 0 else float("nan")
    return {"n": int(len(pnl)), "wins": int(win.sum()), "losses": int(len(pnl) - win.sum()), "won_usd": float(pnl[pnl > 0].sum()), "lost_usd": float(-pnl[pnl <= 0].sum()),
            "pnl_per_bet": float(tot / len(pnl)), "ci95_per_bet": (round(lo / len(pnl), 4), round(hi / len(pnl), 4)),
            "win_rate": float(win.mean()), "pnl": float(tot), "ci95": (round(lo, 2), round(hi, 2)),
            "ci95_dayblock": (round(dlo, 2), round(dhi, 2)), "staked": float(sum(b["cost"] for b in bets)),
            "roi": float(tot / max(1e-9, sum(b["cost"] for b in bets))), "max_day_share": float(maxday), "days": int(len(dayp))}

def gate(bets, coins=("KXBTC15M", "KXETH15M", "KXSOL15M")):
    s = summarize(bets)
    if s["n"] == 0: return False, "no bets", s
    per = {c: sum(b["pnl"] for b in bets if b["coin"] == c) for c in coins}
    prof = sum(1 for v in per.values() if v > 0)
    reasons = []
    if s["n"] < 300: reasons.append(f"n={s['n']}<300")
    if s["ci95"][0] <= 0: reasons.append(f"CI low {s['ci95'][0]}<=0")
    if s["ci95_dayblock"][0] <= 0: reasons.append(f"day-block CI low {s['ci95_dayblock'][0]}<=0")
    need = min(2, len(coins))   # single-coin runs (e.g. BTC only): that coin must be profitable
    if prof < need: reasons.append(f"profitable coins {prof}/{len(coins)}")
    if not (s["max_day_share"] <= 0.30): reasons.append(f"max day share {s['max_day_share']:.2f}")
    return (not reasons), ("PASS" if not reasons else "FAIL: " + "; ".join(reasons)), s

def bucket_of(tau):
    for name, lo, hi in BUCKETS:
        if lo <= tau <= hi: return name
    return None

if __name__ == "__main__":
    for P in (0.05, 0.10, 0.25, 0.31, 0.50, 0.90):
        C, cost = size_bet(P); print(P, C, cost, "win profit", round(C - cost, 2), "fee", round(cost - C * P, 4))
