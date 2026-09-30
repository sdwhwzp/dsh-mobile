package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMessageReceiptStore
import kotlin.test.*

class SharedMessageReceiptStoreTest {
    private fun sent(id: String, session: String = "s") =
        """{"kind":"sent","requestId":"$id","sessionId":"$session"}"""
    private fun echo(id: String, session: String = "s") =
        """{"kind":"event","sessionId":"$session","event":{"type":"user/message","text":"same","raw":{"rpcId":"$id"}}}"""

    @Test fun acknowledgementKeepsPreviewUntilItsOwnDurableEcho() {
        val store = SharedMessageReceiptStore()
        store.begin("one", "s", "same", 2)
        assertEquals("sending", store.snapshot().single().phase)
        assertEquals("accepted", store.acceptFrame(sent("one")).single().phase)
        assertEquals(1, store.acceptFrame(echo("someone-else")).size)
        assertEquals(1, store.acceptFrame(echo("one", "other-session")).size)
        assertTrue(store.acceptFrame(echo("one")).isEmpty())
        assertTrue(store.acceptFrame(sent("one")).isEmpty())
    }

    @Test fun earlyEchoAndIdenticalConcurrentTextNeverDuplicateOrConsumeAnotherReceipt() {
        val store = SharedMessageReceiptStore()
        store.begin("one", null, "same", 0)
        store.begin("two", "s", "same", 0)
        store.acceptFrame(echo("one"))
        store.acceptFrame(sent("one"))
        assertEquals("two", store.snapshot().single().requestId)
        store.acceptFrame(sent("two", "wrong"))
        assertEquals("sending", store.snapshot().single().phase)
        store.acceptFrame(sent("two"))
        assertEquals("accepted", store.snapshot().single().phase)
    }

    @Test fun reconnectSnapshotCompletesOnlyMatchingRecordedRequest() {
        val store = SharedMessageReceiptStore()
        store.begin("one", "s", "same", 0)
        store.acceptFrame(sent("one"))
        assertEquals("unconfirmed", store.disconnected().single().phase)
        assertTrue(store.acceptFrame("""{"kind":"session-snapshot","sessionId":"s","events":[{"type":"user/message","data":{"source":{"rpcId":"one"}}}]}""").isEmpty())
        store.begin("other", "other", "private", 0)
        assertTrue(store.reset().isEmpty())
    }

    @Test fun failuresTimeoutAndDismissalDoNotResubmitAnything() {
        val store = SharedMessageReceiptStore()
        store.begin("one", "s", "draft", 0)
        assertEquals("unconfirmed", store.submissionTimedOut().single().phase)
        assertEquals("failed", store.acceptFrame("""{"kind":"error","requestType":"message","requestId":"one"}""").single().phase)
        assertEquals("draft", store.acceptFrame("invalid json").single().text)
        assertTrue(store.dismiss("one").isEmpty())
    }

    @Test fun confirmedQueueRemovalClearsOnlyItsOwnLocalPreview() {
        val store = SharedMessageReceiptStore()
        store.begin("one", "s", "same", 0)
        store.begin("two", "s", "same", 0)
        store.acceptFrame("""{"kind":"session-queues","queues":{"s":[{"id":"q1","rpcId":"one"},{"id":"q2","rpcId":"two"}]}}""")
        store.acceptFrame("""{"kind":"queue-item-updated","sessionId":"s","itemId":"q1","action":"remove","accepted":false}""")
        assertEquals(2, store.snapshot().size)
        store.acceptFrame("""{"kind":"queue-item-updated","sessionId":"s","itemId":"q1","action":"remove","accepted":true}""")
        assertEquals("two", store.snapshot().single().requestId)
    }
}
