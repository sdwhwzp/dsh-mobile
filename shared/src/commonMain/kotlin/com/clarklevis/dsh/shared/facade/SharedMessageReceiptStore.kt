package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.wireJson

/** A local preview is separate from durable history and never advances its sequence. */
data class SharedMessageReceipt(
    val requestId: String,
    val sessionId: String?,
    val text: String,
    val attachmentCount: Int,
    val phase: String = "sending",
    val queueItemId: String? = null
) {
    override fun toString(): String = "SharedMessageReceipt(phase=$phase, content=<redacted>)"
}

/** One account owns this store. Receipts survive acknowledgement until their own user event arrives. */
class SharedMessageReceiptStore {
    private var items = emptyList<SharedMessageReceipt>()

    fun snapshot(): List<SharedMessageReceipt> = items

    fun begin(requestId: String, sessionId: String?, text: String, attachmentCount: Int): List<SharedMessageReceipt> {
        items = items.filterNot { it.requestId == requestId } +
            SharedMessageReceipt(requestId, sessionId, text, attachmentCount)
        return items
    }

    /** Dismissal changes only the local preview; it never retries or cancels a server message. */
    fun dismiss(requestId: String): List<SharedMessageReceipt> {
        items = items.filterNot { it.requestId == requestId }
        return items
    }

    fun disconnected(): List<SharedMessageReceipt> {
        items = items.map { it.copy(phase = "unconfirmed") }
        return items
    }

    fun submissionTimedOut(): List<SharedMessageReceipt> {
        items = items.map { if (it.phase == "sending") it.copy(phase = "unconfirmed") else it }
        return items
    }

    fun reset(): List<SharedMessageReceipt> {
        items = emptyList()
        return items
    }

    fun acceptFrame(json: String): List<SharedMessageReceipt> {
        val frame = runCatching { JsonValue.fromJsonElement(wireJson.parseToJsonElement(json)) }.getOrNull() ?: return items
        val sessionId = frame["sessionId"]?.stringValue
        val requestId = frame["requestId"]?.stringValue
        when (frame["kind"]?.stringValue) {
            "sent" -> items = items.map { item ->
                if (item.requestId == requestId && sessionId != null && (item.sessionId == null || item.sessionId == sessionId))
                    item.copy(sessionId = sessionId, phase = "accepted") else item
            }
            "error" -> if (frame["requestType"]?.stringValue == "message" || requestId != null) {
                items = items.map { if (it.requestId == requestId) it.copy(phase = "failed") else it }
            }
            "event" -> {
                val event = frame["event"] ?: return items
                if (event["type"]?.stringValue == "user/message") {
                    val nonce = event["raw"]?.get("rpcId")?.stringValue
                        ?: event["raw"]?.get("source")?.get("rpcId")?.stringValue
                    complete(sessionId, nonce)
                }
            }
            "history", "session-snapshot" -> frame["events"]?.arrayValue.orEmpty().forEach { event ->
                if (event["type"]?.stringValue == "user/message") {
                    complete(sessionId, event["data"]?.get("source")?.get("rpcId")?.stringValue)
                }
            }
            "session-queue" -> bindQueue(sessionId, frame["items"])
            "session-queues" -> frame["queues"]?.objectValue.orEmpty().forEach { (id, rows) -> bindQueue(id, rows) }
            "queue-item-updated" -> if (frame["accepted"]?.booleanValue == true && frame["action"]?.stringValue == "remove") {
                val queueItemId = frame["itemId"]?.stringValue
                if (queueItemId != null) items = items.filterNot { it.sessionId == sessionId && it.queueItemId == queueItemId }
            }
        }
        return items
    }

    private fun bindQueue(sessionId: String?, rows: JsonValue?) {
        if (sessionId == null) return
        rows?.arrayValue.orEmpty().forEach { row ->
            val requestId = row["rpcId"]?.stringValue ?: return@forEach
            items = items.map { if (it.requestId == requestId && (it.sessionId == null || it.sessionId == sessionId))
                it.copy(sessionId = sessionId, queueItemId = row["id"]?.stringValue) else it }
        }
    }

    private fun complete(sessionId: String?, requestId: String?) {
        if (sessionId == null || requestId == null) return
        items = items.filterNot { it.requestId == requestId && (it.sessionId == null || it.sessionId == sessionId) }
    }
}
