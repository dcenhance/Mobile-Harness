package com.jarves.mh.runtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One capture's result, handed from the foreground service to the chat surface inside this process.
 *
 * The service cannot return a value to the caller that started it, and the frame is only known once
 * the projection has produced it, so the outcome travels here: exactly one success (with the written
 * frame) or exactly one refusal reason.
 */
object ScreenCaptureResult {

    data class Frame(
        val fileName: String,
        val relativePath: String,
        val bytes: Long,
        val capturedAtMillis: Long,
    )

    sealed interface Outcome {
        data class Captured(val frame: Frame) : Outcome
        data class Refused(val refusal: ScreenFrameFiles.Refusal) : Outcome
    }

    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 4, replay = 0)
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    /** Delivers one outcome; a full buffer is not an error (the chat is gone and nobody listens). */
    fun publish(outcome: Outcome) {
        _outcomes.tryEmit(outcome)
    }
}
