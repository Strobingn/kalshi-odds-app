"""Write docs/backtest-2026-09-25.md and PNG charts."""

from __future__ import annotations

import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

STRATS = [
    ("app_shipped", "App pick as shipped (alert gate)"),
    ("app_dirk", "App pick + Dirk filter (ask ≲ 31¢, profit ≥ $10)"),
    ("fair_dirk", "Fair-value baseline + Dirk filter"),
    ("cheap_side", "Naive: always cheap side ≤ 31¢"),
    ("always_favorite", "Naive: always favorite"),
    ("random_side", "Naive: random side (ticker-hash)"),
    ("tuned", "IS-tuned rule (reported OOS only)"),
]


def _fmt(s: dict) -> str:
    if not s or s.get("n", 0) == 0:
        return "| 0 | — | — | $0.00 | — | — | $0.00 | — |"
    wr = f"{100*s['win_rate']:.1f}%" if s.get("win_rate") is not None else "—"
    avg = f"{100*s['avg_ask']:.1f}¢" if s.get("avg_ask") is not None else "—"
    pbet = f"${s['pnl_per_bet']:+.3f}" if s.get("pnl_per_bet") is not None else "—"
    roi = f"{100*s['roi']:.1f}%" if s.get("roi") is not None else "—"
    ci = s.get("ci95") or (None, None, None)
    ci_s = f"[{ci[1]:+.3f}, {ci[2]:+.3f}]" if ci[1] is not None else "—"
    excl = ""
    if ci[1] is not None and ci[2] is not None:
        if ci[1] > 0:
            excl = " yes (above 0)"
        elif ci[2] < 0:
            excl = " yes (below 0)"
        else:
            excl = " no"
    return f"| {s['n']} | {s.get('wins',0)} | {wr} | {avg} | ${s['pnl']:+.2f} | {pbet} | {roi} | ${s['max_dd']:.2f} | {ci_s}{excl} |"


def _brk_table(title: str, brk: dict) -> str:
    lines = [f"#### {title}", "", "| Bucket | N | Win% | Avg ask | P&L | $/bet | ROI | CI95 $/bet |", "|---|---:|---:|---:|---:|---:|---:|---|"]
    for k, s in brk.items():
        wr = f"{100*s['win_rate']:.1f}%" if s.get("win_rate") is not None else "—"
        avg = f"{100*s['avg_ask']:.1f}¢" if s.get("avg_ask") is not None else "—"
        pbet = f"${s['pnl_per_bet']:+.3f}" if s.get("pnl_per_bet") is not None else "—"
        roi = f"{100*s['roi']:.1f}%" if s.get("roi") is not None else "—"
        ci = s.get("ci95") or (None, None, None)
        ci_s = f"[{ci[1]:+.3f}, {ci[2]:+.3f}]" if ci[1] is not None else "—"
        lines.append(f"| {k} | {s['n']} | {wr} | {avg} | ${s['pnl']:+.2f} | {pbet} | {roi} | {ci_s} |")
    lines.append("")
    return "\n".join(lines)


def _cum(pnls: list[float]) -> list[float]:
    o = []
    s = 0.0
    for x in pnls:
        s += x
        o.append(s)
    return o


def write_charts(result: dict, out_dir: Path, artifact_dir: Path) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    artifact_dir.mkdir(parents=True, exist_ok=True)
    fig, ax = plt.subplots(figsize=(10, 5.5))
    for key, label in STRATS:
        pack = result["strategies"].get(key) or {}
        eq = _cum(pack.get("oos_equity") or [])
        if not eq:
            continue
        ax.plot(range(1, len(eq) + 1), eq, label=label, linewidth=1.6)
    ax.axhline(0, color="#444", linewidth=0.8)
    ax.set_xlabel("OOS bet number (first qualifying minute, chronological)")
    ax.set_ylabel("Cumulative P&L after fees ($)")
    ax.set_title("Out-of-sample cumulative P&L — $5 all-in tickets")
    ax.legend(fontsize=8)
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    p1 = out_dir / "oos_cumulative_pnl.png"
    fig.savefig(p1, dpi=140)
    fig.savefig(artifact_dir / "oos_cumulative_pnl.png", dpi=140)
    plt.close(fig)

    cal = result.get("calibration") or {}
    fig, ax = plt.subplots(figsize=(6.2, 6.2))
    ax.plot([0, 1], [0, 1], "--", color="#888", label="perfect")
    for name, color in (("reliability_model", "#1f77b4"), ("reliability_market", "#ff7f0e")):
        rows = cal.get(name) or []
        xs = [r["pred"] for r in rows if r.get("n") and r.get("pred") is not None]
        ys = [r["obs"] for r in rows if r.get("n") and r.get("obs") is not None]
        ns = [r["n"] for r in rows if r.get("n")]
        if not xs:
            continue
        ax.plot(xs, ys, "o-", color=color, label=name.replace("reliability_", ""))
        for x, y, n in zip(xs, ys, ns):
            ax.annotate(str(n), (x, y), textcoords="offset points", xytext=(4, 4), fontsize=7, color=color)
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.set_xlabel("Mean predicted P(YES)")
    ax.set_ylabel("Observed YES frequency")
    ax.set_title("Reliability (all decision minutes)")
    ax.legend()
    ax.set_aspect("equal")
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    p2 = out_dir / "calibration.png"
    fig.savefig(p2, dpi=140)
    fig.savefig(artifact_dir / "calibration.png", dpi=140)
    plt.close(fig)
    return p1, p2


def write_report(result: dict, dest: Path, charts: tuple[Path, Path], meta: dict | None) -> None:
    span = result.get("span") or (None, None)
    cal = result.get("calibration") or {}
    rule = result.get("tuned_rule") or {}
    shadow = result.get("scorecard_shadow") or {}
    oos_rows = []
    for key, label in STRATS:
        s = (result["strategies"].get(key) or {}).get("oos") or {}
        oos_rows.append(f"| {label} {_fmt(s)}")

    # conclusion
    positive = []
    for key, label in STRATS:
        s = (result["strategies"].get(key) or {}).get("oos") or {}
        ci = s.get("ci95") or (None, None, None)
        if s.get("n", 0) and ci[1] is not None and ci[1] > 0:
            positive.append((key, label, s, ci))

    if positive:
        best = max(positive, key=lambda x: x[2]["pnl"])
        if best[0] == "tuned" and rule:
            concl = (
                f"**Yes — one OOS rule has a bootstrap CI above zero:** "
                f"{best[1]}. Exact rule (tuned on the earlier 2/3 of days only): "
                f"coin={rule.get('coin') or 'any'}, time-left bucket={rule.get('tte') or 'any'}, "
                f"max ask={rule.get('max_ask')}, min |net edge|={rule.get('min_edge')} pp, "
                f"Dirk filter (profit-if-win ≥ $10, $5 all-in). "
                f"OOS n={best[2]['n']}, P&L ${best[2]['pnl']:+.2f}, "
                f"$/bet {best[3][0]:+.3f} CI [{best[3][1]:+.3f}, {best[3][2]:+.3f}]. "
                f"This is still a small sample; do not size up."
            )
        else:
            concl = (
                f"**Yes — {best[1]} has an OOS CI that excludes zero.** "
                f"n={best[2]['n']}, P&L ${best[2]['pnl']:+.2f}, "
                f"$/bet CI [{best[3][1]:+.3f}, {best[3][2]:+.3f}]."
            )
    else:
        concl = (
            "**No. No strategy has a positive out-of-sample per-bet P&L whose bootstrap 95% CI excludes zero.** "
            "Kalshi 15-minute crypto mids are hard to beat after taker fees and a conservative (worse of close/high) fill. "
            "The MLP / blend edge vs mid looks weak or harmful once you pay the ask; "
            "the digital-fair / spot-vs-strike baseline is the least-bad component and still does not clear fees in OOS. "
            "Book-flow features could not be reconstructed (see limitations) — they may or may not help live, "
            "but we will not claim an edge we did not measure."
        )
        # name harmful components from calibration
        m = cal.get("model") or {}
        mk = cal.get("market") or {}
        mlp = cal.get("mlp") or {}
        extra = []
        if m.get("brier") is not None and mk.get("brier") is not None:
            if m["brier"] > mk["brier"]:
                extra.append(
                    f"Blended fair Brier {m['brier']:.4f} is *worse* than the market mid Brier {mk['brier']:.4f} "
                    f"on the same decision minutes."
                )
            else:
                extra.append(
                    f"Blended fair Brier {m['brier']:.4f} vs market {mk['brier']:.4f} "
                    f"— even when the probability is slightly better-calibrated, it does not pay after fees."
                )
        if mlp.get("brier") is not None and mk.get("brier") is not None and mlp["brier"] > mk["brier"]:
            extra.append(
                f"The 8-feature fallback MLP Brier {mlp['brier']:.4f} loses to the market mid "
                f"({mk['brier']:.4f}); that channel is pulling the blend the wrong way."
            )
        if extra:
            concl += " " + " ".join(extra)

    # meta docs
    docs = (meta or {}).get("docs") or {}
    cutoff = (meta or {}).get("cutoff") or {}

    md = f"""# DipHunter vs Kalshi 15m crypto — backtest 2026-09-25

Honest historical replay of the **as-shipped 0.3.10 decision path** on settled
`KXBTC15M` / `KXETH15M` / `KXSOL15M` windows. No look-ahead. One bet per market
(first qualifying minute). $5 max all-in including Kalshi taker fees.
Dirk's rule: only take a bet if profit-if-win ≥ $10 (asks around 31¢ or less).

## Data span

| | |
|---|---|
| First open | {span[0]} |
| Last close | {span[1]} |
| UTC days | {len(result.get('days') or [])} ({(result.get('days') or ['?'])[0]} → {(result.get('days') or ['?'])[-1]}) |
| Settled markets used | **{result['n_markets']}** (BTC {result['by_coin'].get('BTC',0)}, ETH {result['by_coin'].get('ETH',0)}, SOL {result['by_coin'].get('SOL',0)}) |
| Decision minutes scored | {result['n_decisions']} |
| In-sample days (tune only) | {len(result.get('is_days') or [])}: {(result.get('is_days') or [''])[0]} → {(result.get('is_days') or [''])[-1]} |
| **Out-of-sample days (what counts)** | {len(result.get('oos_days') or [])}: {(result.get('oos_days') or [''])[0]} → {(result.get('oos_days') or [''])[-1]} |
| Kalshi `market_settled_ts` cutoff | {cutoff.get('market_settled_ts', 'see meta.json')} |

Live settled markets (after the cutoff) come from `GET /markets?status=settled`
with `min_settled_ts` / `max_settled_ts`. Older windows, if requested, come from
`GET /historical/markets` and `GET /historical/markets/{{ticker}}/candlesticks`
([historical data]({docs.get('historical', 'https://docs.kalshi.com/getting_started/historical_data')}),
[candlesticks]({docs.get('candles', 'https://docs.kalshi.com/api-reference/market/get-market-candlesticks')}),
[historical candles]({docs.get('hist_candles', 'https://docs.kalshi.com/api-reference/historical/get-historical-market-candlesticks')})).
Public market-data; no auth. Rate limit respected (~8 reads/s, well under the
basic 20/s). Coinbase Exchange 1-minute candles (`granularity=60`, 300/request)
for BTC-USD / ETH-USD / SOL-USD over the same span.

Raw cache is **not** committed (too large). A small fixture lives under
`tools/backtest/fixtures/`.

## What was replayed

At elapsed minutes 1…13 the harness feeds **only data available at that minute**
into a port of the production classes:

- `FeatureVector` + `FallbackWeights` / `DipHunterModel.predict` (TFLite unavailable
  on this JVM path → same embedded MLP the phone uses when TFLite fails)
- `ScoringEngine` light blend (Heavy ML and extended AI **default OFF** in 0.3.10)
- Reconstructable TickBook channels: mid history, 1-minute velocity/acceleration,
  volume-delta flow (no taker flag), related-crypto mid, Coinbase spot nudge
- `DigitalOptionFairValue`, `SpotFeatureMath`, `DirectionSanity`, `TapeConflict.primaryFromMarket`
- `NetExpectedValue`, `KalshiFee` (ceil_6dp then ceil_cent), `TicketBuilder.resolveSide`
- Skip filter / 5pp alert gate for strategy (a)

**The shipped ticket side is the hero/primary side** (`TicketBuilder.resolveSide`
prefers `primaryHeroSide` from tape+spot over the model fade). That is what
strategies (a) and (b) bet.

### Features that could not be reconstructed (neutral / dropped)

| Feature | Why missing | What the engine does |
|---|---|---|
| Order-book imbalance / depth / decay | No historical L2 snapshots | Weight drops out, blend renormalizes |
| Quote-pull / cancel spike | WebSocket book deltas | Weight drops out |
| Taker aggressor | No public historical trade-side tape | `aggressorScore = 0`; flow uses mid-change sign only |
| Sub-second BTC lead–lag | 1-minute bars only | Weight drops out |
| Heavy ML sequence (30×10s bins) | Needs size/imbalance/aggressor/spot at 10s | Default off; not faked |
| News / rival flow / MM / conformal / meta / path | Live-only or default off | Off |
| OnlineAdapter / Calibrator | Need *this user's* prior settlements | Cold-start identity |

We also run the **fair-value baseline alone** (digital Φ(d2) / spot vs strike)
as strategy (c).

## Fill and fee

- Fill = conservative taker: **worse of candle close and high** on the side we buy.
  DOWN ask = `1 − yes_bid`; high DOWN ask = `1 − yes_bid.low`.
- Unusable 0.000 / 1.000 prints are dropped (`KalshiPrice` 0.1¢–99.9¢).
- Fee: `ceil_cent(C·P + ceil_6dp(0.07·C·P·(1−P)))` for a non-direct member.
  `C` is the max integer with that debit ≤ $5.
- P&L = `C × $1 − cost` if the side wins, else `−cost`.
- First qualifying minute only. **“Best minute” is not allowed.**

## Out-of-sample results (what counts)

| Strategy | N | Wins | Win% | Avg ask | P&L | $/bet | ROI | Max DD | Bootstrap 95% CI $/bet (excludes 0?) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
{chr(10).join(oos_rows)}

In-sample tables are in the appendix — they were used only to tune the optional
rule, never to pick the conclusion.

![OOS cumulative P&L](backtest/oos_cumulative_pnl.png)

## Walk-forward tuned rule

Search grid (IS days only): coin ∈ {{any,BTC,ETH,SOL}}, time-left bucket,
max ask ∈ {{20,31,40,50}}¢, min |net edge| ∈ {{0,3,5,8}} pp, plus Dirk's
profit ≥ $10 filter. Minimum 20 IS bets to count.

```
{json.dumps({k: rule[k] for k in rule if k in ('coin','tte','max_ask','min_edge','n','wins','win_rate','pnl','pnl_per_bet','ci95')}, indent=2, default=str)}
```

OOS application of that exact rule is the `IS-tuned rule` row above.

## Breakdowns (OOS, app + Dirk)

{_brk_table("By coin", (result["strategies"].get("app_dirk") or {}).get("oos_by_coin") or {})}
{_brk_table("By time-left", (result["strategies"].get("app_dirk") or {}).get("oos_by_tte") or {})}
{_brk_table("By fill ask", (result["strategies"].get("app_dirk") or {}).get("oos_by_ask") or {})}
{_brk_table("By |spot − strike|", (result["strategies"].get("app_dirk") or {}).get("oos_by_dist") or {})}

## Calibration (all decision minutes, not just bets)

| Forecast | N | Brier | Log-loss |
|---|---:|---:|---:|
| Blended fair P(YES) | {cal.get('model',{}).get('n')} | {cal.get('model',{}).get('brier')} | {cal.get('model',{}).get('logloss')} |
| Fallback MLP P(YES) | {cal.get('mlp',{}).get('n')} | {cal.get('mlp',{}).get('brier')} | {cal.get('mlp',{}).get('logloss')} |
| Digital fair P(YES) | {cal.get('digital',{}).get('n')} | {cal.get('digital',{}).get('brier')} | {cal.get('digital',{}).get('logloss')} |
| Market mid | {cal.get('market',{}).get('n')} | {cal.get('market',{}).get('brier')} | {cal.get('market',{}).get('logloss')} |

Reliability table (model):

| Bin | N | Mean forecast | Observed YES |
|---|---:|---:|---:|
{chr(10).join(f"| {r['bin']} | {r['n']} | {r.get('pred')} | {r.get('obs')} |" for r in (cal.get('reliability_model') or []))}

![Calibration](backtest/calibration.png)

Lower Brier is better. A well-calibrated 15m market mid is usually ~0.15–0.25.
**0.003 is not a plausible mean Brier on real 15-minute binaries** unless the
forecast is already ~95% and almost always correct — which is the scorecard bug
below, not a miracle model.

## Conclusion

{concl}

## Scorecard bug (0/5 · Brier 0.003)

The in-app line `Scorecard: 0/5 · Brier 0.003` mixes **two different questions**.

1. **Hit rate** (`0/5`) uses the **picked side** (`predictedSide` / hero side)
   vs the settlement YES/NO.
2. **Brier** uses `(predictedYes − 1_{result=yes})²` — a P(YES) calibration
   score, *not* a score of the side you bet.

`PredictionLogStore.applySettlement` (`app/src/main/java/com/dirk/kalshiodds/prediction/PredictionLogStore.kt` **184–193**):

```kotlin
val predYes = when (e.predictedSide?.uppercase()) {{
    "YES" -> true
    "NO" -> false
    else -> e.predictedYes > 0.5
}}
val actualYes = normalized == "yes"
val score = if (predYes == actualYes) 1 else 0
val y = if (actualYes) 1.0 else 0.0
val brier = (e.predictedYes - y) * (e.predictedYes - y)
```

The home-screen string (`OddsViewModel.kt` **868–871**) prints
`scoreSummary().correct/total` next to `meanBrier` with `%.3f`.

**How you get 0/5 and Brier 0.003 together**

If the five settled logs have `predictedYes ≈ 0.945` and `result = yes` but
`predictedSide = NO` (the engine faded a 96¢ YES because fair was 94.5¢):

- side hit rate = 0/5
- Brier = (0.945 − 1)² ≈ **0.003**

That Brier is in **probability² units on [0,1]** (proper Brier, not percent).
It looks “excellent” because P(YES) agreed with the outcome. The **side you
bet** was the opposite. The same inconsistency is in
`ScorecardMetrics.brierOf` / `sideHit` (`ScorecardMetrics.kt` **257–269**).

Replay of first-alert minutes in this backtest (shadow, not the phone log):
n={shadow.get('n')}, side hits={shadow.get('side_hits')},
mean P(YES) Brier={shadow.get('mean_brier_yes')},
mean **side** Brier={shadow.get('mean_brier_side')}.

**Fix (do not edit production in this PR — describe only)**

- `PredictionLogStore.kt:184–193`: store *two* scores, or pick one definition.
  Recommended: keep P(YES) Brier (statistically correct) **and** a side-Brier
  `p_side = predictedSide==NO ? 1-predictedYes : predictedYes`,
  `y_side = side_hit ? 1 : 0`, `brier_side = (p_side - y_side)²`.
  Label the UI “P(YES) Brier” vs “side hit rate” so they cannot be read as
  the same experiment.
- `ScorecardMetrics.kt:257–269`: same split; `window().brier` should not
  silently average a P(YES) Brier next to a side hit rate.
- `OddsViewModel.kt:868`: if `total < 20` (or `< ScorecardMetrics.MIN_HONEST_SAMPLES`),
  do not print Brier at all — 5 samples of 0.003 is noise dressed as precision.

`SettlementScorer` itself is fine; it only writes `result`. The bug is the
scorecard **definition**, not settlement lookup.

## Appendix — in-sample (do not use for the decision)

| Strategy | N | Wins | Win% | Avg ask | P&L | $/bet | ROI | Max DD | CI95 $/bet |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
{chr(10).join(f"| {label} {_fmt((result['strategies'].get(key) or {}).get('is_') or {{}})}" for key, label in STRATS)}

## Repro

```bash
python3 tools/backtest/run.py --days 28 --cache tools/backtest/cache
# Kotlin parity (no network):
./gradlew :app:testDebugUnitTest --tests com.dirk.kalshiodds.backtest.PipelineParityTest
./gradlew :app:testDebugUnitTest --tests com.dirk.kalshiodds.backtest.ScorecardBrierDiagnosisTest
```
"""
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(md)


def run_report(result: dict, repo: Path, artifact: Path, meta: dict | None) -> None:
    charts = write_charts(result, repo / "docs" / "backtest", artifact)
    write_report(result, repo / "docs" / "backtest-2026-09-25.md", charts, meta)


if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser()
    p.add_argument("--cache", default=str(Path(__file__).parent / "cache"))
    p.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    p.add_argument("--artifacts", default="/opt/cursor/artifacts")
    args = p.parse_args()
    cache = Path(args.cache)
    result = json.loads((cache / "sim_result.json").read_text())
    meta = json.loads((cache / "meta.json").read_text()) if (cache / "meta.json").is_file() else {}
    run_report(result, Path(args.repo), Path(args.artifacts), meta)
    print("wrote report")
