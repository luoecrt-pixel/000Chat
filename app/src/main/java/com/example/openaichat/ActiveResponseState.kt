package com.example.openaichat

data class ActiveResponseState(
    val generation: Long = 0L,
    val responseId: String? = null,
    val assistantItemId: String? = null,
    val userStopRequestedGeneration: Long? = null
) {
    fun isCurrent(candidateGeneration: Long, activityVisible: Boolean, finishing: Boolean): Boolean =
        activityVisible && !finishing && generation == candidateGeneration

    fun wasUserStopped(candidateGeneration: Long): Boolean =
        userStopRequestedGeneration == candidateGeneration
}

class ActiveResponseStateStore {
    @Volatile
    var state: ActiveResponseState = ActiveResponseState()
        private set

    fun nextGeneration(): Long {
        val next = state.generation + 1L
        state = state.copy(generation = next)
        return next
    }

    fun markUserStop(): Long {
        val stoppedGeneration = state.generation
        state = state.copy(
            generation = stoppedGeneration + 1L,
            userStopRequestedGeneration = stoppedGeneration
        )
        return stoppedGeneration
    }

    fun setGeneration(generation: Long) {
        state = state.copy(generation = generation)
    }

    fun setUserStopRequestedGeneration(generation: Long?) {
        state = state.copy(userStopRequestedGeneration = generation)
    }

    fun clearUserStop() {
        state = state.copy(userStopRequestedGeneration = null)
    }

    fun setResponseId(responseId: String?) {
        state = state.copy(responseId = responseId)
    }

    fun setAssistantItemId(itemId: String?) {
        state = state.copy(assistantItemId = itemId)
    }

    fun clearAssistantItem() {
        state = state.copy(assistantItemId = null)
    }

    fun clear() {
        state = ActiveResponseState(generation = state.generation)
    }
}
