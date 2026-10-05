package com.dirk.kalshiodds.signal.paper

/**
 * Decides the side effects of one Autopilot tick.
 * [run] calls [place] only when [Result.shouldPlace] is true, so Shadow
 * mode cannot reach the order API even if a caller passes a live client.
 */
object AutopilotDispatch {
    data class Request(
        val mode: AutopilotMode,
        val masterOn: Boolean,
        val decisionOk: Boolean,
        val paperFilled: Boolean,
        val armed: Boolean,
        val credentialsOk: Boolean,
        val failClosed: Boolean,
        val paperSide: String?,
        val paperPrice: Double?,
        val shadowSide: String?,
        val shadowPrice: Double?,
        val shadowDepthFill: Boolean,
        val shadowAllInUsd: Double,
        val spentTodayUsd: Double,
        val dailyCapUsd: Double,
        val alreadyAttempted: Boolean
    )

    data class Result(
        val bookPaper: Boolean,
        val recordShadow: Boolean,
        val shouldPlace: Boolean,
        val reason: String
    )

    fun decide(request: Request): Result {
        if (!request.masterOn) {
            return Result(false, false, false, "Autopilot off")
        }
        val bookPaper = request.mode != AutopilotMode.SHADOW
        val recordShadow = request.decisionOk && request.mode != AutopilotMode.PAPER
        if (request.mode != AutopilotMode.LIVE || !request.decisionOk) {
            val reason = when (request.mode) {
                AutopilotMode.PAPER -> "Paper only — no Kalshi order"
                AutopilotMode.SHADOW -> "SHADOW — not submitted"
                AutopilotMode.LIVE -> "No live send — decision skipped"
            }
            return Result(bookPaper, recordShadow, false, reason)
        }
        val gate = LiveAutopilotGate.evaluate(
            mode = request.mode,
            armed = request.armed,
            credentialsOk = request.credentialsOk,
            failClosed = request.failClosed,
            paperFilled = request.paperFilled,
            paperSide = request.paperSide,
            paperPrice = request.paperPrice,
            shadowSide = request.shadowSide,
            shadowPrice = request.shadowPrice,
            shadowDepthFill = request.shadowDepthFill,
            shadowAllInUsd = request.shadowAllInUsd,
            spentTodayUsd = request.spentTodayUsd,
            dailyCapUsd = request.dailyCapUsd,
            alreadyAttempted = request.alreadyAttempted
        )
        return Result(
            bookPaper = bookPaper,
            recordShadow = recordShadow,
            shouldPlace = gate.send,
            reason = gate.reason
        )
    }

    /** The only helper that may invoke [place]. Shadow results never do. */
    fun run(result: Result, place: () -> Unit) {
        if (result.shouldPlace) place()
    }
}
