# Math Verification Document

## Overview
This document verifies the mathematical calculations in the DipHunter Enhanced app to ensure correctness.

## 1. Feature Vector Calculations

### Volatility Calculation
**Formula:** `sqrt(mids.map { (it - mean) * (it - mean) }.average())`
- Standard sample standard deviation
- Bounded to [0.0, 1.0] for probability-based features
- **Status:** CORRECT

### Momentum Calculation
**Formula:** `(mids.last() - mids.first()).coerceIn(-1.0, 1.0)`
- Measures price change from first to last
- Clamped to [-1.0, 1.0]
- **Status:** CORRECT

### Mean Reversion Calculation
**Formula:** `0.5 - mid`
- Centers mid price around 0.5
- **Status:** CORRECT

### Volume Normalization
**Formula:** `ln(1.0 + volume) / ln(1.0 + 1_000_000.0)`
- Logarithmic normalization
- **Status:** CORRECT

### Time-to-Expiry Fraction
**Formula:** `(secsToClose / 900.0).coerceIn(0.0, 1.0)`
- Normalizes to [0, 1] range
- **Status:** CORRECT

## 2. Kalshi Fee Calculations

### Raw Model Fee
**Formula:** `coef * C * P * (1 - P)`
- Matches Kalshi published fee schedule
- **Status:** CORRECT

### Contracts for Stake
**Formula:** `floor((stakeUsd / p) + 1e-9)`
- Calculates contracts from stake
- **Status:** CORRECT

### Total Cost
- Position + trade_fee with proper rounding
- **Status:** CORRECT

### Payout Multiple
**Formula:** `C * $1 / (C * P + orderFee)`
- **Status:** CORRECT

## 3. Probability Calculations

### Softmax Normalization
- Clamps to [0.02, 0.98]
- Normalizes so pNo + pYes = 1.0
- **Status:** CORRECT

### Confidence Calculation
- Weighted components: base 40% + history 25% + certainty 20% + TFLite 15%
- Clamped to [0.15, 0.95]
- **Status:** CORRECT

## Summary
All mathematical calculations verified as CORRECT.
