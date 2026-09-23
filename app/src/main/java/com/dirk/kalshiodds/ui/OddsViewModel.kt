package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.data.repo.MarketRepository
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OddsUiState(
    val isLoading: Boolean = false,
    val snapshot: MarketsSnapshot? = null,
    val userMessage: String? = null,
    val pollLabel: String = "Polling ~750ms",
    val modelScoreLabel: String? = null
)

/**
 * Fastest practical public-REST poll.
 *
 * Kalshi WebSocket requires API-key auth even for public market channels, so
 * Dip Hunter stays on unauthenticated GET /markets.
 *
 * Base target: 750ms with ±250ms jitter → ~500ms–1000ms.
 * On HTTP 429/503: double the current interval (cap 60s).
 * On success: ease interval back toward base (×0.8 each success).
 */
class OddsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MarketRepository(application)

    private val _state = MutableStateFlow(OddsUiState(isLoading = true))
    val state: StateFlow<OddsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    /** Adaptive delay between polls; starts at BASE_POLL_MS. */
    private var currentIntervalMs: Long = BASE_POLL_MS

    init {
        viewModelScope.launch {
            repository.cachedSnapshot.collect { cached ->
                if (cached != null && _state.value.snapshot == null) {
                    _state.update {
                        it.copy(
                            snapshot = cached,
                            isLoading = false,
                            modelScoreLabel = scoreLabel(cached)
                        )
                    }
                }
            }
        }
        startPolling()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, userMessage = null) }
            applyResult(repository.refresh())
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (_state.value.snapshot == null) {
                    _state.update { it.copy(isLoading = true) }
                }
                applyResult(repository.refresh())
                delay(nextDelayMs())
            }
        }
    }

    private fun applyResult(result: MarketsSnapshot) {
        if (result.rateLimited) {
            currentIntervalMs = min(max(currentIntervalMs * 2, INITIAL_BACKOFF_MS), MAX_BACKOFF_MS)
        } else if (result.errorMessage == null) {
            currentIntervalMs = max((currentIntervalMs * 4) / 5, BASE_POLL_MS)
        }
        val pollLabel = if (currentIntervalMs > BASE_POLL_MS + JITTER_MS) {
            "Backing off ~${currentIntervalMs / 1000}s"
        } else {
            "Polling ~${currentIntervalMs}ms (±${JITTER_MS}ms)"
        }
        _state.update {
            it.copy(
                isLoading = false,
                snapshot = result,
                userMessage = when {
                    result.errorMessage != null && result.fromCache ->
                        "Offline — showing cache (${result.errorMessage})"
                    result.errorMessage != null -> result.errorMessage
                    else -> null
                },
                pollLabel = pollLabel,
                modelScoreLabel = scoreLabel(result)
            )
        }
    }

    private fun scoreLabel(result: MarketsSnapshot): String? {
        val c = result.modelScoreCorrect ?: return null
        val total = result.modelScoreTotal ?: return null
        if (total <= 0) return null
        val brier = result.modelMeanBrier?.let { String.format(java.util.Locale.US, " · Brier %.3f", it) }.orEmpty()
        return "Model score: $c/$total correct$brier"
    }

    private fun nextDelayMs(): Long {
        val half = min(JITTER_MS, currentIntervalMs / 3)
        val jitter = if (half <= 0L) 0L else Random.nextLong(-half, half + 1)
        return (currentIntervalMs + jitter).coerceAtLeast(MIN_POLL_MS)
    }

    companion object {
        const val BASE_POLL_MS = 750L
        const val JITTER_MS = 250L
        const val MIN_POLL_MS = 500L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
