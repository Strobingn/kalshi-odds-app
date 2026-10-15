import copy
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from analyze import Trade, analyze, audit, load_rows, partition, summary


def row(ident='t', net=.2, note='', rule='v2|v2-sf41|P'):
    return dict(id=ident, ticker='KXBTC15M-26OCT122015-15', state='CLOSED', contracts=10,
                entry_price=.4, entry_fee_usd=.17, exit_fee_usd=.18, sold_contracts=10,
                proceeds_usd=4.35 + net, net_usd=net, entry_at_ms=1000, closed_at_ms=2000,
                note=note, rule_version=rule, exit_reason='trail')


def trade(day, window, entry=.4, net=1., variant='sf41'):
    at = int(datetime(2026, 10, day, tzinfo=timezone.utc).timestamp() * 1000)
    return Trade(f'{variant}:{window}', variant, window, at, at + 1000, 10, entry, 10 * entry + .17, .35, net, 'trail')


class AuditTest(unittest.TestCase):
    def test_recomputes_fees_and_deduplicates(self):
        r = row()
        trades, counts = audit([r, copy.deepcopy(r), row('shadow', rule='v2|sf41|S'), dict(id='open', state='OPEN')])
        self.assertEqual(len(trades), 1)
        self.assertAlmostEqual(trades[0].net, .2)
        self.assertAlmostEqual(trades[0].fees, .35)
        self.assertEqual(counts, {'closed': 1, 'duplicates': 1, 'shadow': 1, 'OPEN': 1})

    def test_cashflow_mismatch_nonfinite_and_partial_closed_are_rejected(self):
        for change in ({'net_usd': 99}, {'entry_fee_usd': float('nan')}, {'sold_contracts': 3},
                       {'contracts': 1.5}, {'entry_at_ms': 3000}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                audit([row() | change])
        with self.assertRaises(ValueError):
            audit([row(), row(net=-1)])

    def test_camelcase_and_adaptive_cohorts_and_v2_stay_separate(self):
        def camel(key):
            parts = key.split('_')
            return parts[0] + ''.join(p.title() for p in parts[1:])
        r = {camel(k): v for k, v in row(note='v2ai=trial; test').items()}
        trades, _ = audit([r, row('base', rule='v2|sf41|P'), row('keep', note='v2ai=keep; ok')])
        self.assertEqual({t.variant for t in trades}, {'v2-sf41:trial', 'v2-sf41:keep', 'sf41'})

    def test_sqlite_is_read_only_and_json_input(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / 'phone.sqlite'
            with sqlite3.connect(p) as db:
                db.execute('CREATE TABLE scalp_trades (id TEXT)')
                db.execute("INSERT INTO scalp_trades VALUES ('one')")
            before = p.read_bytes()
            self.assertEqual(load_rows(p), [{'id': 'one'}])
            self.assertEqual(p.read_bytes(), before)
            j = Path(temp) / 'phone.json'
            j.write_text(json.dumps({'scalps': [row()]}))
            self.assertEqual(len(audit(load_rows(j))[0]), 1)

    def test_clusters_are_not_fake_independent_bets(self):
        trades = [trade(1, 'same', net=1) for _ in range(100)]
        stats = summary(trades, resamples=50)
        self.assertIsNone(stats['ci95_cents_per_contract'])
        self.assertEqual(stats['windows'], 1)
        stats = summary(trades + [trade(2, 'other', net=-1)], resamples=100)
        self.assertEqual(stats, summary(trades + [trade(2, 'other', net=-1)], resamples=100))
        self.assertLess(stats['ci95_cents_per_contract'][0], 0)
        self.assertGreater(stats['ci95_cents_per_contract'][1], 0)

    def test_day_split_embargoes_entire_crossing_window(self):
        crossing = replace(trade(2, 'cross'), closed=trade(3, 'later').entered + 1)
        trades = [trade(1, 'a'), crossing, trade(3, 'cross'), trade(3, 'c')]
        train, validation, embargoed, cutoff = partition(trades)
        self.assertEqual(cutoff, '2026-10-03')
        self.assertEqual([t.window for t in train], ['a'])
        self.assertEqual([t.window for t in validation], ['c'])
        self.assertEqual(len(embargoed), 2)

    def test_validation_cannot_change_training_selection_or_ranking(self):
        trades = [trade(day, f'{day}-{i}', entry=.25 if i < 5 else .45,
                        net=1 if i < 5 else -2) for day in (1, 2, 3) for i in range(10)]
        a = analyze(trades, min_trades=5, min_windows=5, resamples=50)
        b = analyze([replace(t, net=-100 * t.net) if t.day.endswith('03') else t for t in trades],
                    min_trades=5, min_windows=5, resamples=50)
        self.assertEqual(a['models'][0]['chosen_cap'], .4)
        self.assertEqual(a['models'][0]['chosen_cap'], b['models'][0]['chosen_cap'])
        self.assertEqual(a['models'][0]['chosen_train'], b['models'][0]['chosen_train'])
        self.assertGreater(a['models'][0]['chosen_validation']['net_usd'], 0)
        self.assertLess(b['models'][0]['chosen_validation']['net_usd'], 0)

    def test_net_per_contract_is_size_weighted_and_drawdown_uses_close_order(self):
        small = trade(1, 'a', net=1.)
        large = replace(trade(1, 'b', net=-2.), contracts=20, entered=small.entered + 1, closed=small.closed - 1)
        s = summary([small, large], resamples=50)
        self.assertAlmostEqual(s['net_cents_per_contract'], -100 / 30)
        self.assertEqual(s['max_realized_drawdown_usd'], -2.)

    def test_insufficient_days_does_not_invent_validation(self):
        with self.assertRaises(ValueError):
            analyze([trade(1, 'a')])

    def test_cli_end_to_end_with_synthetic_ledger(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / 'demo.jsonl'
            rows = []
            for day in (1, 2, 3):
                for i in range(10):
                    at = trade(day, 'any').entered
                    rows.append(row(f'{day}-{i}', net=.2) | dict(
                        ticker=f'KXBTC15M-26OCT{day:02}{i:02}15-15', entry_at_ms=at, closed_at_ms=at+1000))
            source.write_text(''.join(json.dumps(r) + '\n' for r in rows))
            out = Path(temp) / 'report'
            subprocess.run([sys.executable, str(Path(__file__).with_name('analyze.py')), str(source),
                            '--out', str(out), '--min-trades', '5', '--min-windows', '5', '--resamples', '50'], check=True)
            result = json.loads((out / 'readout.json').read_text())
            self.assertEqual(result['audit_counts']['closed'], 30)
            self.assertIn('readout', (out / 'readout.md').read_text())


if __name__ == '__main__':
    unittest.main()
