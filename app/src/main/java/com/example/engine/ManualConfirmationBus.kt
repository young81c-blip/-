package com.example.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PendingConfirmationRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val taskId: Long,
    val taskName: String,
    val stepTitle: String,
    val reason: String,
    val executionMode: String,
    val createdAt: Long = System.currentTimeMillis()
)

object ManualConfirmationBus {
    private val _pendingRequest = MutableStateFlow<PendingConfirmationRequest?>(null)
    val pendingRequest: StateFlow<PendingConfirmationRequest?> = _pendingRequest.asStateFlow()

    @Volatile
    private var activeDeferred: CompletableDeferred<Boolean>? = null

    suspend fun awaitDecision(request: PendingConfirmationRequest): Boolean {
        val prev = activeDeferred
        if (prev != null && !prev.isCompleted) {
            prev.complete(false)
        }
        val deferred = CompletableDeferred<Boolean>()
        activeDeferred = deferred
        _pendingRequest.value = request
        return try {
            deferred.await()
        } finally {
            if (_pendingRequest.value?.requestId == request.requestId) {
                _pendingRequest.value = null
            }
            if (activeDeferred === deferred) {
                activeDeferred = null
            }
        }
    }

    fun resolve(confirmed: Boolean, requestId: String? = null): Boolean {
        val current = _pendingRequest.value ?: return false
        if (requestId != null && current.requestId != requestId) return false
        val deferred = activeDeferred ?: return false
        val completed = deferred.complete(confirmed)
        if (_pendingRequest.value?.requestId == current.requestId) {
            _pendingRequest.value = null
        }
        return completed
    }

    fun cancelActive() {
        activeDeferred?.complete(false)
        activeDeferred = null
        _pendingRequest.value = null
    }
}
