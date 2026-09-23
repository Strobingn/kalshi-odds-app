package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.data.repo.MarketRepository
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
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
    val pollLabel: String = "Polling ~1.5s"
)

/**
 * Foreground poll: base 1.5s with ±0.5s jitter (1.0–2.0s).
 * On HTTP 429/503: exponential backoff from 2s, doubling up to 60s, then resume.
 */
class OddsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MarketRepository(application)

    private val _state = MutableStateFlow(OddsUiState(isLoading = true))
    val state: StateFlow<OddsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var backoffMs: Long = 0L

    init {
        viewModelScope.launch {
            repository.cachedSnapshot.collect { cached ->
                if (cached != null && _state.value.snapshot == null) {
                    _state.update { it.copy(snapshot = cached, isLoading = false) }
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
            backoffMs = if (backoffMs <= 0L) INITIAL_BACKOFF_MS else min(backoffMs * 2, MAX_BACKOFF_MS)
        } else if (result.errorMessage == null) {
            backoffMs = 0L
        }
        val pollLabel = if (backoffMs > 0L) {
            "Backing off ${backoffMs / 1000}s"
        } else {
            "Polling ~1.5s (±0.5s jitter)"
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
                pollLabel = pollLabel
            )
        }
    }

    private fun nextDelayMs(): Long {
        if (backoffMs > 0L) {
            val jitter = Random.nextLong(-BACKOFF_JITTER_MS, BACKOFF_JITTER_MS + 1)
            return (backoffMs + jitter).coerceAtLeast(INITIAL_BACKOFF_MS)
        }
        val jitter = Random.nextLong(-JITTER_MS, JITTER_MS + 1)
        return (BASE_POLL_MS + jitter).coerceIn(MIN_POLL_MS, MAX_POLL_MS)
    }

    companion object {
        const val BASE_POLL_MS = 1_500L
        const val JITTER_MS = 500L
        const val MIN_POLL_MS = 1_000L
        const val MAX_POLL_MS = 2_000L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val BACKOFF_JITTER_MS = 250L
    }
}
