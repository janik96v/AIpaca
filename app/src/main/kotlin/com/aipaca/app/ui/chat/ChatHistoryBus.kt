package com.aipaca.app.ui.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ChatHistoryBus {
    private val _openRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openRequests: SharedFlow<Unit> = _openRequests.asSharedFlow()

    fun requestOpen() {
        _openRequests.tryEmit(Unit)
    }
}
