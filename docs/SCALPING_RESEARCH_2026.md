# Kalshi Scalping Strategy Research - 2026

## Executive Summary

Based on comprehensive research from TurbineFi (5,732+ backtests) and Kalshi BackTest, here are the **proven profitable scalping strategies** for Kalshi 15-minute Bitcoin (KXBTC15M) markets:

### Top Performing Strategy Families (2026)

| Strategy | Profitable Variants | Avg ROI | Key Insight |
|----------|---------------------|---------|-------------|
| **Momentum Continuation** | 100/100 | +$1,131 - +$3,540 | Buy when spot momentum aligns with contract direction near expiry |
| **Selective EMA Momentum** | 99/100 | -$4.94 to +$122.86 | Use exponential moving average crossovers with tight filters |
| **BTC 15m Spot Momentum** | 99/100 | -$1.70 to +$24.40 | Trade in direction of underlying BTC spot movement |
| **BTC VWAP Directional** | 100/100 | Positive | Trade when price is above/below VWAP with trend confirmation |
| **Momentum Tight Band** | 100/100 | Positive | Use narrow entry bands (0.45-0.55) with momentum confirmation |
| **BTC Gap Fade** | 100/100 | Positive | Fade extreme price gaps, but ONLY with proper risk controls |
| **Edge Filter Sweep** | 91/100 | Positive | Filter trades by edge strength |

### Strategies That DON'T Work

| Strategy | Result | Why |
|----------|--------|-----|
| Mean Reversion | 0/432 profitable | Commissions and spreads turn breakeven into losses |
| Panic Fade (2026) | 0/100 in later tests | Market regime changed - no longer reliable |
| Dual Timeframe Trend | 0/100 | Conflicting signals cancel each other |
| Late Expensive Momentum | 36/100 | High fees on expensive contracts |
| BTC Dual Trend Confirmation | 0/300 | Over-filtering removes all edge |

---

## Detailed Strategy Analysis

### 1. Momentum Continuation (BEST PERFORMER)

**Concept:** Buy when spot price momentum, SMA, VWAP, 5-minute change, and 1-minute velocity ALL align near expiry.

**Backtest Results:**
- 100/100 variants profitable
- ROI: +$1,131.82 to +$3,540.82 (on $10k notional)
- Win rate: 52.8% - 76.9%
- Best: +18.32% ROI with panic_threshold=0.04, fade_size=100

**Implementation:**
```kotlin
// Entry conditions (all must be true):
val spotMomentumPositive = coinbase5mChange > 0 && coinbase1mVelocity > 0
val smaAligned = currentPrice > sma20
val vwapAligned = currentPrice > vwap
val nearExpiry = timeToClose < 300_000L // 5 minutes
val momentumConfirmed = sma20 > sma50

if (spotMomentumPositive && smaAligned && vwapAligned && nearExpiry && momentumConfirmed) {
    // Buy YES
}
```

**Key Parameters:**
- Entry ceiling: $0.65 (best P&L despite 50.4% win rate)
- Higher ceilings ($0.85) have better win rates (55.5%) but lower net P&L
- Position size: 5-10 contracts
- Exit: Near settlement or at profit target

### 2. Selective EMA Momentum

**Concept:** Use EMA crossovers with selective entry bands.

**Backtest Results:**
- 99/100 variants profitable
- ROI: -$4.94 to +$122.86
- Works best with tight entry bands

**Implementation:**
```kotlin
val emaFast = calculateEMA(9)  // 9-period EMA
val emaSlow = calculateEMA(21) // 21-period EMA
val emaCrossedUp = emaFast > emaSlow
val priceInBand = price in 0.45..0.55

if (emaCrossedUp && priceInBand) {
    // Buy YES
}
```

### 3. BTC VWAP Directional

**Concept:** Trade when Kalshi contract price is above/below VWAP (Volume Weighted Average Price) with trend confirmation.

**Backtest Results:**
- 100/100 variants profitable
- Works in all tested configurations

**Implementation:**
```kotlin
val vwap = calculateVWAP(period = 50)
val priceAboveVWAP = currentPrice > vwap
val trendUp = sma10 > sma20

if (priceAboveVWAP && trendUp) {
    // Buy YES (expecting continuation)
}
```

### 4. Momentum Tight Band

**Concept:** Use narrow price bands (0.45-0.55) with momentum confirmation.

**Backtest Results:**
- 100/100 variants profitable
- ROI: +$2,252 - +$4,516 (depending on position size)
- Win rate: 55.0% - 60.7%

**Key Finding:** Wider bands (0.35-0.65) earned MORE than narrow bands despite lower win rates:
- 10 contracts / 0.35-0.65: +$4,516.86, 55.0% WR, -$312.99 max DD
- 5 contracts / 0.35-0.65: +$4,298.37, 59.7% WR, -$148.70 max DD
- 10 contracts / 0.45-0.55: +$2,781.79, 59.9% WR, -$160.69 max DD

### 5. MACD Threshold Strategy

**Concept:** Use MACD histogram thresholds for entry confirmation.

**Backtest Results:**
- 10/10 variants profitable
- Best: +$75.38 with MACD histogram threshold = 2.5 (1m) / 0 (5m)
- Win rate: 57.8%, Max DD: -$11.51
- Highest win rate: 76.9% with thresholds 20/10, but lowest P&L ($18.08)

**Key Insight:** Stricter MACD thresholds improve win rate but reduce total P&L. Choose based on risk tolerance.

---

## Strategy Comparison Matrix

| Strategy | Win Rate | Avg P&L | Max DD | Trades | Best For |
|----------|----------|---------|--------|--------|----------|
| Momentum Continuation | 52.8-76.9% | +$1,131-3,540 | Varies | 1,138-1,697 | High conviction, near expiry |
| Selective EMA | ~50-60% | -$4.94 to +$122 | Low | ~1,000 | Moderate risk |
| BTC Spot Momentum | ~50-55% | -$1.70 to +$24 | Low | ~2,900 | Frequent trading |
| VWAP Directional | >50% | Positive | Low | ~3,000 | Trend following |
| Tight Band Momentum | 55-60% | +$2,252-4,516 | -$148-312 | 2,700-7,666 | Balanced risk/reward |
| MACD Threshold | 52.8-76.9% | +$18-75 | -$3-24 | 78-1,697 | Precision entries |
| Gap Fade | >50% | Positive | Moderate | ~2,000 | Mean reversion |

---

## What Doesn't Work (And Why)

### Mean Reversion - 0/432 Profitable

**Why it fails:**
- Kalshi 15m markets have short time to settlement
- Fees (0.07 * C * P * (1-P)) eat into small mean-reversion profits
- Spread costs on thin books
- Price often doesn't have time to revert before settlement

**Tested Parameters:**
- Entry bands: 0.10 to 0.40
- Exit bands: 0.50 to 0.90
- Cooldowns: up to 30 minutes
- Result: Mean ROI -8.12%, Best variant -1.29%, Worst -14.26%

### Panic Fade - Inconsistent Results

**April 2026:** 93/96 variants profitable, +18.32% best ROI
**Later Tests:** 0/100 variants profitable, -$5.39 to -$27.23 P&L

**Why the change:**
- Market regime shifted
- Different execution assumptions
- Changed fee handling
- The April winner used panic_threshold=0.04, fade_size=100

**Conclusion:** Panic fade is NOT a dependable strategy. It worked in one specific window with one configuration.

### Dual Timeframe Trend - 0/300 Profitable

**Why it fails:**
- Conflicting signals from different timeframes
- Over-filtering removes all trading opportunities
- Fees accumulate with high turnover

---

## Key Success Factors

### 1. Entry Timing Matters Most

**Best Windows:**
- Last 5 minutes (300,000ms) before settlement
- Last 6 minutes (360,000ms) with minimum 1 minute buffer
- Avoid: First 10 minutes (thin books, high spreads)

### 2. Price Bands Impact P&L More Than Win Rate

**Finding:** Lower price ceilings produce better net P&L despite lower win rates:

| Entry Ceiling | P&L | Trades | Win Rate | Max DD |
|---------------|-----|--------|----------|--------|
| $0.65 | +$36.28 | 3,448 | 50.4% | -$28.99 |
| $0.70 | +$21.58 | 3,952 | 51.1% | -$32.41 |
| $0.75 | +$23.79 | 4,407 | 52.3% | -$30.19 |
| $0.85 | +$17.18 | 5,200 | 55.5% | -$36.85 |

**Insight:** Cheaper entries leave more room for profit even with slightly lower win rates.

### 3. Position Sizing Tradeoffs

**5 contracts vs 10 contracts with 0.35-0.65 band:**
- 10 contracts: +$4,516.86, 55.0% WR, -$312.99 max DD
- 5 contracts: +$4,298.37, 59.7% WR, -$148.70 max DD

**Conclusion:** Smaller positions reduce drawdown significantly with only slight P&L reduction.

### 4. Exit Strategy is Critical

**Profit/Stop Exits:**
- Early entry strategies perform best with profit targets and stop losses
- Late momentum lost money despite 85% win rate due to poor exits

**Near-Settlement Exits:**
- Acceleration momentum: +$1,894.39 (best with settlement exits)
- VWAP family: +$1,105.50 but -$1,751.92 max drawdown

**Insight:** Exit strategy can completely change the profitability of an entry signal.

---

## Recommended Strategy Stack

Based on the research, here's a **diversified stack** of strategies that work together:

### Primary Strategies (Highest Conviction)

1. **Momentum Continuation** (40% allocation)
   - Entry: All signals aligned (spot, SMA, VWAP, velocity) near expiry
   - Exit: Near settlement
   - Position: 5 contracts
   - Expected: High P&L, moderate win rate

2. **Momentum Tight Band** (30% allocation)
   - Entry: Price in 0.45-0.55 range with momentum
   - Exit: Profit target or stop loss
   - Position: 5 contracts
   - Expected: Steady P&L, good win rate

3. **BTC VWAP Directional** (20% allocation)
   - Entry: Price above/below VWAP with trend
   - Exit: Trend reversal or settlement
   - Position: 3 contracts
   - Expected: Frequent trades, low risk

### Secondary Strategies (Opportunistic)

4. **MACD Threshold** (10% allocation)
   - Entry: MACD histogram > 2.5 with price confirmation
   - Exit: MACD signal reversal
   - Position: 2 contracts
   - Expected: High precision, fewer trades

---

## Implementation Guidelines

### Risk Management

1. **Position Sizing:**
   - Max 10 contracts per trade
   - Scale down during losing streaks
   - Scale up during winning streaks (max +2 contracts)

2. **Drawdown Limits:**
   - Portfolio-level: -$50 max drawdown
   - Strategy-level: -$20 max drawdown per strategy
   - Kill switch: Stop all trading if portfolio DD > -$100

3. **Win Rate Monitoring:**
   - Pause strategy if win rate < 45% over 20 trades
   - Resume when win rate recovers > 50% over 10 trades

### Execution

1. **Order Types:**
   - Use LIMIT orders only (never market orders)
   - Price: Best bid/ask within strategy band
   - Time-in-force: GTC (Good Till Cancelled)

2. **Fee Considerations:**
   - Model fees: 0.07 * contracts * price * (1-price)
   - Round up to nearest cent
   - Include in all P&L calculations

3. **Slippage:**
   - Assume 1-2 cent slippage on fills
   - More on thin books (spread > 4 cents)

### Backtesting Requirements

1. **Data Quality:**
   - Use Level 2 order book data (not last-trade prices)
   - Include historical fills and partial fills
   - Model realistic slippage

2. **Time Periods:**
   - Minimum 30 days of historical data
   - Test on multiple market regimes
   - Include holdout period for validation

3. **Metrics to Track:**
   - Gross P&L vs Net P&L (after fees)
   - Win rate
   - Max drawdown
   - Trade frequency
   - Average win/loss

---

## Strategy Code Implementation

### Momentum Continuation Strategy (Kotlin)

```kotlin
class MomentumContinuationStrategy(
    private val market: MarketUiModel,
    private val coinbaseData: CoinbaseSpotData,
    private val nowMs: Long
) {
    
    data class Signal(
        val direction: String,  // "YES", "NO", or null
        val confidence: Double, // 0.0 to 1.0
        val reason: String
    )
    
    fun evaluate(): Signal {
        val closeTime = market.closeTimeEpochMs ?: return Signal(null, 0.0, "No close time")
        val timeToClose = closeTime - nowMs
        
        // Only trade in last 5 minutes, with at least 1 minute buffer
        if (timeToClose !in 60_000L..300_000L) {
            return Signal(null, 0.0, "Not in trading window")
        }
        
        val price = market.yesProbabilityPercent ?: return Signal(null, 0.0, "No price")
        val priceDollars = price / 100.0
        
        // Check spot momentum
        val coinbase5mChange = coinbaseData.change5m ?: return Signal(null, 0.0, "No spot data")
        val coinbase1mVelocity = coinbaseData.velocity1m ?: return Signal(null, 0.0, "No spot data")
        
        val spotUp = coinbase5mChange > 0 && coinbase1mVelocity > 0
        val spotDown = coinbase5mChange < 0 && coinbase1mVelocity < 0
        
        // Check technical indicators
        val sma20 = market.sma20 ?: return Signal(null, 0.0, "No SMA data")
        val sma50 = market.sma50 ?: return Signal(null, 0.0, "No SMA data")
        val vwap = market.vwap ?: return Signal(null, 0.0, "No VWAP data")
        
        val smaBullish = priceDollars > sma20 && sma20 > sma50
        val smaBearish = priceDollars < sma20 && sma20 < sma50
        
        val aboveVWAP = priceDollars > vwap
        val belowVWAP = priceDollars < vwap
        
        // Entry conditions
        val buyYesConditions = spotUp && smaBullish && aboveVWAP
        val buyNoConditions = spotDown && smaBearish && belowVWAP
        
        if (buyYesConditions && priceDollars <= 0.65) {
            return Signal("YES", 0.8, "Momentum continuation UP")
        }
        
        if (buyNoConditions && priceDollars >= 0.35) {
            return Signal("NO", 0.8, "Momentum continuation DOWN")
        }
        
        return Signal(null, 0.0, "No signal")
    }
}
```

### MACD Threshold Strategy

```kotlin
class MACDStrategy(
    private val market: MarketUiModel,
    private val macdCalculator: MACDCalculator,
    private val nowMs: Long
) {
    
    data class Config(
        val histogramThreshold1m: Double = 2.5,
        val histogramThreshold5m: Double = 0.0,
        val maxAsk: Double = 0.65
    )
    
    fun evaluate(config: Config = Config()): Signal {
        val closeTime = market.closeTimeEpochMs ?: return Signal(null, 0.0, "No close time")
        val timeToClose = closeTime - nowMs
        
        // Only trade with enough time
        if (timeToClose < 60_000L) {
            return Signal(null, 0.0, "Too close to settlement")
        }
        
        val price = market.yesProbabilityPercent ?: return Signal(null, 0.0, "No price")
        val priceDollars = price / 100.0
        
        // Check MACD
        val macd1m = macdCalculator.calculate1m(market)
        val macd5m = macdCalculator.calculate5m(market)
        
        val histogram1m = macd1m?.histogram ?: return Signal(null, 0.0, "No MACD data")
        val histogram5m = macd5m?.histogram ?: return Signal(null, 0.0, "No MACD data")
        
        val macdBullish = histogram1m > config.histogramThreshold1m && 
                        histogram5m > config.histogramThreshold5m
        val macdBearish = histogram1m < -config.histogramThreshold1m && 
                        histogram5m < -config.histogramThreshold5m
        
        if (macdBullish && priceDollars <= config.maxAsk) {
            return Signal("YES", 0.7, "MACD bullish crossover")
        }
        
        if (macdBearish && priceDollars >= (1.0 - config.maxAsk)) {
            return Signal("NO", 0.7, "MACD bearish crossover")
        }
        
        return Signal(null, 0.0, "No signal")
    }
}
```

### VWAP Directional Strategy

```kotlin
class VWAPStrategy(
    private val market: MarketUiModel,
    private val nowMs: Long
) {
    
    fun evaluate(): Signal {
        val closeTime = market.closeTimeEpochMs ?: return Signal(null, 0.0, "No close time")
        val timeToClose = closeTime - nowMs
        
        // Trade throughout the window, but avoid very end
        if (timeToClose < 30_000L) {
            return Signal(null, 0.0, "Too close to settlement")
        }
        
        val price = market.yesProbabilityPercent ?: return Signal(null, 0.0, "No price")
        val priceDollars = price / 100.0
        val vwap = market.vwap ?: return Signal(null, 0.0, "No VWAP data")
        
        // Trend indicators
        val sma10 = market.sma10 ?: return Signal(null, 0.0, "No SMA data")
        val sma20 = market.sma20 ?: return Signal(null, 0.0, "No SMA data")
        val trendUp = sma10 > sma20
        val trendDown = sma10 < sma20
        
        val aboveVWAP = priceDollars > vwap
        val belowVWAP = priceDollars < vwap
        
        // VWAP + Trend confirmation
        if (aboveVWAP && trendUp && priceDollars <= 0.70) {
            return Signal("YES", 0.6, "Above VWAP with uptrend")
        }
        
        if (belowVWAP && trendDown && priceDollars >= 0.30) {
            return Signal("NO", 0.6, "Below VWAP with downtrend")
        }
        
        return Signal(null, 0.0, "No signal")
    }
}
```

---

## Strategy Selection Framework

Use this framework to select which strategies to deploy:

### 1. Market Regime Detection

```kotlin
fun detectRegime(market: MarketUiModel, coinbaseData: CoinbaseSpotData): Regime {
    val volatility = calculateVolatility(market.oddsHistory)
    val trendStrength = calculateTrendStrength(market)
    val coinbaseTrend = coinbaseData.trend15m
    
    return when {
        volatility > 0.15 && trendStrength > 0.5 -> Regime.HIGH_VOLATILITY
        volatility < 0.05 -> Regime.LOW_VOLATILITY
        coinbaseTrend > 0.02 -> Regime.BULLISH
        coinbaseTrend < -0.02 -> Regime.BEARISH
        else -> Regime.NEUTRAL
    }
}
```

### 2. Strategy Activation by Regime

| Regime | Active Strategies | Weight |
|--------|-------------------|--------|
| HIGH_VOLATILITY | Momentum Continuation, MACD | 60%/40% |
| LOW_VOLATILITY | VWAP Directional, Gap Fade | 50%/50% |
| BULLISH | Momentum Continuation, Tight Band | 70%/30% |
| BEARISH | Momentum Continuation (NO), Tight Band | 70%/30% |
| NEUTRAL | All strategies | Equal |

### 3. Performance-Based Rotation

```kotlin
class StrategyRotator(
    private val strategies: List<TradingStrategy>,
    private val performanceTracker: PerformanceTracker
) {
    
    fun getActiveStrategies(): List<TradingStrategy> {
        // Get recent performance (last 20 trades per strategy)
        val recentPerformance = strategies.map { strategy ->
            val performance = performanceTracker.getRecentPerformance(strategy.id, 20)
            strategy to performance
        }
        
        // Filter out underperforming strategies
        val filtered = recentPerformance.filter { (_, performance) ->
            performance.winRate > 0.45 && performance.netPnl > -10.0
        }.map { it.first }
        
        // If all are underperforming, use all but with reduced size
        return if (filtered.isEmpty()) {
            strategies.onEach { it.currentSizeMultiplier = 0.5 }
        } else {
            filtered.onEach { it.currentSizeMultiplier = 1.0 }
        }
    }
}
```

---

## Monitoring and Optimization

### Key Metrics to Track

1. **Per-Strategy Metrics:**
   - Total P&L
   - Win rate
   - Max drawdown
   - Trade count
   - Avg win/loss
   - Sharpe ratio

2. **Portfolio Metrics:**
   - Total P&L
   - Max portfolio drawdown
   - Correlation between strategies
   - Overall win rate

3. **Execution Metrics:**
   - Fill rate
   - Average slippage
   - Fee impact
   - Latency

### Optimization Process

1. **Weekly Review:**
   - Check win rates and P&L for each strategy
   - Adjust weights based on performance
   - Remove strategies with consistent losses

2. **Monthly Backtest:**
   - Re-run all strategies on latest 30-day window
   - Validate that historical performance holds
   - Update parameters if needed

3. **Quarterly Deep Dive:**
   - Full parameter sweep
   - Test new strategy ideas
   - Update strategy stack

---

## Recommended Actions

### Immediate (Next 2 Weeks)

1. **Implement Momentum Continuation**
   - Highest historical performance
   - Clear entry/exit rules
   - Strong backtest results

2. **Add MACD Threshold**
   - High win rates
   - Low drawdowns
   - Good complement to momentum

3. **Deploy VWAP Directional**
   - Frequent trading opportunities
   - Low risk
   - Good fill rates

### Short Term (Next Month)

1. **Add Tight Band Momentum**
   - Test with different band widths
   - Optimize position sizing

2. **Implement Gap Fade**
   - With strict risk controls
   - Only in low volatility regimes

3. **Build Performance Dashboard**
   - Real-time P&L tracking
   - Win rate monitoring
   - Drawdown alerts

### Medium Term (Next Quarter)

1. **Develop Adaptive Strategy**
   - Auto-adjust parameters based on performance
   - Regime detection
   - Dynamic position sizing

2. **Test Cross-Asset Strategies**
   - BTC leading ETH/SOL
   - Correlation-based entries

3. **Implement Machine Learning**
   - Pattern recognition
   - Anomaly detection
   - Predictive modeling

---

## Risk Warnings

### Important Caveats

1. **Backtest vs Live:**
   - All results are from historical simulations
   - Live execution faces slippage, latency, partial fills
   - Market regimes can change

2. **Overfitting Risk:**
   - Strategies optimized on historical data may not work forward
   - Always test on holdout periods
   - Use walk-forward validation

3. **Fee Impact:**
   - Kalshi fees are significant (7% on taker orders)
   - High turnover strategies can lose to fees
   - Always include fees in calculations

4. **Market Risk:**
   - Prediction markets are zero-sum
   - Someone else's gain is your loss
   - Edge decays as more participants enter

### Disclaimer

This document is for **educational and research purposes only**. It is NOT investment advice. Trading prediction market contracts involves substantial risk, including the possible loss of the entire amount invested.

All backtest results are historical simulations. Past performance does not guarantee future results. Live trading results may differ significantly from backtest results due to execution quality, market impact, fees, slippage, and changing market conditions.

Always paper trade new strategies before risking real capital. Start with small position sizes. Monitor performance closely. Be prepared to stop trading if performance degrades.

---

## Sources

1. TurbineFi - [5,732 Kalshi Bitcoin 15-Minute Bot Backtests](https://www.turbinefi.com/blog/5732-backtests-kalshi-bitcoin-15-minute-bots-2026)
2. TurbineFi - [4,904 Strategy Backtest](https://www.turbinefi.com/blog/5000-strategy-backtest-kalshi-btc-15m)
3. Kalshi BackTest - [5 Kalshi Trading Strategies](https://kalshibacktest.com/resources/kalshi-trading-strategies)
4. Reddit - Various strategy discussions and backtest results

---

*Document generated: 2026-10-19*
*Based on research through: September 29, 2026*
