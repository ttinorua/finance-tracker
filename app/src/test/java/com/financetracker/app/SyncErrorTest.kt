package com.financetracker.app

import com.financetracker.app.data.enablebanking.isTransientNetworkError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class SyncErrorTest {

    @Test
    fun `no-internet and dns errors count as transient`() {
        assertTrue(isTransientNetworkError(UnknownHostException("Unable to resolve host \"api.enablebanking.com\"")))
        assertTrue(isTransientNetworkError(java.net.SocketTimeoutException("timeout")))
        assertTrue(isTransientNetworkError(java.net.ConnectException("Failed to connect to /10.0.0.1")))
        // Wrapped a few layers deep, as the real sync surfaces it.
        val wrapped = RuntimeException("Enable Banking request failed", IOException("Unable to resolve host"))
        assertTrue(isTransientNetworkError(wrapped))
    }

    @Test
    fun `real errors are not transient`() {
        assertFalse(isTransientNetworkError(IllegalStateException("Consent expired (403)")))
        assertFalse(isTransientNetworkError(RuntimeException("Unexpected JSON")))
    }
}
