package com.example.openaichat

enum class ResponseStreamSequenceObservation {
    ACCEPTED,
    DUPLICATE
}

class ResponseStreamSequenceCursor(startingAfter: Long = -1L) {
    var lastSequenceNumber: Long = startingAfter
        private set

    fun observe(sequenceNumber: Long): ResponseStreamSequenceObservation {
        if (sequenceNumber < 0L) return ResponseStreamSequenceObservation.ACCEPTED
        if (sequenceNumber <= lastSequenceNumber) return ResponseStreamSequenceObservation.DUPLICATE
        if (lastSequenceNumber >= 0L && sequenceNumber > lastSequenceNumber + 1L) {
            throw ResponseStreamSequenceGapException(
                expectedSequenceNumber = lastSequenceNumber + 1L,
                receivedSequenceNumber = sequenceNumber
            )
        }
        lastSequenceNumber = sequenceNumber
        return ResponseStreamSequenceObservation.ACCEPTED
    }
}
