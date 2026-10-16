# Kalshi Odds App - Implementation Status Report

## Executive Summary

This document provides a complete audit of what has been implemented, what is wired and working, what exists but is not connected, and what remains to be done for the Kalshi prediction market trading application.

---

## 📋 User Requirements Status

### ✅ FULLY IMPLEMENTED & WORKING

| Requirement | Status | Implementation Details |
|-------------|--------|----------------------|
| **Paper Trading: $1,000 Start** | ✅ | `SignalConstants.PAPER_START_USD = 1000.0` |
| **Allow Negative Balances** | ✅ | `SignalConstants.PAPER_ALLOW_NEGATIVE = true` in PaperBook.kt |
| **Reset Functionality** | ✅ | `PaperBook.reset()` archives current state, starts fresh at $1,000 |
| **Stop Loss at 3% (not 20%)** | ✅ | `TrailingStop.kt` and `StopOrderManager.kt` updated from 20% to 3% |
| **Trailing Stop: Absolute 3¢** | ✅ | Trailing stop uses cents, not percentage. At 90¢ → stop at 87¢ |
| **Stop Orders** | ✅ | `StopOrderManager.kt` with auto-execution |
| **Winning Strategy** | ✅ | `WinningStrategy.kt` with cheap sides + time window filter |

### ⚠️ PARTIALLY IMPLEMENTED (Code Exists, Not Wired)

| Requirement | Status | Files Created | What's Missing |
|-------------|--------|---------------|----------------|
| **Settlement Arbitrage Strategies** | ⚠️ | 6 files on main, 17 on branch | Not integrated with live trading |
| **WebSocket Integration** | ⚠️ | `SettlementWebSocketManager.kt`, `CFBenchmarksWebSocketClient.kt` | Not instantiated in app |
| **AI Learning from Trades** | ⚠️ | Adaptive logic exists | Not fully connected to strategy adjustment |

---

## 🗂️ Current Branch State

### Main Branch (Production)

**Latest Commits:**
- `18c0a4f` - Fix stop loss: change from 20% to 3% for Kalshi prediction markets
- `8e2cc6b` - Fix trailing stop: use absolute cents (3c) not percentage
- `6cc0f1e` - Add trailing stop and stop-loss for paper trading
- `04960db` - Update paper trading: $1000 start, allow negative, adaptive learning
- `e0865ea` - Add winning strategy: cheap sides + time window filter

**Settlement Files on Main (6 files):**
1. `FairAnchoredQuotingStrategy.kt` - Rank 4 strategy
2. `HourOfDayVolatilityStrategy.kt` - Rank 6 strategy
3. `LeanAgainstTakerJumpsStrategy.kt` - Rank 7 strategy
4. `LiquidityIncentiveProgramStrategy.kt` - Rank 5 strategy
5. `ReferencePriceErrorStrategy.kt` - Rank 2 strategy
6. `SettlementWebSocketManager.kt` - WebSocket coordinator

### vibe/settlement-arbitrage-implementation Branch (Feature Branch)

**Additional Files (17 total in settlement package):**
1. `AveragingAwareStrategy.kt` - **Rank 1** (Core: 1-4¢ edge expected)
2. `BRTIConstants.kt` - Constants from research (σ multipliers, fee tables)
3. `BRTIPrint.kt` - Data classes for BRTI prints and windows
4. `BRTISettlementTracker.kt` - Core engine: real-time print tracking, probability calculation
5. `CFBenchmarksWebSocketClient.kt` - WebSocket for `cfbenchmarks_value` feed
6. `FeeTypeVerifier.kt` - KXBTC15M maker fee verification
7. `MakerStrategy.kt` - **Rank 3** (sub-cent to 1¢ edge)
8. `package.kt` - Package documentation
9. `QueuePositionMonitor.kt` - Front-of-queue tracking
10. `SettlementModule.kt` - Dependency injection factory
11. `SettlementStrategyManager.kt` - Coordinates all strategies

**Plus the 6 files already on main.**

---

## 🎯 Strategy Implementation Matrix

### Rank 1-4: Core Settlement Arbitrage (HIGH PRIORITY)

| Rank | Strategy | Edge Estimate | Status | File | Key Method |
|------|----------|---------------|--------|------|------------|
| 1 | Averaging-aware late favourite | 1-4¢/contract | ✅ Created | `AveragingAwareStrategy.kt` | `evaluate()`, `createTicket()` |
| 2 | Reference price errors | Large (rare) | ✅ Created | `ReferencePriceErrorStrategy.kt` | `diagnoseMarketPricing()` |
| 3 | Resting favourite-side bids (maker) | Sub-cent to 1¢ | ✅ Created | `MakerStrategy.kt` | `shouldQuote()`, front-of-queue |
| 4 | Fair-anchored quoting | ~0.35¢ | ✅ Created | `FairAnchoredQuotingStrategy.kt` | `calculateDynamicSpread()` |

### Rank 5-7: Secondary Strategies (MEDIUM PRIORITY)

| Rank | Strategy | Edge Estimate | Status | File | Key Method |
|------|----------|---------------|--------|------|------------|
| 5 | Liquidity Incentive Program | Unknown | ✅ Created | `LiquidityIncentiveProgramStrategy.kt` | `getLIPEligibility()` |
| 6 | Hour-of-day volatility | <1¢ | ✅ Created | `HourOfDayVolatilityStrategy.kt` | `calculateImpliedVolatility()` |
| 7 | Lean against taker jumps | <1% probability | ✅ Created | `LeanAgainstTakerJumpsStrategy.kt` | `detectJump()`, `SpotTradeHistory` |

### Rank 8: AVOID

| Rank | Strategy | Status |
|------|----------|--------|
| 8 | Taker latency arbitrage | ❌ Not implemented (losing strategy for retail) |

---

## 🏗️ Architecture Components

### ✅ Core Infrastructure (Working)

| Component | File | Status |
|-----------|------|--------|
| Paper Trading Engine | `PaperBook.kt` | ✅ Working |
| Stop Order Management | `StopOrderManager.kt` | ✅ Working |
| Trailing Stop Logic | `TrailingStop.kt` | ✅ Working (3¢ absolute) |
| Winning Strategy | `WinningStrategy.kt` | ✅ Working |
| Signal Constants | `SignalConstants.kt` | ✅ Working |

**Paper Trading Configuration:**
```kotlin
// From SignalConstants.kt
const val PAPER_START_USD = 1000.0
const val PAPER_STAKE_USD = 5.0
const val PAPER_ALLOW_NEGATIVE = true  // Can go negative
const val PAPER_LEDGER_MAX = 1000
```

**Stop Configuration (from TrailingStop.kt and StopOrderManager.kt):**
```kotlin
const val TRAILING_STOP_CENTS = 3.0    // Absolute 3 cent trailing stop
const val STOP_LOSS_PCT = 3.0          // 3% stop loss (changed from 20%)
const val MIN_PROFIT_CENTS = 0.0       // Active from entry
```

### ⚠️ Settlement Infrastructure (Created, Not Wired)

| Component | File | Purpose | Status |
|-----------|------|---------|--------|
| BRTI Constants | `BRTIConstants.kt` | σ multipliers, fee tables | ✅ Created |
| BRTI Print Data | `BRTIPrint.kt` | `BRTIPrint`, `BRTIWindow` data classes | ✅ Created |
| BRTI Settlement Tracker | `BRTISettlementTracker.kt` | Real-time print accumulation, probability | ✅ Created |
| CF Benchmarks WS Client | `CFBenchmarksWebSocketClient.kt` | `wss://ws.kalshi.com/v1/ws?channel=cfbenchmarks_value` | ✅ Created |
| Queue Position Monitor | `QueuePositionMonitor.kt` | Front-of-queue tracking | ✅ Created |
| Fee Type Verifier | `FeeTypeVerifier.kt` | KXBTC15M maker fee check | ✅ Created |
| Settlement Module | `SettlementModule.kt` | DI factory: `createSettlementStack()` | ✅ Created |
| Settlement Strategy Manager | `SettlementStrategyManager.kt` | Coordinates all 7 strategies | ✅ Created |
| Settlement WebSocket Manager | `SettlementWebSocketManager.kt` | Central WebSocket coordinator | ✅ Created |

---

## 🔌 Wiring Status

### ✅ WIRED & WORKING

1. **Paper Trading** - Fully integrated with UI
   - Reset button functional
   - Negative balances allowed
   - $1,000 starting amount
   - All fills tracked

2. **Stop Orders** - Integrated with PaperBook
   - Trailing stop (3¢ absolute) active
   - Stop loss (3%) active
   - Auto-execution enabled
   - Position state tracking

3. **Winning Strategy** - Integrated with market scanning
   - Cheap side filter (default: ≤40¢)
   - Time window filter (default: last 6 minutes)
   - Presets available (BACKTEST_WINNER, CHEAP_SIDE_ONLY, etc.)

### ❌ NOT WIRED (Code Exists, Not Connected)

1. **Settlement Strategies (All 7)**
   - Code complete in `settlement/` package
   - NOT connected to `DataViewModel` or `OddsViewModel`
   - NOT receiving live market data
   - NOT placing orders

2. **WebSocket Infrastructure**
   - `SettlementWebSocketManager` - Not instantiated
   - `CFBenchmarksWebSocketClient` - Not connected
   - `SpotPriceWebSocketClient` - Not on main branch (only on feature branch)

3. **BRTI Settlement Tracker**
   - Not receiving live BRTI prints
   - Not calculating settlement probabilities
   - Not feeding strategies

4. **Queue Position Monitoring**
   - Placeholder implementation
   - Needs real Kalshi API (`get-order-queue-position` endpoint)

5. **LIP Eligibility Checking**
   - Placeholder implementation
   - Needs real Kalshi API (market-page eligibility)

6. **AI Learning from Trades**
   - Adaptive logic exists in concept
   - Not connected to strategy adjustment
   - No feedback loop implemented

---

## 📊 What's Working (Verified)

### Paper Trading System
```
✅ Start with $1,000
✅ Allow negative balances (PAPER_ALLOW_NEGATIVE = true)
✅ Reset functionality (archives current, starts fresh)
✅ Track all fills
✅ Calculate P&L
✅ Display equity = cash + open stake
```

### Stop Order System
```
✅ Trailing stop: 3¢ absolute (not percentage)
   - YES position: stop = peakPrice - 0.03
   - NO position: stop = lowPrice + 0.03
✅ Stop loss: 3% (not 20%)
   - YES position: stop = entryPrice * 0.97
   - NO position: stop = (1 - entryPrice) * 1.03
✅ Auto-execution when stops hit
✅ Position state tracking with stops
```

### Winning Strategy
```
✅ Filter: cheap sides only (ask ≤ maxAskFilter)
✅ Filter: time window (last N minutes before close)
✅ Filter: minimum profit target
✅ Presets for different configurations
✅ Integrated with market scanning
```

---

## ❌ What's NOT Working

### Settlement-Based Trading
- ❌ BRTI prints not being received
- ❌ Settlement probabilities not calculated
- ❌ No settlement-aware signals generated
- ❌ Strategies not evaluating markets

### WebSocket Connections
- ❌ CF Benchmarks feed not connected
- ❌ Kalshi market data WebSocket not active
- ❌ Spot price WebSocket not connected

### Queue Position
- ❌ No real queue position data
- ❌ Maker strategies can't verify front-of-queue
- ❌ Can't implement "quote only at front" rule

### LIP Program
- ❌ No LIP eligibility checking
- ❌ No reward pool information
- ❌ Can't verify if KXBTC15M has maker fees

### AI Learning
- ❌ No trade history analysis
- ❌ No strategy adjustment based on results
- ❌ No feedback loop

---

## 🎯 Immediate Next Steps (Priority Order)

### Priority 1: Wire Settlement Infrastructure

**Task: Instantiate SettlementWebSocketManager in AppContainer**

```kotlin
// In AppContainer.kt or similar
val settlementTracker = BRTISettlementTracker()
val spotPriceTracker = SpotPriceTracker()
val spotTradeHistory = SpotTradeHistory()

val settlementWebSocketManager = SettlementWebSocketManager(
    okHttpClient = okHttpClient,
    tradeApi = tradeApi,
    credentials = { apiKey to secret },
    settlementTracker = settlementTracker,
    spotPriceTracker = spotPriceTracker,
    spotTradeHistory = spotTradeHistory
)

// Start when app initializes
settlementWebSocketManager.start()
```

**Task: Connect to live BRTI feed**
```kotlin
// In SettlementWebSocketManager.start()
cfBenchmarksClient = CFBenchmarksWebSocketClient(
    okHttpClient = okHttpClient,
    callback = { brtiPrint -> settlementTracker.onBRTIPrint(brtiPrint) }
)
cfBenchmarksClient?.connect()
```

### Priority 2: Wire Strategies to Market Scanning

**Task: Integrate SettlementStrategyManager with existing scanning**
```kotlin
// In market scanning code (DataViewModel or similar)
val settlementManager = SettlementStrategyManager(
    settlementTracker = settlementTracker,
    queuePositionMonitor = QueuePositionMonitor(tradeApi),
    feeTypeVerifier = FeeTypeVerifier(tradeApi),
    strategies = listOf(
        AveragingAwareStrategy(),
        ReferencePriceErrorStrategy(),
        MakerStrategy(),
        FairAnchoredQuotingStrategy(),
        LiquidityIncentiveProgramStrategy(),
        HourOfDayVolatilityStrategy(),
        LeanAgainstTakerJumpsStrategy()
    )
)

// When evaluating a market
val signal = settlementManager.getBestSignal(market, currentBRTI, nowMs)
signal?.let { 
    // Create ticket or place order
}
```

### Priority 3: Verify Fee Structure

**Task: Check KXBTC15M fee type before trading**
```kotlin
// Before any maker strategy
val feeType = feeTypeVerifier.verifyKXBTC15MFeeType()
if (feeType.hasMakerFee) {
    // Maker fee = ceil(0.0175 * C * P * (1-P))
    // At 50¢: 0.44¢ per contract
    // This could wipe out the +0.35¢ front-of-queue edge
    // Consider disabling maker strategies
}
```

### Priority 4: Implement AI Learning Loop

**Task: Add trade result analysis and strategy adjustment**
```kotlin
// Track trade outcomes
class TradeHistoryAnalyzer {
    fun recordTrade(fill: PaperFill, outcome: TradeOutcome) {
        // Store trade with result
    }
    
    fun adjustStrategyConfig(strategy: BaseStrategy, recentResults: List<TradeResult>) {
        // Adjust parameters based on performance
        // Example: If losing on late entries, tighten time window
        // If winning on cheap sides, widen cheap filter
    }
}
```

---

## 📈 Expected Performance (From Research)

### Settlement Arbitrage (Rank 1-4)

| Strategy | Expected Edge | Frequency | Notes |
|----------|---------------|-----------|-------|
| Averaging-aware late favourite | 1-4¢/contract | Few trades/day | Best opportunity |
| Reference price errors | Large | Rare | If market uses spot instead of average |
| Resting favourite bids (maker) | Sub-cent to 1¢ | Multiple fills | Zero fee if no maker fee |
| Fair-anchored front-of-queue | ~0.35¢ | Per fill | Only works at front of queue |

### Reality Check

**Taker fees peak at 1.75¢ at 50¢, 0.63¢ at 90¢, 0.33¢ at 95¢**

- At 50¢: Taker fee = 1.75¢ (wipes out most edges)
- At 70¢: Taker fee = 1.47¢
- At 90¢: Taker fee = 0.63¢ (manageable)
- At 95¢: Taker fee = 0.33¢ (best for taker strategies)

**Key Insight: Trade at high prices (≥90¢) where fees are low**

### Capacity Limits

- Thin books near close limit fill sizes
- Expect tens to hundreds of contracts per trade
- Edge disappears with any queue ahead (for maker strategies)

---

## 🔍 Verification Checklist

### Math Verification (User Request: "All your math is correct")

| Calculation | Status | Verification |
|-------------|--------|---------------|
| Settlement probability | ✅ | Uses normal distribution Φ((E-K)/sd) |
| Effective price E | ✅ | E = (Σₖ + m·S_now)/60 |
| Settlement variance | ✅ | σ²·m(m+1)(2m+1)/21,600 |
| Taker fee formula | ✅ | ceil(0.07·C·P·(1-P)) |
| Maker fee formula | ✅ | ceil(0.0175·C·P·(1-P)) |
| Trailing stop calculation | ✅ | Absolute cents, not percentage |
| Stop loss calculation | ✅ | 3%, not 20% |

### Code Quality

| Aspect | Status |
|--------|--------|
| Type safety | ✅ | All data classes use proper types |
| Null safety | ✅ | Nullable types where appropriate |
| Coroutine safety | ✅ | Uses StateFlow, synchronized blocks |
| Error handling | ⚠️ | Basic, needs enhancement for production |
| Logging | ⚠️ | Minimal, needs better diagnostics |
| Testing | ⚠️ | Unit tests for BRTISettlementTracker, needs more |

---

## 📱 APK Build Status

### Current State
- ✅ Branch APK builds configured (build-apk.yml)
- ✅ Per-branch release tags working
- ✅ Phone-compatible model trainer (128 tree)

### To Build APK

```bash
# Build debug APK
./gradlew assembleDebug

# APK location
# app/build/outputs/apk/debug/app-debug.apk

# Or use the GitHub Actions workflow
# Runs on all branches with per-branch release tags
```

### Package ID
- Current: `com.dirk.kalshiodds`
- User requested: "app needs its own name and package ID so I can install next to others"
- **ACTION NEEDED**: Create new package ID for parallel installation

---

## 🚀 Delivery Plan

### Phase 1: Quick Wins (1-2 days)
1. ✅ Paper trading with $1,000 start, negative allowed, reset - **DONE**
2. ✅ Stop loss at 3% - **DONE**
3. ✅ Trailing stop at 3¢ absolute - **DONE**
4. ✅ Winning strategy (cheap sides + time window) - **DONE**

### Phase 2: Settlement Integration (3-5 days)
1. ⏳ Wire SettlementWebSocketManager to app initialization
2. ⏳ Connect CFBenchmarksWebSocketClient to live feed
3. ⏳ Feed BRTI prints to BRTISettlementTracker
4. ⏳ Calculate settlement probabilities in real-time
5. ⏳ Display settlement info in UI

### Phase 3: Strategy Activation (5-7 days)
1. ⏳ Wire all 7 strategies to SettlementStrategyManager
2. ⏳ Connect manager to market scanning
3. ⏳ Implement order placement from strategies
4. ⏳ Add strategy enable/disable UI
5. ⏳ Display strategy signals and reasoning

### Phase 4: Advanced Features (7-10 days)
1. ⏳ Implement queue position API
2. ⏳ Verify KXBTC15M fee type
3. ⏳ Implement LIP eligibility checking
4. ⏳ Add AI learning from trade history
5. ⏳ Create performance dashboard

### Phase 5: Polish & Testing (Ongoing)
1. ⏳ Build APK with new package ID
2. ⏳ Test on phone
3. ⏳ Monitor live performance
4. ⏳ Tune strategy parameters
5. ⏳ Add backtest framework

---

## 📝 Summary

### What's Done
- ✅ Paper trading: $1,000 start, negative allowed, reset works
- ✅ Stop orders: 3% stop loss, 3¢ trailing stop (absolute)
- ✅ Winning strategy: cheap sides + time window filter
- ✅ All 7 settlement strategies coded (on feature branch)
- ✅ WebSocket infrastructure coded (on feature branch)

### What's Missing
- ❌ Settlement strategies not wired to live data
- ❌ WebSockets not connected
- ❌ Queue position monitoring not implemented
- ❌ LIP eligibility not verified
- ❌ AI learning loop not closed
- ❌ New package ID for parallel APK installation

### What's Next
1. **Merge vibe/settlement-arbitrage-implementation to main** (or cherry-pick files)
2. **Wire SettlementWebSocketManager to app initialization**
3. **Connect strategies to market scanning**
4. **Build APK with new package ID**

### Blockers
- Need to verify KXBTC15M fee type (affects maker strategy viability)
- Need real queue position API (for front-of-queue verification)
- Need LIP eligibility API (for Rank 5 strategy)

---

## 🎯 Recommendation

**Focus on Phase 1-2 first:** The settlement arbitrage strategies (especially Rank 1) represent the best opportunity based on the research. The code is written, it just needs to be wired up.

**Start with these 3 files to merge from the feature branch:**
1. `BRTISettlementTracker.kt` - Core probability calculation
2. `CFBenchmarksWebSocketClient.kt` - Live BRTI feed
3. `AveragingAwareStrategy.kt` - Rank 1 strategy (highest expected edge)

**Then add the wiring:**
1. Instantiate in `AppContainer.kt`
2. Start WebSocket connections on app launch
3. Feed data to settlement tracker
4. Connect strategy manager to market scanning

**The rest can follow incrementally.**

---

*Last Updated: October 16, 2026*
*Status: Ready for Phase 2 implementation*
