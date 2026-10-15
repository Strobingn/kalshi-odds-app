package com.dirk.kalshiodds.signal.trade

/**
 * Every gate that can block a live Approve, with the shipped default.
 * Each block must surface [reason] on screen — never a silent no-op.
 */
object LiveOrderGates {

    const val LIVE_ALL_IN = 10.0
    const val MIN_PROFIT = 0.0
    const val PEM_ONLY_KEY_ID =
        "Key ID is saved but the private key PEM is missing — paste a BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY block"

    data class Gate(
        val id: String,
        val default: String,
        val reason: String
    )

    val catalog: List<Gate> = listOf(
        Gate("tickets_enabled", "on", "Turn on trade tickets in Settings to buy"),
        Gate("live_approve_tap", "required — never auto-bet", "Live Approve is the only path that sends a V2 order"),
        Gate("credentials", "Key ID + PEM required", "Add Kalshi API Key ID + PEM in Settings before Live Approve — or use Paper"),
        Gate("pem_only_key_id", "both required", PEM_ONLY_KEY_ID),
        Gate("paper_isolation", "paper never blocks live", "Paper fills do not block a live order"),
        Gate("client_order_id", "UUID per live Approve", "Duplicate client_order_id — not re-sent"),
        Gate("sit_out", "off unless auto-tune sits out", "Sitting out — model loses to the market"),
        Gate("kill_switch_heavy_ml", "Heavy ML off", "Scoring is the 0.2.x blend; tickets still need Approve"),
        Gate("drawdown_pause", "pause at $50 drawdown", "Alerts paused after drawdown — Resume in Settings"),
        Gate("streak_pause", "pause after 4 misses", "Alerts paused after a losing streak — Resume in Settings"),
        Gate("payout_gate_configured", "$10 → ≥$100 max payout (ask ≤10¢)", "Price too high for the $100 payout gate"),
        Gate("hunter_threshold", "$1 → ≥$25 (ask ≤~4¢)", "Ask is above the hunter $1→$25 print"),
        Gate("long_shot", "ask ≤20¢ and AI beats implied after fees", "Long-shot needs a cheap ask and a model edge"),
        Gate("live_all_in_cap", "$10 including fees", "Cannot size a live order under the $10 all-in cap"),
        Gate("min_profit_if_win", "removed", "Min-profit-if-win is off — low profit does not block a ticket"),
        Gate("allowlist", "off until enough samples", "Series is muted by the allowlist"),
        Gate("confidence_filter", "min 0.45", "Confidence is below the Settings floor"),
        Gate("skip_filter", "on when ticketRespectGates", "Skip filter blocked this ticket"),
        Gate("mute", "auto-mute below 40% hit rate", "Market is muted"),
        Gate("checklist", "advisory", "Pre-trade checklist failed — Approve stays off"),
        Gate("stale_quote", "one snapshot for all four bids/asks", "Quote is stale or crossed — refresh"),
        Gate("close_cutoff", "close_time / status", "Market closed"),
        Gate("rate_limiter", "Kalshi 429", "Rate limited — wait and Approve again"),
        Gate("approve_router_live", "Live tap → HTTP", "Live Approve never routes to the paper book"),
        Gate("paper_approve", "Paper tap only", "Paper Approve never hits Kalshi")
    )

    const val PemNormalizerMissing =
        "Key ID is saved but the private key PEM is missing — paste a BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY block"

    fun defaultOf(id: String): String = catalog.first { it.id == id }.default
}
