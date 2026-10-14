package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ResponsesStreamFailurePolicyTest {
    @Test fun socketRetryIsAllowedOnlyWithoutAnyPermanentStorageOrDecoderFailure() {
        val socket = IOException("socket disconnected")
        assertFalse(ResponsesStreamFailurePolicy.isUnrecoverable(socket))
        socket.addSuppressed(ResponsesArchiveStorageException(IOException("fsync failed")))
        assertTrue(ResponsesStreamFailurePolicy.isUnrecoverable(socket))
        assertTrue(ResponsesStreamFailurePolicy.isUnrecoverable(
            IOException("wrapper", ResponsesSseLimitException("explicit limit"))))
    }

    @Test fun cyclicExceptionGraphsTerminateAndStillFindAStorageFailure() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertFalse(ResponsesStreamFailurePolicy.isUnrecoverable(a))
        b.addSuppressed(ResponsesArchiveStorageException(IOException("disk full")))
        assertTrue(ResponsesStreamFailurePolicy.isUnrecoverable(a))
    }
}
