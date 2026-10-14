package com.example.openaichat

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
class SecureStoreTest {
    @After
    fun tearDown() {
        SecureStore.failWritesForTesting = false
    }

    @Test
    fun failedKeyReplacementReturnsFalseAndEvictsOldAccountKey() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        var shouldFail = false
        val store = SecureStore(context) { raw ->
            if (shouldFail) throw GeneralSecurityException("Simulated Keystore failure")
            ByteArray(12) { 1 } + raw
        }

        assertTrue(store.setApiKey("sk-account-A-valid"))
        assertTrue(prefs.getString("key", null).orEmpty().isNotBlank())

        // Simulate Keystore failure when user replaces Account A key with Account B key.
        shouldFail = true
        assertFalse(store.setApiKey("sk-account-B-new"))

        // Old Account A key must be evicted so next request cannot silently bill Account A.
        assertEquals(null, prefs.getString("key", null))
        assertEquals("", store.getApiKey())
    }

    @Test
    fun globalFailHookReportsFailureAcrossAllCredentialSlots() {
        val context = RuntimeEnvironment.getApplication()
        val store = SecureStore(context) { raw -> ByteArray(12) { 2 } + raw }
        assertTrue(store.setOpenRouterApiKey("sk-or-v1-account-A"))

        SecureStore.failWritesForTesting = true
        assertFalse(store.setOpenRouterApiKey("sk-or-v1-account-B"))
        assertFalse(store.setGitHubToken("github_pat_account_B"))
        assertEquals("", store.getOpenRouterApiKey())
        assertEquals("", store.getGitHubToken())
    }
}
