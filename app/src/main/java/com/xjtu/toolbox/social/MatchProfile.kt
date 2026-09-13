package com.xjtu.toolbox.social

import com.xjtu.toolbox.schedule.CourseItem
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 课表匹配：把自己的作息压成一段分享码，跟朋友交换后在本地算契合度。
 *
 * ## 为什么是分享码而不是服务器
 *
 * 这个 App 没有后端，全部数据都来自校方系统。为了一个"看看跟谁课表合得来"的功能
 * 去搭一套账号体系和用户数据库，代价和风险都不成比例——那意味着我们要开始
 * **保管学生的课表**。分享码把交换这一步交回给用户：他给谁看、看多少，自己决定，
 * 我们一条都不存。
 *
 * ## 每一维都可选
 *
 * [Dimensions] 里每一项都能单独关掉，关掉的维度不进分享码，对方也就无从得知。
 * 关掉之外还有一层：**没有数据的维度也不进码**。早先的版本只看开关不看数据，
 * 没读到课表时照样塞一张全空的网格出去，对方算出来是"共同空闲 100%"——
 * 一个纯属虚构的满分。现在两个条件都要满足，见 [build]。
 *
 * ## 这个对象不碰 Android
 *
 * 编解码走 `java.util.Base64`（minSdk 31 远高于它要求的 26）而不是 `android.util.Base64`，
 * 打分和取整逻辑因此能在普通 JVM 单元测试里跑，见 `MatchProfileTest`。
 */
object MatchProfile {

    /** 分享码版本。改了字段布局就加一，解码端据此拒绝旧码而不是解出乱数据。 */
    private const val VERSION = 3

    /** 一周 7 天 × 11 节的占用位图，用 77 个字符的 0/1 串表示。 */
    const val DAYS = 7

    /**
     * 每天 11 节。这个数要跟 [com.xjtu.toolbox.util.XjtuTime] 的作息表对齐——
     * 之前写的 12 会凭空多出一列谁都没课的格子，把"共同空闲"整体抬高 1/12。
     */
    const val SECTIONS = 11

    /**
     * 打分只看周一到周五。
     *
     * 周末两个人都空是常态，算进去等于给所有人加同一笔分，反而把"作息合拍"和
     * "刚好都没课"压成同一个数。周末照样进网格供界面展示，只是不参与百分比。
     */
    const val WEEKDAYS = 5

    private const val CELLS = DAYS * SECTIONS

    /** 解压上限。分享码来自别人，别让一段几百字节的输入膨胀成几十兆。 */
    private const val MAX_INFLATED = 64 * 1024

    val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    data class Dimensions(
        val schedule: Boolean = true,
        val sameCourses: Boolean = true,
        val identity: Boolean = true,
        val diningHours: Boolean = false,
        val dietTags: Boolean = false,
    )

    /** 共享的课程条目。课程号用来判同课，课名只为了让结果能写出"你俩都上《大学物理》"。 */
    data class SharedCourse(val code: String, val name: String)

    data class Profile(
        val nickname: String = "",
        /** 77 位 0/1，'1' = 这一格有课。空串表示这一维没分享。 */
        val busyGrid: String = "",
        /** 课程号 + 课名。用课程号判同课：同一门课不同教学班的课名可能带后缀。 */
        val courses: List<SharedCourse> = emptyList(),
        /** 一天 24 小时里在食堂消费过的小时，0/1 串；空表示没分享。 */
        val diningHours: String = "",
        val dietTags: Set<String> = emptySet(),
        /** 入学年份，如 2023。0 表示没分享。 */
        val grade: Int = 0,
        val profession: String = "",
        val department: String = "",
        val academy: String = "",
        /** 校区。是硬门槛而不是加分项，见 [compare]。 */
        val campus: String = "",
        val className: String = "",
    ) {
        val courseCodes: Set<String> get() = courses.map { it.code }.toSet()
    }

    // ── 构建 ────────────────────────────────────────────────

    /**
     * 字段分隔符会出现在昵称、课名这些自由文本里。
     *
     * 昵称里打一个 `|` 就能把后面每一个字段错位一格，对方解出来的年级、校区全是别的东西。
     * 与其在解码端猜，不如在进码之前把三个分隔符换成空格。
     */
    private fun clean(s: String): String =
        s.replace('|', ' ').replace(',', ' ').replace('~', ' ')
            .replace(Regex("""\s+"""), " ").trim()

    /**
     * 从本地数据攒一份档案。
     *
     * [courses] 传整学期的课，不按周过滤：匹配看的是"平时什么作息"，
     * 只按当前这一周算会被单双周课程带偏。
     */
    fun build(
        nickname: String,
        courses: List<CourseItem>,
        diningHourCounts: Map<Int, Int>,
        dietTags: Set<String>,
        profile: com.xjtu.toolbox.hello.HelloProfile?,
        dims: Dimensions,
    ): Profile {
        val grid = CharArray(CELLS) { '0' }
        for (c in courses) {
            if (c.dayOfWeek !in 1..DAYS) continue
            val from = c.startSection.coerceIn(1, SECTIONS)
            val to = c.endSection.coerceIn(from, SECTIONS)
            for (sec in from..to) grid[(c.dayOfWeek - 1) * SECTIONS + (sec - 1)] = '1'
        }
        // 每一维都是「用户开了」且「确实有数据」才进码。缺了后半句就会分享出
        // 一张全空网格 / 一串全 0 的饭点，对方那边看不出是"没有"还是"全天有空"。
        val hasSchedule = courses.any { it.dayOfWeek in 1..DAYS }
        val diningBits = String(CharArray(24) { h ->
            // 只保留"经常"去的时段：偶尔一次不代表作息。阈值取该小时消费数 ≥ 2。
            if ((diningHourCounts[h] ?: 0) >= 2) '1' else '0'
        })
        val cleanTags = dietTags.map { clean(it) }.filter { it.isNotEmpty() }.toSet()
        return Profile(
            nickname = clean(nickname).take(12),
            busyGrid = if (dims.schedule && hasSchedule) String(grid) else "",
            courses = if (dims.sameCourses) {
                courses.mapNotNull { c ->
                    val code = clean(c.courseCode)
                    if (code.isEmpty()) null else SharedCourse(code, clean(c.courseName).take(14))
                }.distinctBy { it.code }.sortedBy { it.code }
            } else emptyList(),
            diningHours = if (dims.diningHours && diningBits.contains('1')) diningBits else "",
            dietTags = if (dims.dietTags) cleanTags else emptySet(),
            // 学号本身不进码：数字相邻只说明报到顺序。进去的是它派生出的有含义的那面。
            grade = if (dims.identity) profile?.grade ?: 0 else 0,
            profession = if (dims.identity) clean(profile?.professionName.orEmpty()) else "",
            department = if (dims.identity) clean(profile?.departmentName.orEmpty()) else "",
            academy = if (dims.identity) clean(profile?.academyName.orEmpty()) else "",
            campus = if (dims.identity) clean(profile?.campusName.orEmpty()) else "",
            className = if (dims.identity) clean(profile?.className.orEmpty()) else "",
        )
    }

    // ── 编解码 ──────────────────────────────────────────────
    //
    // 字段用 '|' 分隔、集合内用 ',' 分隔、课程号与课名之间用 '~'，
    // deflate 后 Base64（URL-safe、无填充）。压缩是必要的：77 位网格加几十门课，
    // 明文有一两千字符，粘不进聊天框。

    fun encode(p: Profile): String {
        val raw = listOf(
            VERSION.toString(),
            p.nickname,
            p.busyGrid,
            p.courses.joinToString(",") { "${it.code}~${it.name}" },
            p.diningHours,
            p.dietTags.sorted().joinToString(","),
            p.grade.toString(),
            p.profession,
            p.department,
            p.academy,
            p.campus,
            p.className,
        ).joinToString("|")
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(raw))
    }

    /** 解不出就返回 null，由调用方提示"这个码不对"，不要抛给用户一个异常。 */
    fun decode(code: String): Profile? = try {
        // 聊天软件会给长串自动折行，粘回来带换行和空格。先全部去掉再解。
        val compact = code.filterNot { it.isWhitespace() }
        val parts = inflate(java.util.Base64.getUrlDecoder().decode(compact)).split("|")
        if (parts.size < 12 || parts[0].toIntOrNull() != VERSION) {
            null
        } else {
            Profile(
                nickname = parts[1],
                busyGrid = parts[2].takeIf { it.length == CELLS && it.all { c -> c == '0' || c == '1' } }
                    .orEmpty(),
                courses = parts[3].split(",").mapNotNull { entry ->
                    if (entry.isBlank()) return@mapNotNull null
                    val at = entry.indexOf('~')
                    if (at < 0) SharedCourse(entry, "")
                    else SharedCourse(entry.substring(0, at), entry.substring(at + 1))
                }.filter { it.code.isNotBlank() }.distinctBy { it.code },
                diningHours = parts[4].takeIf { it.length == 24 }.orEmpty(),
                dietTags = parts[5].split(",").filter { it.isNotBlank() }.toSet(),
                grade = parts[6].toIntOrNull() ?: 0,
                profession = parts[7],
                department = parts[8],
                academy = parts[9],
                campus = parts[10],
                className = parts[11],
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun deflate(raw: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        // 循环写：一门课的课名就有十几字节，几十门课早就超过任何"够用"的固定缓冲区，
        // 单次 deflate 写不完会静默截断，生成一段解不开的码。
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!inflater.finished() && out.size() <= MAX_INFLATED) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // ── 打分 ────────────────────────────────────────────────

    data class Facet(
        val label: String,
        val emoji: String,
        val score: Int,
        val detail: String,
        /** 权重。共同空闲是这个功能的本体，口味标签只是佐料，不该等权平均。 */
        val weight: Int,
    )

    /** 一段两人都空的连续时间。[day] 从 0 起（周一），[from]/[to] 是 1 起的节次，闭区间。 */
    data class FreeBlock(val day: Int, val from: Int, val to: Int) {
        val length: Int get() = to - from + 1
    }

    /** 叠加网格的每一格。 */
    object Cell {
        const val BOTH_BUSY = '0'
        const val ONE_FREE = '1'
        const val BOTH_FREE = '2'
    }

    data class Result(
        val overall: Int,
        /** 一句给分数配的话，比光秃秃一个百分比好读。 */
        val verdict: String,
        val facets: List<Facet>,
        /** 77 位，取值见 [Cell]；两人有一方没分享课表时为 null。 */
        val overlapGrid: String? = null,
        /** 工作日里两人都空的连续时段，长的在前。 */
        val freeBlocks: List<FreeBlock> = emptyList(),
        /** 共同课程的课名（没拿到课名的退回课程号）。 */
        val sharedCourses: List<String> = emptyList(),
        /**
         * 拦路的硬条件，比如不同校区。不并进百分比——共同空闲 90% 但一个兴庆一个创新港，
         * 该说的是"约不上"，不是给这个数打个折。
         */
        val blocker: String? = null,
        /** 不打分、只陈述的事实：同专业、同书院、差几届。 */
        val notes: List<String> = emptyList(),
    )

    /**
     * 逐维打分再按权重平均。
     *
     * **只对双方都分享了的维度打分**——一方关掉某项时那一维不参与，
     * 而不是记 0 分。否则"对方比较注重隐私"会被算成"你俩不合"。
     */
    fun compare(mine: Profile, theirs: Profile): Result {
        var overlapGrid: String? = null
        var blocks = emptyList<FreeBlock>()
        var sharedCourses = emptyList<String>()

        val facets = buildList {
            if (mine.busyGrid.length == CELLS && theirs.busyGrid.length == CELLS) {
                overlapGrid = String(CharArray(CELLS) { i ->
                    val free = (if (mine.busyGrid[i] == '0') 1 else 0) +
                        (if (theirs.busyGrid[i] == '0') 1 else 0)
                    when (free) {
                        2 -> Cell.BOTH_FREE
                        1 -> Cell.ONE_FREE
                        else -> Cell.BOTH_BUSY
                    }
                })
                blocks = freeBlocks(overlapGrid!!)

                var bothFree = 0
                var myFree = 0
                var theirFree = 0
                for (d in 0 until WEEKDAYS) for (s in 0 until SECTIONS) {
                    val i = d * SECTIONS + s
                    val a = mine.busyGrid[i] == '0'
                    val b = theirs.busyGrid[i] == '0'
                    if (a) myFree++
                    if (b) theirFree++
                    if (a && b) bothFree++
                }
                // 分母取两人空闲的较小值，也就是"理论上最多能重合多少"。
                // 用总格子数当分母的话，人人都是七成起步，分不出高下；
                // 用它当分母，问的才是「忙的那个人的空档，另一个人在不在」。
                val ceiling = minOf(myFree, theirFree)
                val pct = if (ceiling == 0) 0 else (bothFree * 100 / ceiling).coerceIn(0, 100)
                val longest = blocks.firstOrNull()
                add(
                    Facet(
                        "共同空闲", "🕒", pct,
                        if (bothFree == 0) "工作日没有一节课的时间是两个人同时空的"
                        else buildString {
                            append("工作日有 $bothFree 节课的时间你俩都空")
                            if (longest != null && longest.length >= 2) {
                                append("，最长一段是${DAY_NAMES[longest.day]}第 ")
                                append("${longest.from}-${longest.to} 节")
                            }
                        },
                        weight = 3,
                    )
                )
            }
            if (mine.courses.isNotEmpty() && theirs.courses.isNotEmpty()) {
                val shared = mine.courseCodes intersect theirs.courseCodes
                // 用 Jaccard 而不是"共同数 / 我的课数"：后者课少的人天生占便宜。
                val union = (mine.courseCodes + theirs.courseCodes).size
                val pct = if (union == 0) 0 else shared.size * 100 / union
                val names = mine.courses.filter { it.code in shared }
                sharedCourses = names.map { it.name.ifBlank { it.code } }
                add(
                    Facet(
                        "同课", "📚", pct,
                        if (shared.isEmpty()) "没有共同课程，抬头见不着"
                        else "有 ${shared.size} 门课坐同一间教室",
                        weight = 2,
                    )
                )
            }
            // 作息不再单独占一个开关：早八几点、末课几点，分享课表的人已经把这些都给出去了，
            // 再摆一个开关只是隐私剧场。直接从网格推，还能推出更有意思的东西。
            if (mine.busyGrid.length == CELLS && theirs.busyGrid.length == CELLS) {
                val a = dayParts(mine.busyGrid)
                val b = dayParts(theirs.busyGrid)
                if (a.sum() > 0 && b.sum() > 0) {
                    add(
                        Facet(
                            "作息", "🌗", cosine(a, b),
                            "你${chronotype(a)}，${theirs.nickname.ifBlank { "对方" }}${chronotype(b)}",
                            weight = 1,
                        )
                    )
                }
            }
            if (mine.diningHours.isNotEmpty() && theirs.diningHours.isNotEmpty()) {
                var both = 0
                var either = 0
                for (i in 0 until 24) {
                    val a = mine.diningHours[i] == '1'
                    val b = theirs.diningHours[i] == '1'
                    if (a && b) both++
                    if (a || b) either++
                }
                val pct = if (either == 0) 0 else both * 100 / either
                add(
                    Facet(
                        "饭点", "🍜", pct,
                        if (both == 0) "吃饭时间基本错开" else "有 $both 个时段常同时在食堂",
                        weight = 1,
                    )
                )
            }
            if (mine.dietTags.isNotEmpty() && theirs.dietTags.isNotEmpty()) {
                val shared = mine.dietTags intersect theirs.dietTags
                val union = (mine.dietTags + theirs.dietTags).size
                val pct = if (union == 0) 0 else shared.size * 100 / union
                add(
                    Facet(
                        "口味", "🌶️", pct,
                        if (shared.isEmpty()) "没有共同口味标签"
                        else "都喜欢：${shared.joinToString("、")}",
                        weight = 1,
                    )
                )
            }
        }
        // 身份只陈述不打分："同专业"是事实不是契合度，折算成百分比只会稀释有用的那几维。
        val notes = buildList {
            if (mine.className.isNotBlank() && mine.className == theirs.className) {
                add("同班 · ${mine.className}")
            } else if (mine.profession.isNotBlank() && mine.profession == theirs.profession) {
                add("同专业 · ${mine.profession}")
            } else if (mine.department.isNotBlank() && mine.department == theirs.department) {
                add("同学院 · ${mine.department}")
            }
            if (mine.academy.isNotBlank() && mine.academy == theirs.academy) {
                add("同书院 · ${mine.academy}")
            }
            if (mine.grade > 0 && theirs.grade > 0) {
                val d = kotlin.math.abs(mine.grade - theirs.grade)
                add(if (d == 0) "同级 · ${mine.grade} 级" else "差 $d 届")
            }
        }

        // 校区：硬门槛。
        val blocker = if (
            mine.campus.isNotBlank() && theirs.campus.isNotBlank() && mine.campus != theirs.campus
        ) {
            "你在${mine.campus}，${theirs.nickname.ifBlank { "对方" }}在${theirs.campus}，约起来不方便"
        } else {
            null
        }

        val totalWeight = facets.sumOf { it.weight }
        val overall = if (totalWeight == 0) 0 else facets.sumOf { it.score * it.weight } / totalWeight
        return Result(
            overall = overall,
            verdict = verdict(overall, facets.isEmpty()),
            facets = facets,
            overlapGrid = overlapGrid,
            freeBlocks = blocks,
            sharedCourses = sharedCourses,
            blocker = blocker,
            notes = notes,
        )
    }

    /** 工作日里连续两节及以上的共同空档，长的排前面；一样长的按周一到周五、从早到晚。 */
    fun freeBlocks(overlap: String, minLength: Int = 2): List<FreeBlock> = buildList {
        for (d in 0 until WEEKDAYS) {
            var run = 0
            for (s in 0 until SECTIONS) {
                if (overlap[d * SECTIONS + s] == Cell.BOTH_FREE) {
                    run++
                } else {
                    if (run >= minLength) add(FreeBlock(d, s - run + 1, s))
                    run = 0
                }
            }
            if (run >= minLength) add(FreeBlock(d, SECTIONS - run + 1, SECTIONS))
        }
    }.sortedWith(compareByDescending<FreeBlock> { it.length }.thenBy { it.day }.thenBy { it.from })

    /** 一周的课按上午（1-4）、下午（5-8）、晚上（9-11）分三堆。 */
    private fun dayParts(grid: String): IntArray {
        val parts = IntArray(3)
        for (d in 0 until WEEKDAYS) for (s in 0 until SECTIONS) {
            if (grid[d * SECTIONS + s] == '1') {
                parts[if (s < 4) 0 else if (s < 8) 1 else 2]++
            }
        }
        return parts
    }

    /** 两个三段分布的余弦相似度。方向一致就接近 100，一个全早八一个全晚课就接近 0。 */
    private fun cosine(a: IntArray, b: IntArray): Int {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0
        // 四舍五入而不是截断：sqrt(8)*sqrt(8) 在浮点下是 8.000000000000002，
        // 两份一模一样的课表算出来会是 99.999…，截断就成了 99 分。
        val cos = dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
        return kotlin.math.round(cos * 100).toInt().coerceIn(0, 100)
    }

    private fun chronotype(parts: IntArray): String {
        val morning = parts[0]
        val afternoon = parts[1]
        val night = parts[2]
        return when {
            night >= morning + afternoon -> "常年晚课"
            morning > afternoon + night -> "是早八战士"
            night > 0 && morning == 0 -> "从不早起"
            else -> "作息挺常规"
        }
    }

    private fun verdict(overall: Int, empty: Boolean): String = when {
        empty -> "没有能比的维度"
        overall >= 85 -> "课表像商量好的"
        overall >= 70 -> "随时能碰头"
        overall >= 55 -> "挤一挤总有时间"
        overall >= 40 -> "得提前约"
        overall >= 20 -> "错峰人生"
        else -> "活在两个平行时空"
    }

    /** 可以直接粘进聊天框的一段战报。 */
    fun summaryText(theirName: String, r: Result): String = buildString {
        appendLine("我和${theirName}的课表匹配：${r.overall}% —— ${r.verdict}")
        r.blocker?.let { appendLine("⚠️ $it") }
        r.facets.forEach { appendLine("${it.emoji} ${it.label} ${it.score}% · ${it.detail}") }
        r.freeBlocks.take(3).forEach {
            appendLine("🕒 ${DAY_NAMES[it.day]}第 ${it.from}-${it.to} 节都有空")
        }
        if (r.notes.isNotEmpty()) appendLine(r.notes.joinToString(" · "))
    }.trim()
}
