package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedMessageReceiptStore
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMessageReceiptProjectionTest {
    @Test
    fun rejectedOldSubscriptionEchoKeepsPreviewUntilCurrentEchoIsPublished() = runTest {
        val fixture = ReceiptFixture(this)
        try {
            fixture.ready()
            fixture.deliver(emptySnapshot)
            fixture.begin()
            fixture.deliver(userEcho.replace("\"current\"", "\"old\""))
            assertEquals("request", fixture.receipts.snapshot().single().requestId)
            assertTrue(fixture.published.conversation.isEmpty())
            assertEquals(0, fixture.acknowledgements)

            fixture.deliver(userEcho)
            assertTrue(fixture.receipts.snapshot().isEmpty())
            assertEquals(listOf("Question"), fixture.published.conversation.map { it.text })
            assertEquals(1, fixture.acknowledgements)
        } finally {
            fixture.actor.close()
        }
    }

    @Test
    fun rejectedSnapshotKeepsPreviewUntilMatchingSnapshotIsPublished() = runTest {
        val fixture = ReceiptFixture(this)
        try {
            fixture.ready()
            fixture.begin()
            fixture.deliver(userSnapshot.replace("\"current\"", "\"old\""))
            assertEquals(1, fixture.receipts.snapshot().size)
            assertTrue(fixture.published.conversation.isEmpty())

            fixture.deliver(userSnapshot)
            assertTrue(fixture.receipts.snapshot().isEmpty())
            assertEquals(listOf("Question"), fixture.published.conversation.map { it.text })
            assertEquals(1, fixture.acknowledgements)
        } finally {
            fixture.actor.close()
        }
    }

    @Test
    fun ignoredLateHistoryCannotClearPreviewAfterAnActiveBaseline() = runTest {
        val fixture = ReceiptFixture(this)
        try {
            fixture.ready()
            fixture.deliver(emptySnapshot)
            fixture.begin()
            fixture.deliver(userHistory)
            assertEquals(1, fixture.receipts.snapshot().size)
            assertTrue(fixture.published.conversation.isEmpty())
            assertEquals(0, fixture.acknowledgements)
        } finally {
            fixture.actor.close()
        }
    }

    @Test
    fun acceptedLegacyHistoryClearsPreviewAfterItsMessageIsPublished() = runTest {
        val fixture = ReceiptFixture(this)
        try {
            fixture.actor.selectSession("s")
            fixture.begin()
            fixture.deliver(userHistory)
            assertTrue(fixture.receipts.snapshot().isEmpty())
            assertEquals(listOf("Question"), fixture.published.conversation.map { it.text })
            assertEquals(1, fixture.acknowledgements)
        } finally {
            fixture.actor.close()
        }
    }

    @Test
    fun malformedUserEventKeepsPreviewWhenProjectionFails() = runTest {
        val fixture = ReceiptFixture(this)
        try {
            fixture.actor.selectSession("s")
            fixture.begin()
            fixture.deliver(userEcho
                .replace(",\"seq\":1,\"time\":100", "")
                .replace(",\"subscriptionId\":\"current\",\"streamId\":\"stream\"", ""))
            assertEquals(1, fixture.receipts.snapshot().size)
            assertTrue(fixture.published.conversation.isEmpty())
            assertEquals(0, fixture.acknowledgements)
        } finally {
            fixture.actor.close()
        }
    }

    private class ReceiptFixture(scope: TestScope) {
        val receipts = SharedMessageReceiptStore()
        private val projection = AndroidGatewayProjection()
        var published: SharedMobileSnapshot = projection.snapshot()
        var acknowledgements = 0
        val actor = AndroidProjectionActor(
            projection = projection,
            uiDispatcher = StandardTestDispatcher(scope.testScheduler),
            backgroundDispatcher = StandardTestDispatcher(scope.testScheduler),
            publish = { snapshot, _ -> published = snapshot }
        )

        fun begin() {
            acknowledgements = 0
            receipts.begin("request", "s", "Question", 0)
        }

        suspend fun ready() {
            actor.selectSession("s")
            deliver("""{"kind":"hello","historyFormatVersion":3,"capabilities":["assistant-stream-v1"]}""")
            deliver("""{"kind":"subscribed","sessionId":"s","subscriptionId":"current","assistantStream":true}""")
        }

        suspend fun deliver(raw: String) {
            actor.acceptFrame(raw, GatewayWireDecoder.decode(raw), "s", afterTranscriptAccepted = {
                if (receipts.snapshot().isNotEmpty()) {
                    assertTrue("publish the real message before removing its local preview", published.conversation.any { it.text == "Question" })
                    receipts.acceptFrame(raw)
                    acknowledgements++
                }
            })
        }
    }

    private companion object {
        const val userEcho = """{"kind":"event","sessionId":"s","subscriptionId":"current","streamId":"stream","seq":1,"time":100,"event":{"type":"user/message","text":"Question","raw":{"rpcId":"request"}}}"""
        const val emptySnapshot = """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"current","streamId":"stream","historyFormatVersion":3,"cursor":0,"events":[],"hasMore":false}"""
        const val userSnapshot = """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"current","streamId":"stream","historyFormatVersion":3,"cursor":1,"events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"Question"}],"source":{"rpcId":"request"}}}],"hasMore":false}"""
        const val userHistory = """{"kind":"history","sessionId":"s","events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"Question"}],"source":{"rpcId":"request"}}}],"hasMore":false,"bytes":128}"""
    }
}
