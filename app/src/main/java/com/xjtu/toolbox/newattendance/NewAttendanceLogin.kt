package com.xjtu.toolbox.newattendance

import android.util.Log
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SafetyVerifyRequiredException
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap

/**
 * 新版考勤（kq.xjtu.edu.cn/sa）登录。
 *
 * 入口走 CAS，回调带 `loginRequestId` + `ticket`，再 POST `/sa/auth/cas/exchange`
 * 换成业务令牌。令牌不能放在带初始化器的子类字段里：[XJTULogin] 构造期间就会调
 * [postLogin]，子类属性初始化器随后会把值冲掉。
 */
class NewAttendanceLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(LOGIN_URL, session, visitorId, cachedRsaKey) {

    var authToken: String?
        get() = tokens[this]
        private set(value) {
            if (value.isNullOrBlank()) tokens.remove(this) else tokens[this] = value
        }

    private val jsonType = "application/json".toMediaType()
    private val reAuthLock = Any()

    override fun postLogin(response: Response) {
        if (consumeLanding(response.request.url, lastResponseBody)) return
        client.newCall(Request.Builder().url(LOGIN_URL).get().build()).execute().use { retry ->
            val body = retry.body?.string().orEmpty()
            if (!consumeLanding(retry.request.url, body)) {
                throw RuntimeException("考勤系统登录失败：未取得 CAS 回调票据")
            }
        }
    }

    override fun validateLogin(): Boolean {
        val token = authToken ?: return false
        return try {
            client.newCall(
                Request.Builder()
                    .url("$BASE_URL/student/home")
                    .header(TOKEN_HEADER, token)
                    .get()
                    .build()
            ).execute().use { resp ->
                if (resp.code != 200) return false
                val body = resp.body?.string() ?: return false
                if (isAuthFailureResponse(body)) return false
                body.safeParseJsonObject().get("code")?.takeIf { !it.isJsonNull }?.asInt == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun keepAlive(): KeepAliveStatus {
        return try {
            when {
                validateLogin() -> KeepAliveStatus.VALID
                reAuthenticate() -> KeepAliveStatus.REAUTH_OK
                else -> KeepAliveStatus.AUTH_INVALID
            }
        } catch (_: IOException) {
            KeepAliveStatus.NETWORK_ERROR
        } catch (_: SafetyVerifyRequiredException) {
            KeepAliveStatus.AUTH_INVALID
        } catch (_: Exception) {
            KeepAliveStatus.ERROR
        }
    }

    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        val pair = casAuthenticate(LOGIN_URL) ?: return false
        val parsed = pair.second.toHttpUrlOrNull()
        if (parsed != null && consumeLanding(parsed, pair.first)) return true
        if ("/cas/callback" !in pair.second) return false
        client.newCall(Request.Builder().url(pair.second).get().build()).execute().use { postLogin(it) }
        !authToken.isNullOrBlank()
    }

    private fun consumeLanding(url: HttpUrl, body: String, hops: Int = 0): Boolean {
        if (hops > 2) return false
        val normalized = normalizeRedirect(url)
        val loginRequestId = normalized.queryParameter("loginRequestId")?.trim()
        val ticket = normalized.queryParameter("ticket")?.trim()
        if (!loginRequestId.isNullOrBlank() && !ticket.isNullOrBlank()) {
            applyExchange(loginRequestId, ticket, hops)
            return !authToken.isNullOrBlank()
        }
        extractToken(body)?.let {
            authToken = it
            return true
        }
        return false
    }

    private fun applyExchange(loginRequestId: String, ticket: String, hops: Int) {
        val data = exchange(loginRequestId, ticket)
        val token = data.get("tokenValue")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (token.isNotEmpty()) {
            authToken = token
            Log.d(TAG, "got business token, length=${token.length}")
            return
        }
        val handoff = data.get("handoffPath")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (!isSafeHandoffPath(handoff)) {
            val msg = data.get("message")?.takeIf { !it.isJsonNull }?.asString
            throw RuntimeException("考勤系统登录失败：${msg ?: "交换登录票据未返回业务令牌"}")
        }
        followHandoff(handoff).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!consumeLanding(resp.request.url, body, hops + 1)) {
                throw RuntimeException("考勤系统研究生交接后仍未取得业务令牌")
            }
        }
    }

    private fun exchange(loginRequestId: String, ticket: String): JsonObject {
        val payload = JsonObject().apply {
            addProperty("loginRequestId", loginRequestId)
            addProperty("ticket", ticket)
        }
        client.newCall(
            Request.Builder()
                .url(EXCHANGE_URL)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(jsonType))
                .build()
        ).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("考勤系统登录交换失败 (HTTP ${resp.code})")
            val json = body.safeParseJsonObject()
            val code = json.get("code")?.takeIf { !it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = json.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "code=$code"
                throw RuntimeException("考勤系统登录交换失败：$msg")
            }
            return json.getAsJsonObject("data")
                ?: throw RuntimeException("考勤系统登录交换响应缺少 data")
        }
    }

    private fun followHandoff(handoffPath: String): Response {
        var url = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegments(handoffPath.trimStart('/'))
            .build()
        val hopClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        repeat(MAX_HOPS) {
            val resp = hopClient.newCall(Request.Builder().url(url).get().build()).execute()
            if (resp.code !in 300..399) return resp
            val location = resp.header("Location")
            resp.close()
            if (location.isNullOrBlank()) throw RuntimeException("考勤系统 handoff 缺少跳转地址")
            val resolved = url.resolve(location) ?: throw RuntimeException("考勤系统 handoff 跳转地址无效")
            url = normalizeRedirect(resolved)
        }
        throw RuntimeException("考勤系统 handoff 跳转次数过多")
    }

    private fun extractToken(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val data = body.safeParseJsonObject().getAsJsonObject("data") ?: return null
            data.get("tokenValue")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSafeHandoffPath(path: String): Boolean =
        path == "/sa/auth/cas/handoff/graduate-student-pc" ||
            path == "/sa/auth/cas/handoff/graduate-student-h5"

    private fun normalizeRedirect(url: HttpUrl): HttpUrl {
        var next = url
        if (next.host.equals(LEGACY_INTERNAL_HOST, ignoreCase = true)) {
            next = next.newBuilder().scheme("https").host(HOST).port(443).build()
        }
        val service = next.queryParameter("service") ?: return next
        if (LEGACY_INTERNAL_HOST !in service) return next
        val rewritten = service
            .replace("https://$LEGACY_INTERNAL_HOST", "https://$HOST")
            .replace("http://$LEGACY_INTERNAL_HOST", "https://$HOST")
        return next.newBuilder().setQueryParameter("service", rewritten).build()
    }

    companion object {
        private const val TAG = "NewAttendanceLogin"
        private val tokens = Collections.synchronizedMap(WeakHashMap<NewAttendanceLogin, String>())
        const val HOST = "kq.xjtu.edu.cn"
        const val BASE_URL = "https://kq.xjtu.edu.cn/sa"
        const val LOGIN_URL = "$BASE_URL/auth/cas/login/student-pc"
        const val TOKEN_HEADER = "X-Business-Token"
        private const val EXCHANGE_URL = "$BASE_URL/auth/cas/exchange"
        private const val LEGACY_INTERNAL_HOST = "202.117.22.36"
        private const val MAX_HOPS = 8
    }
}
