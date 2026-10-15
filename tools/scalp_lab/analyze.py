#!/usr/bin/env python3
"""Read-only paper-ledger audit and chronological entry-cap experiment. Standard library only."""
from __future__ import annotations

import argparse
import csv
import json
import math
import random
import re
import sqlite3
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


@dataclass(frozen=True)
class Trade:
    id: str
    variant: str
    window: str
    entered: int
    closed: int
    contracts: int
    entry: float
    cost: float
    fees: float
    net: float
    reason: str

    @property
    def day(self):
        return datetime.fromtimestamp(self.entered / 1000, timezone.utc).date().isoformat()


def field(row, snake, default=None):
    camel = re.sub(r'_([a-z])', lambda m: m[1].upper(), snake)
    return row.get(snake, row.get(camel, default))


def finite(value, label):
    n = float(value)
    if not math.isfinite(n):
        raise ValueError(f'{label} must be finite')
    return n


def integer(value, label):
    n = finite(value, label)
    if n != int(n):
        raise ValueError(f'{label} must be an integer')
    return int(n)


def load_rows(path):
    if path.suffix.lower() in {'.db', '.sqlite', '.sqlite3'}:
        # URI read-only: never migrate, update aggregates or change the phone database.
        with sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True) as db:
            db.row_factory = sqlite3.Row
            return [dict(r) for r in db.execute('SELECT * FROM scalp_trades')]
    if path.suffix.lower() == '.csv':
        with path.open(newline='', encoding='utf-8-sig') as f:
            return list(csv.DictReader(f))
    with path.open(encoding='utf-8-sig') as f:
        if path.suffix.lower() == '.jsonl':
            return [json.loads(line) for line in f if line.strip()]
        value = json.load(f)
    if isinstance(value, dict):
        value = value.get('scalp_trades', value.get('scalps'))
    if not isinstance(value, list):
        raise ValueError('Expected a row array, scalp_trades/scalps array, JSONL, CSV or app SQLite database')
    return value


def audit(rows):
    trades, counts, seen = [], Counter(), {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('Each ledger row must be an object')
        ident = str(row.get('id', ''))
        if not ident:
            raise ValueError('Every row needs a stable id')
        if ident in seen:
            if row != seen[ident]:
                raise ValueError(f'Conflicting versions of trade {ident}; export one consistent snapshot')
            counts['duplicates'] += 1
            continue
        seen[ident] = row
        rule = str(field(row, 'rule_version', 'legacy-v1'))
        parts = rule.split('|')
        if len(parts) > 2 and parts[2] == 'S':
            counts['shadow'] += 1
            continue
        state = row.get('state')
        if state != 'CLOSED':
            counts[str(state)] += 1
            continue
        variant = parts[1] if len(parts) > 1 else 'legacy-v1'
        # Preserve adaptive cohorts; otherwise a parameter trial inherits its baseline's apparent edge.
        cohort = re.search(r'v2ai=(keep|trial)', str(row.get('note', '')))
        if cohort:
            variant += ':' + cohort[1]
        n = integer(row.get('contracts'), 'contracts')
        sold = integer(field(row, 'sold_contracts'), 'sold_contracts')
        entry = finite(field(row, 'entry_price'), 'entry_price')
        ef = finite(field(row, 'entry_fee_usd'), 'entry_fee_usd')
        xf = finite(field(row, 'exit_fee_usd'), 'exit_fee_usd')
        proceeds = finite(field(row, 'proceeds_usd'), 'proceeds_usd')
        entered = integer(field(row, 'entry_at_ms'), 'entry_at_ms')
        closed = integer(field(row, 'closed_at_ms'), 'closed_at_ms')
        if n <= 0 or sold != n or not 0 < entry < 1 or min(ef, xf, proceeds) < 0 or not 0 < entered <= closed:
            raise ValueError(f'Invalid closed trade accounting: {ident}')
        cost = n * entry + ef
        net = proceeds - xf - cost
        stored = finite(field(row, 'net_usd'), 'net_usd')
        if abs(net - stored) > 0.00001:
            raise ValueError(f'{ident}: net_usd={stored:.6f} but cash flows give {net:.6f}')
        ticker = str(row.get('ticker', '')).upper()
        if '-' not in ticker:
            raise ValueError(f'{ident}: missing market ticker')
        # Same close-time token clusters BTC, ETH, SOL and repeated entries together.
        window = ticker.split('-')[1]
        trades.append(Trade(ident, variant, window, entered, closed, n, entry, cost, ef + xf, net,
                            str(field(row, 'exit_reason', 'unknown'))))
        counts['closed'] += 1
    return sorted(trades, key=lambda t: (t.entered, t.id)), dict(counts)


def quantile(xs, q):
    return sorted(xs)[min(len(xs) - 1, int(q * len(xs)))]


def summary(trades, resamples=2000, cluster_day=False):
    if not trades:
        return {'n': 0, 'windows': 0, 'net_usd': 0.0, 'net_cents_per_contract': None,
                'ci95_cents_per_contract': None}
    groups = defaultdict(lambda: [0.0, 0])
    for t in trades:
        g = groups[t.day if cluster_day else t.window]
        g[0] += t.net
        g[1] += t.contracts
    groups = list(groups.values())
    samples, rng = [], random.Random(15)
    if len(groups) >= 2:
        for _ in range(resamples):
            chosen = rng.choices(groups, k=len(groups))
            samples.append(100 * sum(g[0] for g in chosen) / sum(g[1] for g in chosen))
    net, contracts = sum(t.net for t in trades), sum(t.contracts for t in trades)
    # Cash-flow equity in realized close order; never entry-order profits.
    peak = equity = drawdown = 0.0
    for t in sorted(trades, key=lambda t: (t.closed, t.id)):
        equity += t.net
        peak = max(peak, equity)
        drawdown = min(drawdown, equity - peak)
    return {'n': len(trades), 'windows': len({t.window for t in trades}), 'days': len({t.day for t in trades}),
            'contracts': contracts, 'net_usd': net, 'gross_usd': net + sum(t.fees for t in trades),
            'fees_usd': sum(t.fees for t in trades), 'win_rate': sum(t.net > 0 for t in trades) / len(trades),
            'net_cents_per_contract': 100 * net / contracts,
            'ci95_cents_per_contract': [quantile(samples, .025), quantile(samples, .975)] if samples else None,
            'roi': net / sum(t.cost for t in trades), 'max_realized_drawdown_usd': drawdown}


def partition(trades):
    """Two-thirds of complete entry days for fitting; entire crossing windows are embargoed."""
    days = sorted({t.day for t in trades})
    if len(days) < 3:
        raise ValueError('Need at least three entry days for chronological fitting/validation')
    cutoff_day = days[min(len(days) - 1, max(1, len(days) * 2 // 3))]
    cutoff = int(datetime.fromisoformat(cutoff_day).replace(tzinfo=timezone.utc).timestamp() * 1000)
    windows = defaultdict(list)
    for t in trades:
        windows[t.window].append(t)
    train, validation, embargoed = [], [], []
    for group in windows.values():
        if max(t.closed for t in group) < cutoff:
            train.extend(group)
        elif min(t.entered for t in group) >= cutoff:
            validation.extend(group)
        else:
            embargoed.extend(group)
    return train, validation, embargoed, cutoff_day


def analyze(trades, min_trades=30, min_windows=5, resamples=2000, cluster_day=False):
    train, validation, embargoed, cutoff = partition(trades)
    output = []
    variants = sorted({t.variant for t in trades})
    for variant in variants:
        fit = [t for t in train if t.variant == variant]
        test = [t for t in validation if t.variant == variant]
        # Predeclared small grid. Pick by training ONLY, then evaluate exactly that cap on validation.
        trials = []
        for cap in (1.0, .30, .40, .50):
            selected = [t for t in fit if t.entry <= cap + 1e-9]
            if len(selected) < min_trades or len({t.window for t in selected}) < min_windows:
                continue
            s = summary(selected, resamples, cluster_day)
            trials.append((s['net_cents_per_contract'], cap, s))
        chosen = max(trials, key=lambda x: (x[0], x[1])) if trials else None
        filtered = [t for t in test if chosen and t.entry <= chosen[1] + 1e-9]
        output.append({'variant': variant, 'train': summary(fit, resamples, cluster_day),
                       'validation': summary(test, resamples, cluster_day),
                       'chosen_cap': chosen[1] if chosen else None,
                       'chosen_train': chosen[2] if chosen else None,
                       'chosen_validation': summary(filtered, resamples, cluster_day),
                       'exit_reasons': dict(Counter(t.reason for t in test))})
    # Freeze ranking on training data: the validation column is never a selection criterion.
    output.sort(key=lambda x: (-(x['chosen_train']['net_cents_per_contract'] if x['chosen_train'] else -math.inf), x['variant']))
    return {'cutoff_utc': cutoff, 'embargoed_trades': len(embargoed), 'variants_tested': len(variants),
            'caps_tested_per_variant': 4, 'cluster': 'entry day' if cluster_day else '15-minute market window',
            'models': output,
            'limits': ['Entry-cap results filter already-recorded fills; they do not replay replacement trades, changed exits, queue position or bankroll competition.',
                       'Independent paper models can reuse the same displayed liquidity. Their profits are not an executable combined portfolio.',
                       'Intervals are descriptive, not adjusted for multiple model/cap trials. Validate frozen choices on new forward data.',
                       'No hypothetical size scaling: larger clips need a depth-and-latency replay. Open positions are excluded from closed-trade returns.']}


def markdown(result):
    lines = ['# Paper scalper readout', '', f"Validation starts {result['cutoff_utc']} UTC. {result['embargoed_trades']} crossing-window trades embargoed.",
             f"{result['variants_tested']} variants; four entry caps per variant; ranking and cap picked using training data only.",
             f"Bootstrap cluster: {result['cluster']}. Net includes recorded entry and exit fees.", '',
             '| Variant | Train ct edge | Cap | Validation N | Validation $ | Filtered N | Filtered $ | Filtered ct edge [95% CI] |',
             '|---|---:|---:|---:|---:|---:|---:|---|']
    for m in result['models']:
        s, f, tr = m['validation'], m['chosen_validation'], m['train']
        edge = tr['net_cents_per_contract']
        ci = f['ci95_cents_per_contract']
        interval = f"{f['net_cents_per_contract']:+.2f}¢ [{ci[0]:+.2f}, {ci[1]:+.2f}]" if ci else 'insufficient clusters'
        train_edge = f'{edge:+.2f}¢' if edge is not None else '—'
        cap = f"{m['chosen_cap']:.2f}" if m['chosen_cap'] is not None else 'insufficient training'
        lines.append(f"| {m['variant']} | {train_edge} | {cap} | {s['n']} | {s['net_usd']:+.2f} | {f['n']} | {f['net_usd']:+.2f} | {interval} |")
    lines += ['', *['- ' + s for s in result['limits']], '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ledger', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--min-trades', type=int, default=30)
    parser.add_argument('--min-windows', type=int, default=5)
    parser.add_argument('--resamples', type=int, default=2000)
    parser.add_argument('--cluster-day', action='store_true')
    args = parser.parse_args()
    if min(args.min_trades, args.min_windows, args.resamples) < 1:
        parser.error('Counts must be positive')
    try:
        trades, counts = audit(load_rows(args.ledger))
        result = analyze(trades, args.min_trades, args.min_windows, args.resamples, args.cluster_day)
    except (ValueError, TypeError, KeyError, sqlite3.Error) as exc:
        parser.error(str(exc))
    result['audit_counts'] = counts
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / 'readout.json').write_text(json.dumps(result, indent=2, allow_nan=False) + '\n')
    (args.out / 'readout.md').write_text(markdown(result))
    print(f"Audited {len(trades)} closed trades; report: {args.out / 'readout.md'}")


if __name__ == '__main__':
    main()
