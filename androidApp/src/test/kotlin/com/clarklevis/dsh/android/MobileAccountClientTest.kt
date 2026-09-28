package com.clarklevis.dsh.android

import com.clarklevis.dsh.android.platform.MobileAccountClient
import com.clarklevis.dsh.android.platform.MobileAccountCredential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class MobileAccountClientTest {
    private val credential = MobileAccountCredential("https://example.com", 12, "server", "account",
        "alice", "wss://example.com/api/mobile.v1/account", "__Secure-dsh_mobile_refresh=secret")

    @Test fun savedAccountPreservesItsServerAndUserWithoutLoggingTheRefreshSecret() {
        assertEquals(credential, MobileAccountCredential.decode(credential.encode()))
        assertNull(MobileAccountCredential.decode("legacy-device-token"))
        assertFalse(credential.toString().contains("secret"))
    }

    @Test fun loginRejectsInsecureAndAmbiguousOriginsBeforeSendingCredentials() = runTest {
        for (origin in listOf("http://example.com", "https://user:pass@example.com", "https://example.com/path",
            "https://example.com?redirect=other", "https://example.com#account")) {
            try {
                MobileAccountClient.login(origin, "alice", "secret")
                fail("Accepted $origin")
            } catch (_: IllegalArgumentException) { /* Origin validation rejects before network access. */ }
        }
    }

    @Test fun refreshCannotMoveAnAccountCredentialToAnotherEndpoint() = runTest {
        try {
            MobileAccountClient.access(credential, "wss://other.example/api/mobile.v1/account")
            fail("Accepted another server")
        } catch (_: IllegalArgumentException) { /* The credential remains scoped to its saved endpoint. */ }
    }
}
