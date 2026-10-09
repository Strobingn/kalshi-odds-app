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

    const val SCORECARD_ROLLING_DAYS = 7

    /** Size drop (contracts) treated as a cancel spike. */
    const val CANCEL_SPIKE_SIZE = 8.0

    const val DEPTH_NEAR_CENTS = 3.0
    const val DEPTH_FAR_CENTS = 15.0

    // --- v0.2.1 decision support (advisory only) ---

    /** Default advisory bankroll used only for suggested size (not the paper book). */
    const val DEFAULT_BANKROLL_USD = 1_000.0

    /** Isolated paper book — never hits Kalshi. Visible on the home screen. */
    const val PAPER_START_USD = 10_000.0
    /** Legacy $5 clip — unused for AI paper (Kelly). Kept so History restore still reads. */
    const val PAPER_STAKE_USD = 5.0
    const val DEFAULT_PAPER_TRADING = true
    /** AI paper autopilot — default on. Manual Paper UP/DOWN is separate. */
    const val DEFAULT_AI_PAPER_AUTOPILOT = true
    const val PAPER_LEDGER_MAX = 80

    /**
     * Live-card advisory Kelly fraction for [com.dirk.kalshiodds.signal.sizing.PositionSizer]
     * / ScoringEngine. Default 0.25 (quarter-Kelly). Paper AI uses
     * [DEFAULT_PAPER_KELLY_FRACTION] on a separate preference key.
     */
    const val DEFAULT_KELLY_FRACTION = 0.25

    /**
     * Fraction of full Kelly actually risked on **paper**. Default 0.5
     * (half-Kelly). Settings slider is [PAPER_KELLY_FRACTION_MIN]–[PAPER_KELLY_FRACTION_MAX].
     * Live orders ignore this and stay on the $10 all-in cap.
     */
    const val DEFAULT_PAPER_KELLY_FRACTION = 0.5
    const val PAPER_KELLY_FRACTION_MIN = 0.1
    const val PAPER_KELLY_FRACTION_MAX = 1.0
    const val LIVE_KELLY_FRACTION_MIN = 0.05
    const val LIVE_KELLY_FRACTION_MAX = 1.0

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

    /** @see com.dirk.kalshiodds.signal.feedback.ScorecardTargets.MIN_PER_SLOT_SAMPLES */
    const val SCORECARD_BUCKET_MIN_SAMPLES = 5

    const val GITHUB_OWNER = "Strobingn"
    const val GITHUB_REPO = "kalshi-odds-app"
    const val EDGE_MODEL_RELEASE_TAG = "edge-model-latest"

    const val DEFAULT_RANK_BY_NET_EV = true
    const val DEFAULT_AUTO_MUTE = true

    /** Mute / downrank when rolling hit rate is below this (and enough samples). */
    const val DEFAULT_MUTE_HIT_RATE_FLOOR = 0.40

    /** Minimum settled samples in a bucket before auto-mute can fire. */
    const val MIN_MUTE_SAMPLES = 8

    const val DEFAULT_STREAK_PAUSE_N = 4
    const val DEFAULT_DRAWDOWN_USD = 50.0
    const val DEFAULT_RESUME_ON_NEW_SESSION = true

    /** Reweight blend channels only after this many settlements. Was 8. */
    const val MIN_ADAPTER_SAMPLES = 100
    const val ADAPTER_LEARNING_RATE = 0.08
    const val ADAPTER_WEIGHT_EMA = 0.15
    /** L2-style pull of each channel weight toward the prior (1.0). */
    const val ADAPTER_PRIOR_SHRINK = 0.05

    const val EXTERNAL_CACHE_MS = 25_000L
    const val EXTERNAL_CONNECT_TIMEOUT_MS = 3_000L
    const val EXTERNAL_READ_TIMEOUT_MS = 4_000L

    // --- v0.2.2 approve-gated tickets ---

    /**
     * Hard all-in cap on a live Approve, including Kalshi fees.
     * `count` is the largest integer with count×P + fee ≤ this.
     * 0.3.16: $10 default and hard cap (user may pick less).
     */
    const val LIVE_ALL_IN_CAP_USD = 10.0

    /** Limited live Autopilot daily spend. Editable in Settings. Default $50. */
    const val DEFAULT_LIVE_AUTOPILOT_DAILY_CAP_USD = 50.0
    const val LIVE_AUTOPILOT_DAILY_CAP_MIN_USD = 10.0
    const val LIVE_AUTOPILOT_DAILY_CAP_MAX_USD = 200.0

    /**
     * Min-profit-if-win is gone in 0.3.16. Kept at 0 so leftover prefs
     * and History restore cannot block a ticket.
     */
    const val DEFAULT_MIN_PROFIT_IF_WIN_USD = 0.0

    /** Default USD risked on one approved ticket. */
    const val DEFAULT_TICKET_STAKE_USD = 10.0

    /**
     * Soft cap equals the hard cap — Settings may lower freely; no RAISE
     * phrase is needed because nothing above $10 is allowed.
     */
    const val TICKET_STAKE_SOFT_CAP_USD = 10.0

    /** Hard ceiling: $10 max bet. */
    const val TICKET_STAKE_HARD_CAP_USD = 10.0

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
