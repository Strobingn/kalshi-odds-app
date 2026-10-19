# Kalshi Scalping Strategy Research - 2026

## Executive Summary

Based on TurbineFi research (5,732+ backtests) and Kalshi BackTest data.

### Top Performing Strategies

| Strategy | Profitable | Avg ROI | Notes |
|----------|------------|---------|-------|
| **Momentum Continuation** | 100/100 | +$1,131-3,540 | Best performer - all signals aligned near expiry |
| **Selective EMA Momentum** | 99/100 | -$4.94 to +$122 | EMA crossover with tight bands |
| **BTC Spot Momentum** | 99/100 | -$1.70 to +$24 | Trade in direction of BTC spot |
| **VWAP Directional** | 100/100 | Positive | Price above/below VWAP with trend |
| **Momentum Tight Band** | 100/100 | +$2,252-4,516 | Narrow entry bands (0.45-0.55) |
| **MACD Threshold** | 10/10 | +$18-75 | Precision entries with MACD |
| **Gap Fade** | 100/100 | Positive | Fade extreme gaps with risk controls |

### Strategies That DON'T Work

| Strategy | Result | Why |
|----------|--------|-----|
| Mean Reversion | 0/432 profitable | Fees eat into small profits |
| Panic Fade | 0/100 in later tests | Market regime changed |
| Dual Timeframe Trend | 0/300 | Conflicting signals |

## Key Findings

### 1. Entry Timing
- **Best:** Last 5-6 minutes before settlement
- **Avoid:** First 10 minutes (thin books, high spreads)

### 2. Price Bands
Lower ceilings produce better net P&L despite lower win rates:
- $0.65 ceiling: +$36.28, 50.4% WR
- $0.85 ceiling: +$17.18, 55.5% WR

### 3. Position Sizing
- 5 contracts: Lower drawdown, slightly less P&L
- 10 contracts: Higher P&L, higher drawdown
- Recommend: Start with 5 contracts

### 4. Exit Strategy
- Near-settlement exits work best
- Profit targets and stop losses essential
- Exit strategy can completely change profitability

## Implementation Code

### Momentum Continuation (Best Strategy)

```kotlin
// Entry: All signals aligned near expiry
val nearExpiry = timeToClose in 60_000L..300_000L
val spotUp = coinbase5mChange > 0 && coinbase1mVelocity > 0
val smaBullish = price > sma20 && sma20 > sma50
val aboveVWAP = price > vwap

if (nearExpiry && spotUp && smaBullish && aboveVWAP && price <= 0.65) {
    buy YES
}
```

### MACD Threshold Strategy

```kotlin
val macdBullish = histogram1m > 2.5 && histogram5m > 0
if (macdBullish && price <= 0.65) {
    buy YES
}
```

### VWAP Directional

```kotlin
val priceAboveVWAP = price > vwap
val trendUp = sma10 > sma20
if (priceAboveVWAP && trendUp) {
    buy YES
}
```

## Risk Management

1. **Position Limits:** Max 10 contracts per trade
2. **Drawdown Limits:** Stop at -$50 portfolio loss
3. **Win Rate Monitoring:** Pause if WR < 45% over 20 trades
4. **Always use LIMIT orders** (never market orders)

## Disclaimer

This is educational research only, not investment advice. All results are from historical backtests. Past performance does not guarantee future results. Live trading involves substantial risk.

**Sources:**
- TurbineFi: 5,732 backtest synthesis (Sept 2026)
- Kalshi BackTest: Strategy validation guides
