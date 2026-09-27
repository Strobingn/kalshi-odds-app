package com.dirk.kalshiodds.signal.config

/**
 * Shared defaults for the predictability + decision-support + ticket stack.
 * Orders are never placed except via an explicit in-app Approve on a ticket.
 */
object SignalConstants {
    /** Last ~3 minutes before settlement uses the late-window blend. */
    const val LATE_TTE_MS = 180_000L

    /** Short BTC→ETH/SOL (and reverse) lead window. */
    const val LEAD_WINDOW_MS = 3_000L

    /** Follower lookback; leader move is measured just before this. */
    const val LAG_OFFSET_MS = 400L

    const val MIN_CALIBRATION_SAMPLES = 20
    const val RELIABILITY_BINS = 10

    const val DEFAULT_MIN_CONFIDENCE = 0.45
    const val DEFAULT_MIN_LIQUIDITY = 500.0
    const val DEFAULT_MAX_SPREAD_CENTS = 8.0
    const val DEFAULT_HIDE_WEAK = true

    /**
     * Entry filter ([com.dirk.kalshiodds.signal.engine.EntryFilter],
     * docs/ml-review-2026-09-27.md #7). A guard, not a discovered edge:
     * out of sample the first-minutes entries (14–12m left, −4.5% ROI) and
     * near-strike picks (<5bp, −7.7% ROI) lost most, but neither CI
     * excludes zero. Mirrored in tools/backtest/pipeline.py.
     */
    const val DEFAULT_ENTRY_FILTER = true
    const val DEFAULT_ENTRY_MIN_ELAPSED_MINUTES = 3
    const val DEFAULT_ENTRY_MIN_STRIKE_DISTANCE_BP = 5.0
    const val DEFAULT_ENTRY_NEAR_STRIKE_OVERRIDE_PP = 10.0

    const val SCORECARD_ROLLING_DAYS = 7

    /** Size drop (contracts) treated as a cancel spike. */
    const val CANCEL_SPIKE_SIZE = 8.0

    const val DEPTH_NEAR_CENTS = 3.0
    const val DEPTH_FAR_CENTS = 15.0

    // --- v0.2.1 decision support (advisory only) ---

    /** Default advisory bankroll used only for suggested size (not the paper book). */
    const val DEFAULT_BANKROLL_USD = 1_000.0

    /** Isolated paper book — never hits Kalshi. Visible on the home screen. */
    const val PAPER_START_USD = 100.0
    const val PAPER_STAKE_USD = 5.0
    const val DEFAULT_PAPER_TRADING = true
    const val PAPER_LEDGER_MAX = 40

    /**
     * Fraction of full Kelly actually risked. 0.25 = quarter-Kelly,
     * a common conservative default for noisy edges.
     */
    const val DEFAULT_KELLY_FRACTION = 0.25

    /** Hard cap on bankroll committed to one suggested clip. */
    const val DEFAULT_MAX_BANKROLL_FRACTION = 0.05

    /** Fixed-fraction mode: percent of bankroll per idea. */
    const val DEFAULT_FIXED_FRACTION = 0.02

    /**
     * Default Kalshi-style taker fee coefficient.
     * See [com.dirk.kalshiodds.signal.sizing.NetExpectedValue] for the model.
     */
    const val DEFAULT_FEE_RATE = 0.07

    const val DEFAULT_EDGE_THRESHOLD_PP = 5.0

    const val DEFAULT_AUTO_TUNE = true
    const val DEFAULT_AUTO_TUNE_OVERRIDE = false
    const val AUTO_TUNE_MIN_SAMPLES = 30

    const val DEFAULT_OPPORTUNITY_ALERTS = true
    const val DEFAULT_OPPORTUNITY_QUIET = false
    const val OPPORTUNITY_DEDUPE_MS = 15 * 60_000L

    const val DEFAULT_CLOUD_SYNC = true

    const val SCORECARD_BUCKET_MIN_SAMPLES = 20

    const val GITHUB_OWNER = "Strobingn"
    const val GITHUB_REPO = "kalshi-odds-app"
    /**
     * Rolling release the Claude app's "Get latest model" reads
     * (`.github/workflows/train-edge-model.yml`). `edge-model-latest`
     * belongs to the main app.
     */
    const val EDGE_MODEL_RELEASE_TAG = "edge-model-claude"

    const val DEFAULT_RANK_BY_NET_EV = true
    const val DEFAULT_AUTO_MUTE = true

    /** Mute / downrank when rolling hit rate is below this (and enough samples). */
    const val DEFAULT_MUTE_HIT_RATE_FLOOR = 0.40

    /** Minimum settled samples in a bucket before auto-mute can fire. */
    const val MIN_MUTE_SAMPLES = 8

    const val DEFAULT_STREAK_PAUSE_N = 4
    const val DEFAULT_DRAWDOWN_USD = 50.0
    const val DEFAULT_RESUME_ON_NEW_SESSION = true

    const val MIN_ADAPTER_SAMPLES = 8
    const val ADAPTER_LEARNING_RATE = 0.08
    const val ADAPTER_WEIGHT_EMA = 0.15

    const val EXTERNAL_CACHE_MS = 25_000L
    const val EXTERNAL_CONNECT_TIMEOUT_MS = 3_000L
    const val EXTERNAL_READ_TIMEOUT_MS = 4_000L

    // --- Streamed spot (Coinbase WebSocket; docs/ml-review-2026-09-27.md #6) ---

    /** Settings → "Stream spot (Coinbase WebSocket)". REST stays the fallback. */
    const val DEFAULT_SPOT_STREAM = true

    /** Streamed spot with no print / heartbeat for this long is ignored (REST wins). */
    const val SPOT_STREAM_STALE_MS = 5_000L

    /** A spot print re-scores a market at most this often… */
    const val SPOT_RESCORE_MIN_INTERVAL_MS = 500L

    /** …and only when spot moved at least this far since that market's last score. */
    const val SPOT_RESCORE_MIN_MOVE_BP = 1.0

    // --- v0.2.2 approve-gated tickets ---

    /**
     * Hard all-in cap on a live Approve, including Kalshi fees.
     * `count` is the largest integer with count×P + fee ≤ this.
     */
    const val LIVE_ALL_IN_CAP_USD = 5.0

    /** Hide / disable a ticket when profit-if-win is below this. */
    const val DEFAULT_MIN_PROFIT_IF_WIN_USD = 10.0

    /** Default USD risked on one approved ticket. */
    const val DEFAULT_TICKET_STAKE_USD = 5.0

    /**
     * Soft cap: Settings may lower freely. Raising above this requires an
     * explicit typed confirmation in Settings.
     */
    const val TICKET_STAKE_SOFT_CAP_USD = 5.0

    /** Hard ceiling so this never becomes a large auto-bot. */
    const val TICKET_STAKE_HARD_CAP_USD = 25.0

    /** Minimum stake the slider / prefs will accept. */
    const val TICKET_STAKE_MIN_USD = 1.0

    /**
     * Configured-stake path (default $5): propose only when max settlement
     * payout is at least this many dollars. See [com.dirk.kalshiodds.signal.trade.PayoutGate].
     */
    const val DEFAULT_MIN_PAYOUT_USD = 100.0

    /**
     * Approval-gated hunter path: $1 stake must be able to settle at least
     * this many dollars (ask ≤ ~4¢ on a $1 binary). Detection is automatic;
     * execution still needs an in-app Confirm. Never unsupervised.
     */
    const val HUNTER_STAKE_USD = 1.0
    const val HUNTER_MIN_PAYOUT_USD = 25.0

    /**
     * Long-shot hunter: surface sides priced at or under this ask
     * (default 20¢) when AI/fair beats implied after fees + margin.
     * Sized by win-target (default $50 profit), not a fixed $1 stake.
     */
    const val DEFAULT_LONG_SHOT_MAX_ASK = 0.20

    /** Legacy $1 → $5 keys — kept so History restore can derive max ask. */
    const val HUNTER_VALUE_STAKE_USD = 1.0
    const val HUNTER_VALUE_PAYOUT_USD = 5.0

    /**
     * Legacy $50 profit sizer — off for live. Live uses [LIVE_ALL_IN_CAP_USD]
     * and [DEFAULT_MIN_PROFIT_IF_WIN_USD]. Kept so History restore still reads.
     */
    const val DEFAULT_WIN_TARGET_ENABLED = false
    const val DEFAULT_WIN_TARGET_USD = 50.0
    const val DEFAULT_WIN_TARGET_BANKROLL_PCT = 10.0

    /** Each winning binary contract settles at $1.00. */
    const val CONTRACT_SETTLEMENT_USD = 1.0

    /** Sparkline ring — copied under lock; persisted off the scan thread. */
    const val SPARKLINE_MAX_POINTS = 48
    const val ODDS_MID_PERSIST_MIN_MS = 2_000L
    /** Chart ticks: one persist per ticker per second, even when mid is 0/1. */
    const val CHART_TICK_PERSIST_MIN_MS = 1_000L

    /** Default: skip filter / mute / streak pause still gate tickets. */
    const val DEFAULT_TICKET_RESPECT_GATES = true

    /** Phrase the user must type to raise stake above the soft cap. */
    const val TICKET_RAISE_CONFIRM_PHRASE = "RAISE"

    // --- v0.3.0 heavy ML (on-device; never unsupervised betting) ---

    /**
     * Master switch. Default **off** (light / 0.2.x blend) after 0.3.0
     * devices OOM'd on Heavy ML + book-delta floods (256MB growth limit).
     * Turn on in Settings if the phone can take it. One OOM persists light
     * mode; other failures auto-disable after 3.
     */
    const val DEFAULT_HEAVY_ML = false
    const val DEFAULT_SEQUENCE_MODEL = true
    const val DEFAULT_GBM = true
    const val DEFAULT_UNCERTAINTY_GATE = true

    /**
     * Max ensemble / MC-dropout stddev (probability units) to high-rank
     * or propose a ticket. 0.12 ≈ models may disagree by ~12pp.
     */
    const val DEFAULT_MAX_UNCERTAINTY = 0.12
    const val DEFAULT_CONTINUAL_FINETUNE = true
    const val DEFAULT_POLICY_EVAL_STAKE_USD = 5.0

    // --- v0.3.0 extended AI (10–19); advisory / gates only ---

    /** Master switch for 10–19. Default off (light mode) — see Heavy ML. */
    const val DEFAULT_EXTENDED_AI = false
    const val DEFAULT_REGIME_CLASSIFIER = true
    const val DEFAULT_ANOMALY_GATE = true
    const val DEFAULT_SURVIVAL_MODEL = true
    const val DEFAULT_RL_SIZER = true
    const val DEFAULT_NEWS_PULSE = true
    const val DEFAULT_RIVAL_FLOW = true
    const val DEFAULT_BAYESIAN_MM = true
    const val DEFAULT_CONFORMAL = true
    const val DEFAULT_META_LABEL = true
    const val DEFAULT_PATH_SIM = true
}
