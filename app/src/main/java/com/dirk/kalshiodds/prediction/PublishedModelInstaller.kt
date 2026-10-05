package com.dirk.kalshiodds.prediction

/**
 * Apply a downloaded model only when [ModelActivation] says so.
 * A failed download, a package mismatch, or a losing holdout leaves the
 * current edge model in place. When none is installed, scoring keeps the
 * bundled DipHunter TFLite model.
 */
object PublishedModelInstaller {
    fun apply(
        store: ImportedModelStore,
        outcome: LatestModelClient.Outcome,
        assign: (EdgeModel) -> Unit
    ): String = when (outcome) {
        is LatestModelClient.Outcome.Ready -> {
            val decision = outcome.fetch.decision
            if (decision.activate) {
                store.activate(
                    outcome.fetch.model,
                    outcome.fetch.manifest,
                    rawJson = outcome.fetch.modelJson
                )
                assign(outcome.fetch.model)
            }
            decision.reason
        }
        is LatestModelClient.Outcome.NeedsAuth -> outcome.message
        is LatestModelClient.Outcome.Failed -> outcome.message
    }
}
