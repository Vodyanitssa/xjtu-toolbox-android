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

    /**
     * 这个账号实际所在的考勤站点根地址。
     *
     * 考勤按学生类型分成两套部署：本科在 `bk-kq.xjtu.edu.cn`，研究生在 `kq.xjtu.edu.cn`。
     * 入口统一从 [LOGIN_URL] 进，CAS 会把票签给**属于你那一套**的域名再跳回来。
     * 之前这里把交换地址写死成 kq，于是本科生拿着 bk-kq 的票去 kq 换令牌，
     * 服务端回 `{"code":104,"message":"学生身份与本地数据不一致，无法登录"}`。
     *
     * 路径前缀两边一致（都是 `/sa`），变的只是主机，所以只认落地页的 host。
     */
    var resolvedBaseUrl: String?
        get() = bases[this]
        private set(value) {
            if (value.isNullOrBlank()) bases.remove(this) else bases[this] = value
        }

    /** 交换与探活都要打到 [resolvedBaseUrl]；还没解析出来时退回默认。 */
    private val baseUrl: String get() = resolvedBaseUrl ?: BASE_URL

    private val jsonType = "application/json".toMediaType()
    private val reAuthLock = Any()

    override fun postLogin(response: Response) {
        if (consumeLanding(response.request.url, lastResponseBody)) return
        // 重来一次只为了「这一轮压根没拿到票」的情况。
        // 拿到票但交换失败时 consumeLanding 会直接抛，不会走到这里——
        // 那种情况下再跑一遍会带着已经被消费掉的 ticket，
        // 换回一句「CAS登录浏览器绑定无效或已失效」，把真正的失败原因盖掉。
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
                    .url("$baseUrl/student/home")
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
        rememberBase(normalized)
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

    /**
     * 记下落地页所在的考勤站。
     *
     * 只认 `*.xjtu.edu.cn` 下带 `kq` 的主机——落地页可能是 CAS 自己的域名或别的中转，
     * 照单全收会把令牌打到不相干的地方去。
     */
    private fun rememberBase(url: HttpUrl) {
        val host = url.host.lowercase()
        if (host.endsWith(".xjtu.edu.cn") && "kq" in host.substringBefore('.')) {
            resolvedBaseUrl = "https://$host/sa"
        }
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
            Log.w(TAG, "exchange gave no token: handoff=<$handoff> data=$data")
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
                .url("$baseUrl/auth/cas/exchange")
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
                Log.w(TAG, "exchange failed at $baseUrl: code=$code msg=$msg")
                throw RuntimeException(friendlyExchangeError(code, msg))
            }
            return json.getAsJsonObject("data")
                ?: throw RuntimeException("考勤系统登录交换响应缺少 data")
        }
    }

    /**
     * 把服务端的原始报错换成用户能据以行动的话。
     *
     * 考勤按学生类型分两套部署，学校是逐步开通的：账号没被纳入本科生考勤时，
     * 网页端显示「当前系统没有您的用户信息或访问权限」，而接口这边回的是
     * `code=104 学生身份与本地数据不一致` 或干脆一个 500 —— 原样抛给用户，
     * 看起来像是我们把身份搞错了，其实是那边还没有这个人的数据。
     */
    private fun friendlyExchangeError(code: Int, msg: String): String = when {
        code == 104 || "身份" in msg && "不一致" in msg ->
            "学校的考勤系统里还没有你的数据（网页端同样显示「没有您的用户信息或访问权限」）。" +
                "这不是登录失败，等学校开通后即可使用。"
        "绑定无效" in msg || "已失效" in msg ->
            "登录票据已失效，请退出后重新进入。"
        code >= 500 || "暂时不可用" in msg ->
            "考勤系统暂时不可用（服务端 $code）。也可能是学校还没给你开通，可先在网页端确认。"
        else -> "考勤系统登录失败：$msg"
    }

    private fun followHandoff(handoffPath: String): Response {
        var url = HttpUrl.Builder()
            .scheme("https")
            // 跟着已解析出的站点走，不要写死 HOST——研究生交接同样分部署。
            .host(resolvedBaseUrl?.toHttpUrlOrNull()?.host ?: HOST)
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
        private val bases = Collections.synchronizedMap(WeakHashMap<NewAttendanceLogin, String>())
        const val HOST = "kq.xjtu.edu.cn"
        const val BASE_URL = "https://kq.xjtu.edu.cn/sa"
        const val LOGIN_URL = "$BASE_URL/auth/cas/login/student-pc"
        const val TOKEN_HEADER = "X-Business-Token"
        private const val LEGACY_INTERNAL_HOST = "202.117.22.36"
        private const val MAX_HOPS = 8
    }
}
