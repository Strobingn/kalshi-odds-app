"""Write docs/backtest-2026-09-25.md and PNG charts."""

from __future__ import annotations

import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

CORE_STRATS = [
    ("app_shipped", "App pick as shipped (alert gate)"),
    ("app_dirk", "App pick + Dirk filter (ask ≲ 31¢, profit ≥ $10)"),
    ("fair_dirk", "Fair-value baseline + Dirk filter"),
    ("cheap_side", "Naive: always cheap side ≤ 31¢"),
    ("always_favorite", "Naive: always favorite"),
    ("random_side", "Naive: random side (ticker-hash)"),
    ("tuned", "IS-tuned rule (reported OOS only)"),
]
STRESS_STRATS = [
    ("app_shipped_stress", "Stress only: app pick, worse-of-close/high fill"),
]
EXPLORATORY_STRATS = [
    ("app_32_50", "Exploratory (OOS only, not tuned): app pick, ask 32–50¢"),
    ("app_tte_11_9", "Exploratory (OOS only, not tuned): app pick, time-left 11–9m"),
]
STRATS = CORE_STRATS + STRESS_STRATS + EXPLORATORY_STRATS
CLAIM_STRATS = CORE_STRATS + STRESS_STRATS + EXPLORATORY_STRATS
# Entry filter (EntryFilter.kt, docs/ml-review-2026-09-27.md #7). Reported in
# its own section with its own verdict; not part of the headline claim set.
ENTRY_STRATS = [
    ("app_entry", "App pick + entry filter (app defaults)"),
    ("entry_tuned", "App pick + IS-tuned entry filter (reported OOS only)"),
]
# OOS days of the 2026-09-25 run, whose breakdown motivated the defaults.
ENTRY_MOTIVATION_DAYS = ("2026-09-16", "2026-09-25")
STRATS = STRATS + ENTRY_STRATS


def _fmt(s: dict) -> str:
    if not s or s.get("n", 0) == 0:
        return "| 0 | 0 | — | — | $0.00 | — | — | $0.00 | — |"
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
    styles = {k: "-" for k, _ in CORE_STRATS}
    styles.update({k: ":" for k, _ in STRESS_STRATS})
    styles.update({k: "--" for k, _ in EXPLORATORY_STRATS})
    styles.update({k: "-." for k, _ in ENTRY_STRATS})
    for key, label in STRATS:
        pack = result["strategies"].get(key) or {}
        eq = _cum(pack.get("oos_equity") or [])
        if not eq:
            continue
        ax.plot(range(1, len(eq) + 1), eq, label=label, linewidth=1.6, linestyle=styles.get(key, "-"))
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


def _pct(x) -> str:
    if x is None:
        return "—"
    return f"{100 * x:.1f}¢" if abs(x) <= 2 else f"{x:.4f}"


def _pct_prob(x) -> str:
    if x is None:
        return "—"
    return f"{100 * x:.1f}%"


def _wr_price_table(title: str, rows: list) -> str:
    lines = [f"#### {title}", "", "| Bucket | N | Win% | Avg ask | Win% − ask |", "|---|---:|---:|---:|---:|"]
    for r in rows or []:
        wr = _pct_prob(r.get("win_rate"))
        avg = f"{100 * r['avg_ask']:.1f}¢" if r.get("avg_ask") is not None else "—"
        gap = f"{100 * r['wr_minus_ask']:+.1f}pp" if r.get("wr_minus_ask") is not None else "—"
        lines.append(f"| {r.get('bucket')} | {r.get('n')} | {wr} | {avg} | {gap} |")
    lines.append("")
    return "\n".join(lines)


def _entry_cell(s: dict) -> str:
    if not s or not s.get("n"):
        return "0 | — | — | —"
    pbet = f"${s['pnl_per_bet']:+.3f}" if s.get("pnl_per_bet") is not None else "—"
    roi = f"{100 * s['roi']:.1f}%" if s.get("roi") is not None else "—"
    return f"{s['n']} | ${s['pnl']:+.2f} | {pbet} | {roi}"


def _entry_section(result: dict) -> list[str]:
    """Entry filter (docs/ml-review-2026-09-27.md #7): rows, verdict, IS sweep, breakdowns."""
    ef = result.get("entry_filter") or {}
    strategies = result.get("strategies") or {}
    if not ef or "app_entry" not in strategies:
        return []
    d = ef.get("defaults") or {}
    rule = ef.get("rule") or {}
    me_default = int(d.get("min_elapsed_min") or 0)
    oos_days = [x for x in (result.get("oos_days") or []) if x]
    lo, hi = ENTRY_MOTIVATION_DAYS
    overlap = [x for x in oos_days if lo <= x <= hi]
    out = ["## Entry filter (ml-review #7)", ""]
    out.append(
        f"`EntryFilter` in the app: no entry while fewer than **{me_default} min** of the 15-minute window "
        f"have elapsed (exactly {15 - me_default}:00 left passes), and no entry when "
        f"|spot − strike| / strike < **{d.get('min_bp'):g}bp** unless the picked side's net edge ≥ "
        f"**{d.get('override_pp'):g}pp**. Missing spot / strike never blocks. The harness applies the same rule "
        "(`pipeline.entry_filter`) on top of the shipped alert gate. A blocked minute moves the bet to the next "
        "alert minute the filter allows, as it does live."
    )
    out.append("")
    out.append(
        f"**Read the default row with care.** The defaults were picked from the OOS breakdown of the 2026-09-25 run "
        f"(OOS {lo} → {hi}). {len(overlap)} of {len(oos_days)} OOS days here fall in that range, so the "
        "`app_entry` OOS row is not independent evidence on those days. The IS-tuned row is the clean walk-forward "
        "test: grid min elapsed ∈ {0,2,3,5} min × min distance ∈ {0,3,5,10}bp "
        f"(override fixed at {d.get('override_pp'):g}pp), chosen by IS P&L with ≥ 20 IS bets, then applied OOS."
    )
    out.append("")
    out.append("| Strategy | N | Wins | Win% | Avg ask | P&L | $/bet | ROI | Max DD | Bootstrap 95% CI $/bet (excludes 0?) |")
    out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    out.append(f"| App pick as shipped (baseline) {_fmt((strategies.get('app_shipped') or {}).get('oos') or {})}")
    for key, label in ENTRY_STRATS:
        out.append(f"| {label} {_fmt((strategies.get(key) or {}).get('oos') or {})}")
    out.append("")
    if rule:
        out.append(
            f"IS-tuned choice: min elapsed **{rule.get('min_elapsed_min')} min**, min distance **{rule.get('min_bp'):g}bp** "
            f"(IS n={rule.get('is_n')}, IS P&L ${rule.get('is_pnl') or 0.0:+.2f})."
        )
    else:
        out.append("IS-tuned choice: none (no grid cell had ≥ 20 IS bets).")
    out.append("")

    base = (strategies.get("app_shipped") or {}).get("oos") or {}
    verdict = []
    any_includes_zero = False
    for key, label in ENTRY_STRATS:
        s = (strategies.get(key) or {}).get("oos") or {}
        ci = s.get("ci95") or (None, None, None)
        if not s.get("n") or ci[1] is None:
            verdict.append(f"{label}: no OOS bets.")
            continue
        excl = "above 0" if ci[1] > 0 else ("below 0" if ci[2] < 0 else "includes 0")
        any_includes_zero = any_includes_zero or excl == "includes 0"
        vs = ""
        if base.get("pnl_per_bet") is not None:
            vs = f" vs ${base['pnl_per_bet']:+.3f}/bet as shipped"
        verdict.append(f"{label}: ${s['pnl_per_bet']:+.3f}/bet{vs}, n={s['n']}, CI [{ci[1]:+.3f}, {ci[2]:+.3f}] ({excl}).")
    if any_includes_zero:
        verdict.append("A CI that includes 0 means the filter is not shown to help or hurt; keep it as a guard, not an edge.")
    out.append("**Verdict:** " + " ".join(verdict))
    out.append("")

    grid = ef.get("grid") or []
    if grid:
        out.append("#### Sweep (IS picks; OOS columns are for sensitivity only — do not pick thresholds from them)")
        out.append("")
        out.append("| Min elapsed | Min distance | IS N | IS P&L | IS $/bet | IS ROI | OOS N | OOS P&L | OOS $/bet | OOS ROI |")
        out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for c in grid:
            me = c.get("min_elapsed_min")
            bp = c.get("min_bp") or 0.0
            tags = []
            if rule and me == rule.get("min_elapsed_min") and bp == rule.get("min_bp"):
                tags.append("IS pick")
            if me == d.get("min_elapsed_min") and bp == d.get("min_bp"):
                tags.append("app default")
            if me == 0 and bp == 0.0:
                tags.append("= as shipped")
            tag = f" ({', '.join(tags)})" if tags else ""
            out.append(f"| {me} min{tag} | {bp:g}bp | {_entry_cell(c.get('is_') or {})} | {_entry_cell(c.get('oos') or {})} |")
        out.append("")

    app_entry = strategies.get("app_entry") or {}
    out.append(_brk_table("App pick + entry filter — OOS by time-left", app_entry.get("oos_by_tte") or {}))
    out.append(_brk_table("App pick + entry filter — OOS by |spot − strike|", app_entry.get("oos_by_dist") or {}))
    return out


def write_report(result: dict, dest: Path, charts: tuple[Path, Path], meta: dict | None) -> None:
    span = result.get("span") or (None, None)
    cal = result.get("calibration") or {}
    rule = result.get("tuned_rule") or {}
    shadow = result.get("scorecard_shadow") or {}
    sanity = result.get("sanity") or {}
    settle = result.get("settlement") or {}
    oos_rows = []
    for key, label in CORE_STRATS:
        s = (result["strategies"].get(key) or {}).get("oos") or {}
        oos_rows.append(f"| {label} {_fmt(s)}")
    stress_rows = []
    for key, label in STRESS_STRATS:
        s = (result["strategies"].get(key) or {}).get("oos") or {}
        stress_rows.append(f"| {label} {_fmt(s)}")
    expl_rows = []
    for key, label in EXPLORATORY_STRATS:
        s = (result["strategies"].get(key) or {}).get("oos") or {}
        expl_rows.append(f"| {label} {_fmt(s)}")

    positive = []
    exploratory_positive = []
    for key, label in CLAIM_STRATS:
        s = (result["strategies"].get(key) or {}).get("oos") or {}
        ci = s.get("ci95") or (None, None, None)
        if s.get("n", 0) >= 20 and ci[1] is not None and ci[1] > 0:
            if key in {k for k, _ in EXPLORATORY_STRATS}:
                exploratory_positive.append((key, label, s, ci))
            else:
                positive.append((key, label, s, ci))

    sanity_ok = bool(sanity.get("passed"))
    settle_ok = bool(settle.get("passed"))
    if not sanity_ok or not settle_ok:
        failed = [c["name"] for c in (sanity.get("checks") or []) if not c.get("passed")]
        if not settle_ok:
            failed.append("settlement_audit")
        concl = (
            "**No conclusion — sanity or settlement checks failed.** "
            f"Failed gates: {', '.join(failed) or 'unknown'}. "
            "P&L tables below are printed but must not be read as an edge claim."
        )
    elif positive:
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
    elif exploratory_positive:
        best = max(exploratory_positive, key=lambda x: x[2]["pnl"])
        concl = (
            "**No claimed strategy has a positive OOS edge whose 95% CI excludes zero.** "
            f"One *exploratory* (not tuned) slice does: {best[1]} "
            f"(n={best[2]['n']}, P&L ${best[2]['pnl']:+.2f}, "
            f"$/bet CI [{best[3][1]:+.3f}, {best[3][2]:+.3f}]). "
            "Treat that as a hypothesis, not a result."
        )
    else:
        concl = (
            "**No. No strategy has a positive out-of-sample per-bet P&L whose bootstrap 95% CI excludes zero.** "
            "Primary fills are the candle-close ask. Kalshi 15-minute crypto mids are still hard to beat after taker fees. "
            "The MLP / blend edge vs mid looks weak or harmful once you pay the ask; "
            "the digital-fair / spot-vs-strike baseline is the least-bad component and still does not clear fees in OOS. "
            "Book-flow features could not be reconstructed (see limitations) — they may or may not help live, "
            "but we will not claim an edge we did not measure."
        )
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
        app = (result["strategies"].get("app_shipped") or {}).get("oos") or {}
        cheap = (result["strategies"].get("cheap_side") or {}).get("oos") or {}
        if app.get("n"):
            concl += (
                f" Do not use the current one-pick-per-window recommendation to chase a $50 profit target — "
                f"the OOS app pick is ${app['pnl']:+.2f} "
                f"({app['n']} bets, ${app.get('pnl_per_bet') or 0:+.3f}/bet)."
            )
        if cheap.get("n") and cheap.get("pnl_per_bet") is not None:
            concl += f" Cheap-side hunting lost ${cheap['pnl_per_bet']:+.3f}/bet OOS."

    # meta docs
    docs = (meta or {}).get("docs") or {}
    cutoff = (meta or {}).get("cutoff") or {}

    days = result.get("days") or ["?"]
    is_days = result.get("is_days") or [""]
    oos_days = result.get("oos_days") or [""]
    by_coin = result.get("by_coin") or {}
    hist_url = docs.get("historical", "https://docs.kalshi.com/getting_started/historical_data")
    candles_url = docs.get("candles", "https://docs.kalshi.com/api-reference/market/get-market-candlesticks")
    hist_candles_url = docs.get("hist_candles", "https://docs.kalshi.com/api-reference/historical/get-historical-market-candlesticks")
    rule_json = json.dumps(
        {k: rule[k] for k in rule if k in ("coin", "tte", "max_ask", "min_edge", "n", "wins", "win_rate", "pnl", "pnl_per_bet", "ci95")},
        indent=2,
        default=str,
    )
    rel_rows = "\n".join(
        f"| {r['bin']} | {r['n']} | "
        f"{'—' if r.get('pred') is None else f'{r['pred']:.3f}'} | "
        f"{'—' if r.get('obs') is None else f'{r['obs']:.3f}'} |"
        for r in (cal.get("reliability_model") or [])
    )
    is_rows = "\n".join(
        f"| {label} {_fmt((result['strategies'].get(key) or {}).get('is_') or {})}"
        for key, label in CORE_STRATS + STRESS_STRATS
    )
    app_dirk = result["strategies"].get("app_dirk") or {}
    yn = sanity.get("yes_plus_no") or {}
    checks = sanity.get("checks") or []

    def _val(v):
        if v is None:
            return "—"
        if isinstance(v, float):
            return f"{v:.4f}"
        return str(v)

    def _cents(v):
        if v is None:
            return "—"
        return f"{100.0 * v:.1f}¢"

    def _px(v):
        if v is None:
            return "—"
        return f"{v:.2f}"

    check_lines = []
    for c in checks:
        check_lines.append(
            f"| {c.get('name')} | {_val(c.get('value'))} | {c.get('expect')} | {'PASS' if c.get('passed') else 'FAIL'} |"
        )
    check_rows = "\n".join(check_lines)
    settle_lines = []
    for r in settle.get("sample") or []:
        settle_lines.append(
            f"| {r.get('ticker')} | {r.get('strike')} | {r.get('result')} | {_px(r.get('coinbase_close'))} | "
            f"{r.get('close_time')} | {r.get('coinbase_gt_strike')} | {r.get('result_yes_means_up')} |"
        )
    settle_rows = "\n".join(settle_lines)

    parts = []
    parts.append("# DipHunter vs Kalshi 15m crypto — backtest 2026-09-25")
    parts.append("")
    parts.append(
        "Honest historical replay of the **as-shipped 0.3.10 decision path** on settled "
        "`KXBTC15M` / `KXETH15M` / `KXSOL15M` windows. No look-ahead. One bet per market "
        "(first qualifying minute). $5 max all-in including Kalshi taker fees. "
        "Dirk's rule: only take a bet if profit-if-win ≥ $10 (asks around 31¢ or less)."
    )
    parts.append("")
    parts.append("## What changed from v1 and why")
    parts.append("")
    parts.append(
        "v1 P&L tables failed basic sanity. Random-side average ask was **74.6¢** "
        "(a coin-flip side at a 15m binary should average ~50–53¢). "
        "Always-favorite won 57% at a 79¢ ask and cheap-side won 15% at 25¢ — "
        "that contradicts the same report's market-mid calibration (Brier 0.159, reliability near the diagonal)."
    )
    parts.append("")
    parts.append(
        "**Cause:** the primary fill was `max(close, high)` on the YES ask and "
        "`max(1 − yes_bid.close, 1 − yes_bid.low)` on NO. A typical candle has "
        "`yes_ask.close = 0.47` and `yes_ask.high = 0.75`; `yes_bid.low` is often `0.000`. "
        "That rule overpays ~25¢ per contract and makes favorites look too expensive "
        "and longshots look too cheap relative to their win rates."
    )
    parts.append("")
    parts.append(
        "**v2 fill:** primary taker fill is the ask at the decision minute's candle **close**: "
        "YES = `yes_ask.close`, NO = `1 − yes_bid.close` "
        "([Kalshi candlesticks](https://docs.kalshi.com/api-reference/market/get-market-candlesticks)). "
        "`end_period_ts` is the inclusive period end, so minute *t* uses the candle ending at *t*, not *t+1*. "
        "Worse-of-close/high is kept only as a **stress-test row**. "
        "Random-side now requires **both** close asks to be usable so skip-bias cannot inflate the average. "
        "A sanity gate and a 20-market settlement audit must pass before any edge claim. "
        "Same cached 28-day pull; no re-fetch."
    )
    parts.append("")
    parts.append("## Data span")
    parts.append("")
    parts.append("| | |")
    parts.append("|---|---|")
    parts.append(f"| First open | {span[0]} |")
    parts.append(f"| Last close | {span[1]} |")
    parts.append(f"| UTC days | {len(days)} ({days[0]} → {days[-1]}) |")
    parts.append(
        f"| Settled markets used | **{result['n_markets']}** "
        f"(BTC {by_coin.get('BTC', 0)}, ETH {by_coin.get('ETH', 0)}, SOL {by_coin.get('SOL', 0)}) |"
    )
    parts.append(f"| Decision minutes scored | {result['n_decisions']} |")
    parts.append(f"| In-sample days (tune only) | {len(is_days)}: {is_days[0]} → {is_days[-1]} |")
    parts.append(f"| **Out-of-sample days (what counts)** | {len(oos_days)}: {oos_days[0]} → {oos_days[-1]} |")
    parts.append(f"| Kalshi `market_settled_ts` cutoff | {cutoff.get('market_settled_ts', 'see meta.json')} |")
    parts.append("")
    parts.append(
        "Live settled markets (after the cutoff) come from `GET /markets?status=settled` "
        "with `min_settled_ts` / `max_settled_ts`. Older windows, if requested, come from "
        "`GET /historical/markets` and `GET /historical/markets/{ticker}/candlesticks` "
        f"([historical data]({hist_url}), [candlesticks]({candles_url}), "
        f"[historical candles]({hist_candles_url})). "
        "Public market-data; no auth. Rate limit respected (~8 reads/s, well under the "
        "basic 20/s). Coinbase Exchange 1-minute candles (`granularity=60`, 300/request) "
        "for BTC-USD / ETH-USD / SOL-USD over the same span."
    )
    parts.append("")
    parts.append("Raw cache is **not** committed (too large). A small fixture lives under `tools/backtest/fixtures/`.")
    parts.append("")
    parts.append("## What was replayed")
    parts.append("")
    parts.append(
        "At elapsed minutes 1…13 the harness feeds **only data available at that minute** "
        "into a port of the production classes:"
    )
    parts.append("")
    parts.append("- `FeatureVector` + `FallbackWeights` / `DipHunterModel.predict` (TFLite unavailable on this JVM path → same embedded MLP the phone uses when TFLite fails)")
    parts.append("- `ScoringEngine` light blend (Heavy ML and extended AI **default OFF** in 0.3.10)")
    parts.append("- Reconstructable TickBook channels: mid history, 1-minute velocity/acceleration, volume-delta flow (no taker flag), related-crypto mid, Coinbase spot nudge")
    parts.append("- `DigitalOptionFairValue`, `SpotFeatureMath`, `DirectionSanity`, `TapeConflict.primaryFromMarket`")
    parts.append("- `NetExpectedValue`, `KalshiFee` (ceil_6dp then ceil_cent), `TicketBuilder.resolveSide`")
    parts.append("- Skip filter / 5pp alert gate for strategy (a)")
    parts.append("")
    parts.append(
        "**The shipped ticket side is the hero/primary side** (`TicketBuilder.resolveSide` "
        "prefers `primaryHeroSide` from tape+spot over the model fade). That is what strategies (a) and (b) bet."
    )
    parts.append("")
    parts.append("### Features that could not be reconstructed (neutral / dropped)")
    parts.append("")
    parts.append("| Feature | Why missing | What the engine does |")
    parts.append("|---|---|---|")
    parts.append("| Order-book imbalance / depth / decay | No historical L2 snapshots | Weight drops out, blend renormalizes |")
    parts.append("| Quote-pull / cancel spike | WebSocket book deltas | Weight drops out |")
    parts.append("| Taker aggressor | No public historical trade-side tape | `aggressorScore = 0`; flow uses mid-change sign only |")
    parts.append("| Sub-second BTC lead–lag | 1-minute bars only | Weight drops out |")
    parts.append("| Heavy ML sequence (30×10s bins) | Needs size/imbalance/aggressor/spot at 10s | Default off; not faked |")
    parts.append("| News / rival flow / MM / conformal / meta / path | Live-only or default off | Off |")
    parts.append("| OnlineAdapter / Calibrator | Need *this user's* prior settlements | Cold-start identity |")
    parts.append("")
    parts.append("We also run the **fair-value baseline alone** (digital Φ(d2) / spot vs strike) as strategy (c).")
    parts.append("")
    parts.append("## Fill and fee")
    parts.append("")
    parts.append("- **Primary fill** = candle **close** ask at the decision minute. YES = `yes_ask.close`; NO / DOWN = `1 − yes_bid.close`.")
    parts.append("- **Stress-test fill** (one row only) = worse of close and high: YES `max(yes_ask.close, yes_ask.high)`; NO `max(1 − yes_bid.close, 1 − yes_bid.low)`.")
    parts.append("- Unusable 0.000 / 1.000 prints are dropped (`KalshiPrice` 0.1¢–99.9¢).")
    parts.append("- Fee: `ceil_cent(C·P + ceil_6dp(0.07·C·P·(1−P)))` for a non-direct member. `C` is the max integer with that debit ≤ $5.")
    parts.append("- P&L = `C × $1 − cost` if the side wins, else `−cost`.")
    parts.append("- First qualifying minute only. **“Best minute” is not allowed.**")
    parts.append("")
    parts.append("## Sanity (must pass before any conclusion)")
    parts.append("")
    parts.append(
        f"Overall: **{'PASS' if sanity.get('passed') else 'FAIL'}**. "
        f"Minutes with both close asks usable: {sanity.get('n_minutes_both_asks', 0)}."
    )
    parts.append("")
    parts.append("| Check | Value | Expect | Result |")
    parts.append("|---|---:|---|---|")
    parts.append(check_rows)
    parts.append("")
    parts.append("| | Median | p95 | Mean |")
    parts.append("|---|---:|---:|---:|")
    parts.append(f"| YES ask + NO ask | {_cents(yn.get('median'))} | {_cents(yn.get('p95'))} | {_cents(yn.get('mean'))} |")
    parts.append(f"| Random-side ask (all those minutes) | {_cents(sanity.get('random_side_avg_ask'))} | — | — |")
    parts.append("")
    parts.append(
        "A calibrated book has YES ask + NO ask ≈ 100–105¢ (spread). "
        "A random side must then average ~50–53¢. "
        "Favorite / cheap-side win rate vs 10¢ price buckets should sit near the diagonal."
    )
    parts.append("")
    parts.append(_wr_price_table("Favorite (first-bet) win rate vs price paid", sanity.get("favorite_by_10c") or []))
    parts.append(_wr_price_table("Cheap side ≤31¢ (first-bet) win rate vs price paid", sanity.get("cheap_by_10c") or []))
    parts.append(_wr_price_table("Favorite (every decision minute, both asks)", sanity.get("favorite_all_minutes_by_10c") or []))
    parts.append(_wr_price_table("Cheaper close ask (every decision minute)", sanity.get("cheap_all_minutes_by_10c") or []))
    parts.append("## Settlement mapping (20 random markets)")
    parts.append("")
    parts.append(
        f"Sample agree {settle.get('sample_agree')}/{int(settle.get('sample_agree') or 0) + int(settle.get('sample_disagree') or 0)} "
        f"(missing spot {settle.get('sample_missing', 0)}). "
        f"All markets with a Coinbase print: {settle.get('overall_agree')}/{settle.get('overall_n')} "
        f"({_pct_prob(settle.get('overall_agree_rate'))}). "
        f"**{'PASS' if settle.get('passed') else 'FAIL'}** — `result=yes` means UP (Coinbase close > strike). "
        f"{settle.get('note') or ''}"
    )
    parts.append("")
    parts.append("| Ticker | Strike | Result | Coinbase close | close_time | spot>strike | yes=UP |")
    parts.append("|---|---:|---|---:|---|---|---|")
    parts.append(settle_rows)
    parts.append("")
    parts.append("## Out-of-sample results (what counts)")
    parts.append("")
    parts.append("| Strategy | N | Wins | Win% | Avg ask | P&L | $/bet | ROI | Max DD | Bootstrap 95% CI $/bet (excludes 0?) |")
    parts.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    parts.append("\n".join(oos_rows))
    parts.append("")
    parts.append("### Stress-test fill (worse of close/high) — not the primary")
    parts.append("")
    parts.append("| Strategy | N | Wins | Win% | Avg ask | P&L | $/bet | ROI | Max DD | Bootstrap 95% CI $/bet (excludes 0?) |")
    parts.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    parts.append("\n".join(stress_rows))
    parts.append("")
    parts.append("### Exploratory slices (out of sample only, **not tuned**)")
    parts.append("")
    parts.append(
        "These two rows were added after v1 review. They are **not** in the walk-forward grid "
        "and must not be read as a discovered edge. "
        "`app_32_50` waits for a later alert in the 32–50¢ band; that is why it has more bets "
        "than the first-alert 32–50¢ slice in the app-pick breakdown."
    )
    parts.append("")
    parts.append("| Strategy | N | Wins | Win% | Avg ask | P&L | $/bet | ROI | Max DD | Bootstrap 95% CI $/bet (excludes 0?) |")
    parts.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    parts.append("\n".join(expl_rows))
    parts.append("")
    parts.append("In-sample tables are in the appendix — they were used only to tune the optional rule, never to pick the conclusion.")
    parts.append("")
    parts.append("![OOS cumulative P&L](backtest/oos_cumulative_pnl.png)")
    parts.append("")
    parts.append("## Walk-forward tuned rule")
    parts.append("")
    parts.append("Search grid (IS days only): coin ∈ {any,BTC,ETH,SOL}, time-left bucket, max ask ∈ {20,31,40,50}¢, min |net edge| ∈ {0,3,5,8} pp, plus Dirk's profit ≥ $10 filter. Minimum 20 IS bets to count.")
    parts.append("")
    parts.append("```")
    parts.append(rule_json)
    parts.append("```")
    parts.append("")
    parts.append("OOS application of that exact rule is the `IS-tuned rule` row above.")
    parts.append("")
    app_shipped = result["strategies"].get("app_shipped") or {}
    parts.append("## Breakdowns (OOS, app pick as shipped)")
    parts.append("")
    parts.append(
        "Dirk's cheap-side filter produced **0 OOS bets** on the shipped hero side "
        "(that side is usually the favorite, ~66¢ close-ask). Tables below are the unfiltered "
        "app pick — the only app strategy with enough OOS trades to slice."
    )
    parts.append("")
    parts.append(_brk_table("By coin", app_shipped.get("oos_by_coin") or {}))
    parts.append(_brk_table("By time-left", app_shipped.get("oos_by_tte") or {}))
    parts.append(_brk_table("By fill ask", app_shipped.get("oos_by_ask") or {}))
    parts.append(_brk_table("By |spot − strike|", app_shipped.get("oos_by_dist") or {}))
    parts.extend(_entry_section(result))
    parts.append("## Calibration (all decision minutes, not just bets)")
    parts.append("")
    parts.append("| Forecast | N | Brier | Log-loss |")
    parts.append("|---|---:|---:|---:|")
    def _cal(row, key):
        v = (cal.get(row) or {}).get(key)
        if v is None:
            return "—"
        if key == "n":
            return str(int(v))
        return f"{v:.4f}"

    parts.append(f"| Blended fair P(YES) | {_cal('model','n')} | {_cal('model','brier')} | {_cal('model','logloss')} |")
    parts.append(f"| Fallback MLP P(YES) | {_cal('mlp','n')} | {_cal('mlp','brier')} | {_cal('mlp','logloss')} |")
    parts.append(f"| Digital fair P(YES) | {_cal('digital','n')} | {_cal('digital','brier')} | {_cal('digital','logloss')} |")
    parts.append(f"| Market mid | {_cal('market','n')} | {_cal('market','brier')} | {_cal('market','logloss')} |")
    parts.append("")
    parts.append("Reliability table (model):")
    parts.append("")
    parts.append("| Bin | N | Mean forecast | Observed YES |")
    parts.append("|---|---:|---:|---:|")
    parts.append(rel_rows)
    parts.append("")
    parts.append("![Calibration](backtest/calibration.png)")
    parts.append("")
    parts.append("Lower Brier is better. A well-calibrated 15m market mid is usually ~0.15–0.25.")
    parts.append("**0.003 is not a plausible mean Brier on real 15-minute binaries** unless the forecast is already ~95% and almost always correct — which is the scorecard bug below, not a miracle model.")
    parts.append("")
    parts.append("## Conclusion")
    parts.append("")
    parts.append(concl)
    parts.append("")
    parts.append("## Scorecard bug (0/5 · Brier 0.003)")
    parts.append("")
    parts.append("The in-app line `Scorecard: 0/5 · Brier 0.003` mixes **two different questions**.")
    parts.append("")
    parts.append("1. **Hit rate** (`0/5`) uses the **picked side** (`predictedSide` / hero side) vs the settlement YES/NO.")
    parts.append("2. **Brier** uses `(predictedYes − 1_{result=yes})²` — a P(YES) calibration score, *not* a score of the side you bet.")
    parts.append("")
    parts.append("`PredictionLogStore.applySettlement` (`app/src/main/java/com/dirk/kalshiodds/prediction/PredictionLogStore.kt` **184–193**):")
    parts.append("")
    parts.append("```kotlin")
    parts.append("val predYes = when (e.predictedSide?.uppercase()) {")
    parts.append('    "YES" -> true')
    parts.append('    "NO" -> false')
    parts.append("    else -> e.predictedYes > 0.5")
    parts.append("}")
    parts.append('val actualYes = normalized == "yes"')
    parts.append("val score = if (predYes == actualYes) 1 else 0")
    parts.append("val y = if (actualYes) 1.0 else 0.0")
    parts.append("val brier = (e.predictedYes - y) * (e.predictedYes - y)")
    parts.append("```")
    parts.append("")
    parts.append("The home-screen string (`OddsViewModel.kt` **868–871**) prints `scoreSummary().correct/total` next to `meanBrier` with `%.3f`.")
    parts.append("")
    parts.append("**How you get 0/5 and Brier 0.003 together**")
    parts.append("")
    parts.append("If the five settled logs have `predictedYes ≈ 0.945` and `result = yes` but `predictedSide = NO` (the engine faded a 96¢ YES because fair was 94.5¢):")
    parts.append("")
    parts.append("- side hit rate = 0/5")
    parts.append("- Brier = (0.945 − 1)² ≈ **0.003**")
    parts.append("")
    parts.append("That Brier is in **probability² units on [0,1]** (proper Brier, not percent). It looks “excellent” because P(YES) agreed with the outcome. The **side you bet** was the opposite. The same inconsistency is in `ScorecardMetrics.brierOf` / `sideHit` (`ScorecardMetrics.kt` **257–269**).")
    parts.append("")
    parts.append(
        f"Replay of first-alert minutes in this backtest (shadow, not the phone log): "
        f"n={shadow.get('n')}, side hits={shadow.get('side_hits')}, "
        f"mean P(YES) Brier={shadow.get('mean_brier_yes')}, "
        f"mean **side** Brier={shadow.get('mean_brier_side')}."
    )
    parts.append("")
    parts.append("**Fix (do not edit production in this PR — describe only)**")
    parts.append("")
    parts.append("- `PredictionLogStore.kt:184–193`: store *two* scores, or pick one definition. Recommended: keep P(YES) Brier (statistically correct) **and** a side-Brier `p_side = predictedSide==NO ? 1-predictedYes : predictedYes`, `y_side = side_hit ? 1 : 0`, `brier_side = (p_side - y_side)²`. Label the UI “P(YES) Brier” vs “side hit rate” so they cannot be read as the same experiment.")
    parts.append("- `ScorecardMetrics.kt:257–269`: same split; `window().brier` should not silently average a P(YES) Brier next to a side hit rate.")
    parts.append("- `OddsViewModel.kt:868`: if `total < 20` (or `< ScorecardMetrics.MIN_HONEST_SAMPLES`), do not print Brier at all — 5 samples of 0.003 is noise dressed as precision.")
    parts.append("")
    parts.append("`SettlementScorer` itself is fine; it only writes `result`. The bug is the scorecard **definition**, not settlement lookup.")
    parts.append("")
    parts.append("## Appendix — in-sample (do not use for the decision)")
    parts.append("")
    parts.append("| Strategy | N | Wins | Win% | Avg ask | P&L | $/bet | ROI | Max DD | CI95 $/bet |")
    parts.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    parts.append(is_rows)
    parts.append("")
    parts.append("## Repro")
    parts.append("")
    parts.append("```bash")
    parts.append("python3 tools/backtest/run.py --days 28 --cache tools/backtest/cache")
    parts.append("# Kotlin parity (no network):")
    parts.append("./gradlew :app:testDebugUnitTest --tests com.dirk.kalshiodds.backtest.PipelineParityTest")
    parts.append("./gradlew :app:testDebugUnitTest --tests com.dirk.kalshiodds.backtest.ScorecardBrierDiagnosisTest")
    parts.append("./gradlew :app:testDebugUnitTest --tests com.dirk.kalshiodds.signal.engine.EntryFilterTest")
    parts.append("```")
    parts.append("")
    md = "\n".join(parts)

    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(md)


def run_report(result: dict, repo: Path, artifact: Path, meta: dict | None) -> None:
    charts = write_charts(result, repo / "docs" / "backtest", artifact)
    dest = repo / "docs" / "backtest-2026-09-25.md"
    write_report(result, dest, charts, meta)
    artifact.mkdir(parents=True, exist_ok=True)
    (artifact / "backtest-2026-09-25.md").write_text(dest.read_text())


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
