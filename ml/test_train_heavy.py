#!/usr/bin/env python3
"""Regression checks for the lightweight heavy-model exporter."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import train_heavy as heavy  # noqa: E402


class HeavyTrainerTest(unittest.TestCase):
    def test_stumps_can_select_a_high_distribution_threshold(self) -> None:
        # The old lower-tail slice could never choose a threshold above 0.012.
        # Make the target transition high in the feature range and ensure the
        # exporter actually considers that part of the distribution.
        values = np.linspace(0.0, 1.0, 200)
        x = np.column_stack([values] + [np.zeros_like(values) for _ in range(len(heavy.TABULAR) - 1)])
        y = (values > 0.72).astype(float)
        trees = heavy.fit_stumps(x, y, n_trees=1)
        self.assertEqual(len(trees), 1)
        self.assertGreater(trees[0]["nodes"][0]["threshold"], 0.50)


if __name__ == "__main__":
    unittest.main()
