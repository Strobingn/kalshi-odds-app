package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.data.repo.MarketRepository
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OddsUiState(
    val isLoading: Boolean = false,
    val snapshot: MarketsSnapshot? = null,
    val userMessage: String? = null
)

class OddsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MarketRepository(application)

    private val _state = MutableStateFlow(OddsUiState(isLoading = true))
    val state: StateFlow<OddsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.cachedSnapshot.collect { cached ->
                if (cached != null && _state.value.snapshot == null) {
                    _state.update { it.copy(snapshot = cached, isLoading = false) }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, userMessage = null) }
            val result = repository.refresh()
            _state.update {
                it.copy(
                    isLoading = false,
                    snapshot = result,
                    userMessage = when {
                        result.errorMessage != null && result.fromCache ->
                            "Offline — showing cache (${result.errorMessage})"
                        result.errorMessage != null -> result.errorMessage
                        else -> null
                    }
                )
            }
        }
    }
}
