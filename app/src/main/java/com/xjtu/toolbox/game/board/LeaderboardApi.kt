package com.xjtu.toolbox.game.board

import com.xjtu.toolbox.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 榜上一行：名次、名称、分数。就这三样，服务端也不存别的。 */
data class BoardRow(val rank: Int, val name: String, val score: Int)

/**
 * 一次拉取的结果。
 *
 * [total] 是参与人数（不是榜长），[me] 是自己在榜上的那一行，不在前 [Leaderboard.LIMIT] 名内时为 null——
 * 玩家想知道自己排多少，但榜只放得下前一百。
 */
data class BoardSnapshot(val rows: List<BoardRow>, val total: Int, val me: BoardRow?) {

    /** 分数线：够到这个分数才算上榜。榜没满时人人都能上。 */
    val cutoff: Int get() = if (rows.size < Leaderboard.LIMIT) 0 else rows.last().score

    fun qualifies(score: Int): Boolean = rows.size < Leaderboard.LIMIT || score > cutoff
}

/**
 * 全服榜接口。服务端是 `server/leaderboard`，部署在 zyxf.top 后面（见那边的 README）。
 *
 * 分数**不做任何校验**：整局游戏都在客户端跑，谁都能伪造一个分数传上来。所以这里只做
 * 「别把明显荒唐的值写进库」的粗筛，不做防作弊——按信任制设计，发现脏数据手工清。
 * 也正因为这个入口谁都能写，它单独部署、独立进程，不和持有密钥的资料站后端共用。
 */
object LeaderboardApi {

    /**
     * 服务端地址，构建期可覆盖（见 app/build.gradle.kts 的 LEADERBOARD_BASE_URL）：
     * 默认 `zyxf.top` 的 `/games/`，走同一张证书；本地联调可指到 `http://127.0.0.1:4100/games`。
     */
    private val HOST = com.xjtu.toolbox.BuildConfig.LEADERBOARD_BASE_URL.trimEnd('/')

    /** 地址为空 = 后端没上，整个排行榜功能不出现（本地构建可以留空）。 */
    val configured: Boolean get() = HOST.isNotBlank()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 拉榜单。[uid] 传上来只为了在响应里回一条 [BoardSnapshot.me]，不参与排名计算。 */
    suspend fun board(game: String, uid: String, limit: Int = Leaderboard.LIMIT): BoardSnapshot =
        withContext(Dispatchers.IO) {
            val url = "$HOST/board?game=${enc(game)}&uid=${enc(uid)}&limit=$limit"
            val json = JSONObject(call(Request.Builder().url(url).get().build()))
            val rows = json.optJSONArray("rows") ?: JSONArray()
            BoardSnapshot(
                rows = (0 until rows.length()).map { i ->
                    val r = rows.getJSONObject(i)
                    BoardRow(
                        rank = r.optInt("rank", i + 1),
                        name = r.optString("name").ifBlank { LeaderboardStore.ANONYMOUS },
                        score = r.optInt("score", 0),
                    )
                },
                total = json.optInt("total", 0),
                me = json.optJSONObject("me")?.let {
                    BoardRow(
                        rank = it.optInt("rank", 0),
                        name = it.optString("name").ifBlank { LeaderboardStore.ANONYMOUS },
                        score = it.optInt("score", 0),
                    )
                },
            )
        }

    /**
     * 上报成绩。[name] 为 null 即匿名，服务端会落成 [LeaderboardStore.ANONYMOUS]。
     *
     * 服务端按「游戏 + uid」去重，同一个人同一款游戏只有一行，分数只增不减，
     * 所以重复调用是安全的，不用先查再写。
     */
    suspend fun submit(game: String, uid: String, name: String?, score: Int): Unit =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("game", game)
                .put("uid", uid)
                .put("name", name?.takeIf { it.isNotBlank() } ?: LeaderboardStore.ANONYMOUS)
                .put("score", score)
                .toString()
            call(
                Request.Builder().url("$HOST/score")
                    .post(body.toRequestBody(JSON))
                    .build()
            )
        }

    /** 非 2xx 一律抛 [LeaderboardError]，UI 层统一按「榜单暂时不可用」处理。 */
    private fun call(request: Request): String {
        client.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                throw LeaderboardError(
                    message = json?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "榜单暂时不可用（${resp.code}）",
                    code = json?.optString("code")?.takeIf { it.isNotBlank() },
                )
            }
            return text
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** 服务端判定这个名字不能上榜（命中禁止词或被内容审核拦下）。 */
    const val CODE_NAME_REJECTED = "name_rejected"

    /** 这个名字已经被别人占了：同一款游戏里昵称是独占的。 */
    const val CODE_NAME_TAKEN = "name_taken"

    /**
     * 服务端这次没能把名字送出去审（内容审核服务抖动）。不是名字本身的问题，重试就好，
     * 服务端**不会**因此放一个没审过的名字进榜。
     */
    const val CODE_MODERATION_UNAVAILABLE = "moderation_unavailable"
}

class LeaderboardError(message: String, val code: String? = null) : Exception(message) {

    /** 名字本身有问题（被内容审核拦下、或已被别人占用）：该让玩家换一个。 */
    val nameProblem: Boolean
        get() = code == LeaderboardApi.CODE_NAME_REJECTED || code == LeaderboardApi.CODE_NAME_TAKEN

    /**
     * 该把服务端那句话原样展示给玩家、并让弹窗留在原地。
     *
     * 比 [nameProblem] 宽：审核没跑成时也算——那句话是「稍后再试一次」，
     * 当成网络故障报「检查网络再试」会把人引到错的方向。
     */
    val showableProblem: Boolean
        get() = nameProblem || code == LeaderboardApi.CODE_MODERATION_UNAVAILABLE
}
