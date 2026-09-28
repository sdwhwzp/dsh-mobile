package com.clarklevis.dsh.android.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Persisted only through the encrypted gateway credential store; passwords and access tokens stay in memory. */
@Serializable
internal data class MobileAccountCredential(
    val origin: String,
    val userId: Long,
    val serverId: String,
    val gatewayId: String,
    val gatewayName: String,
    val endpoint: String,
    val refreshCookie: String
) {
    override fun toString() = "MobileAccountCredential(redacted)"
    fun encode(): String = PREFIX + Json.encodeToString(serializer(), this)
    companion object {
        const val PREFIX = "dsh-account-v1:"
        fun decode(value: String): MobileAccountCredential? =
            if (value.startsWith(PREFIX)) Json.decodeFromString(serializer(), value.removePrefix(PREFIX)) else null
    }
}

/** HTTPS, no redirects and no shared cookie jar: each refresh belongs to one server/account. */
internal object MobileAccountClient {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private const val AUTH = "/gateway/mobile/v1/auth"
    private const val REFRESH = "__Secure-dsh_mobile_refresh"
    private val mediaType = "application/json".toMediaType()

    suspend fun login(rawOrigin: String, username: String, password: String, previous: MobileAccountCredential? = null): MobileAccountCredential {
        val url = rawOrigin.trim().toHttpUrl()
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) { "请输入 HTTPS 服务器地址，不要包含路径或凭据" }
        val origin = url.toString().trimEnd('/')
        val (body, cookie) = action(origin, "login", JsonObject(mapOf(
            "username" to JsonPrimitive(username.trim()), "password" to JsonPrimitive(password)
        )), previous?.takeIf { it.origin == origin && it.gatewayName == username.trim() }?.refreshCookie)
        val user = body.getValue("user").jsonObject
        val gateway = body.getValue("mobileGateway").jsonObject
        val id = gateway.getValue("gatewayId").jsonPrimitive.content
        val path = gateway.getValue("path").jsonPrimitive.content
        require(path == "/api/mobile.v1/$id") { "服务器账号协议不兼容，请更新账号网关" }
        val endpoint = origin.replaceFirst("https://", "wss://") + path
        return MobileAccountCredential(origin, user.getValue("userId").jsonPrimitive.content.toLong(),
            body.getValue("serverId").jsonPrimitive.content, id,
            gateway.getValue("gatewayName").jsonPrimitive.content, endpoint,
            requireNotNull(cookie) { "服务器未返回登录凭据" })
    }

    suspend fun access(credential: MobileAccountCredential, endpoint: String): String {
        require(endpoint == credential.endpoint) { "账号与服务器地址不匹配" }
        val (body, _) = action(credential.origin, "refresh", JsonObject(emptyMap()), credential.refreshCookie)
        require(body.getValue("serverId").jsonPrimitive.content == credential.serverId &&
            body.getValue("user").jsonObject.getValue("userId").jsonPrimitive.content.toLong() == credential.userId &&
            body.getValue("mobileGateway").jsonObject.getValue("gatewayId").jsonPrimitive.content == credential.gatewayId) {
            "服务器或账号身份已变更，请重新登录"
        }
        return body.getValue("accessToken").jsonPrimitive.content.also { require(it.startsWith("dshm.")) }
    }

    suspend fun logout(credential: MobileAccountCredential) {
        action(credential.origin, "logout", JsonObject(emptyMap()), credential.refreshCookie)
    }

    private suspend fun action(origin: String, action: String, payload: JsonObject, refresh: String?): Pair<JsonObject, String?> = withContext(Dispatchers.IO) {
        val challengeUrl = "$origin$AUTH/challenge".toHttpUrl()
        val challenge = execute(Request.Builder().url(challengeUrl).build()).use { response ->
            val body = read(response)
            val cookie = cookies(response, "__Secure-dsh_mobile_challenge")
            requireNotNull(cookie) { "服务器未返回登录验证凭据" }
            body.getValue("challenge").jsonPrimitive.content to cookie
        }
        val cookies = listOfNotNull(challenge.second, refresh).joinToString("; ")
        return@withContext execute(Request.Builder().url("$origin$AUTH/$action")
            .header("X-Dsh-Csrf", challenge.first).header("Cookie", cookies)
            .post(payload.toString().toRequestBody(mediaType)).build()).use { response ->
            read(response) to cookies(response, REFRESH)
        }
    }

    private fun cookies(response: Response, name: String): String? {
        val values = response.headers.values("Set-Cookie").mapNotNull { Cookie.parse(response.request.url, it) }
            .filter { it.name == name && it.secure && it.httpOnly && it.hostOnly && it.path == AUTH }
        require(values.size <= 1) { "服务器返回重复的登录凭据" }
        return values.singleOrNull()?.let { "${it.name}=${it.value}" }
    }

    private fun read(response: Response): JsonObject {
        val source = response.body?.source() ?: error("服务器没有返回数据")
        source.request(65_537)
        require(source.buffer.size <= 65_536) { "服务器登录响应过大" }
        val value = source.readUtf8()
        val body = runCatching { Json.parseToJsonElement(value).jsonObject }.getOrNull()
        check(response.isSuccessful) { "账号请求失败（${response.code}）：${body?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()}" }
        return requireNotNull(body) { "服务器账号协议不兼容" }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}
