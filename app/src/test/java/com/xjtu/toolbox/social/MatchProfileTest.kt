package com.xjtu.toolbox.social

import com.xjtu.toolbox.schedule.CourseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchProfileTest {

    private fun course(
        day: Int,
        from: Int,
        to: Int = from + 1,
        code: String = "C$day$from",
        name: String = "课$day$from",
    ) = CourseItem(
        courseName = name,
        teacher = "",
        location = "",
        weekBits = "1".repeat(20),
        dayOfWeek = day,
        startSection = from,
        endSection = to,
        courseCode = code,
        courseType = "",
    )

    private fun build(
        courses: List<CourseItem>,
        nickname: String = "",
        dining: Map<Int, Int> = emptyMap(),
        tags: Set<String> = emptySet(),
        dims: MatchProfile.Dimensions = MatchProfile.Dimensions(),
    ) = MatchProfile.build(nickname, courses, dining, tags, null, dims)

    // ── 分享的东西必须真的存在 ───────────────────────────

    @Test
    fun 没有课表时不分享一张全空的网格() {
        // 旧版本只看开关不看数据：没读到课表照样塞 84 个 '0' 出去，
        // 对方解出来是"这人全周有空"，算出满分的共同空闲。
        val p = build(emptyList())
        assertEquals("", p.busyGrid)
        assertTrue(p.courses.isEmpty())

        val other = build(listOf(course(1, 1), course(3, 5)))
        val r = MatchProfile.compare(p, other)
        assertTrue(r.facets.none { it.label == "共同空闲" })
        assertNull(r.overlapGrid)
    }

    @Test
    fun 没有食堂记录时不分享一串全零的饭点() {
        val dims = MatchProfile.Dimensions(diningHours = true)
        assertEquals("", build(listOf(course(1, 1)), dining = emptyMap(), dims = dims).diningHours)
        // 只去过一次的时段不算习惯，阈值是 2。
        assertEquals("", build(listOf(course(1, 1)), dining = mapOf(12 to 1), dims = dims).diningHours)
        val real = build(listOf(course(1, 1)), dining = mapOf(12 to 3), dims = dims)
        assertEquals(24, real.diningHours.length)
        assertEquals('1', real.diningHours[12])
    }

    @Test
    fun 网格是每天十一节() {
        val p = build(listOf(course(1, 1, 2)))
        assertEquals(MatchProfile.DAYS * MatchProfile.SECTIONS, p.busyGrid.length)
        assertEquals(77, p.busyGrid.length)
        assertEquals("11" + "0".repeat(9), p.busyGrid.take(11))
    }

    // ── 编解码 ──────────────────────────────────────────

    @Test
    fun 编解码往返() {
        val mine = build(
            listOf(course(1, 1, 2), course(3, 5, 6), course(5, 9, 11)),
            nickname = "阿离",
            dining = mapOf(12 to 5, 18 to 4),
            tags = setOf("辣", "面食"),
            dims = MatchProfile.Dimensions(diningHours = true, dietTags = true),
        )
        val back = MatchProfile.decode(MatchProfile.encode(mine))
        assertNotNull(back)
        assertEquals(mine.nickname, back!!.nickname)
        assertEquals(mine.busyGrid, back.busyGrid)
        assertEquals(mine.courses, back.courses)
        assertEquals(mine.diningHours, back.diningHours)
        assertEquals(mine.dietTags, back.dietTags)
    }

    @Test
    fun 昵称里的分隔符不会把后面的字段整体错位() {
        // 一个 '|' 就能让年级、校区全读成别的字段。进码之前换掉。
        val p = build(listOf(course(1, 1)), nickname = "a|b,c~d")
        assertTrue(p.nickname.none { it == '|' || it == ',' || it == '~' })
        val back = MatchProfile.decode(MatchProfile.encode(p))
        assertNotNull(back)
        assertEquals(p.busyGrid, back!!.busyGrid)
        assertEquals(p.courses, back.courses)
    }

    @Test
    fun 课名很多时也不会截断成解不开的码() {
        val many = (1..7).flatMap { d -> (1..11).map { s -> course(d, s, s, "CODE$d$s", "很长的课程名称$d$s") } }
        val p = build(many)
        val back = MatchProfile.decode(MatchProfile.encode(p))
        assertNotNull(back)
        assertEquals(p.courses.size, back!!.courses.size)
        assertEquals(p.courses, back.courses)
    }

    @Test
    fun 粘贴时带上换行也能解开() {
        val code = MatchProfile.encode(build(listOf(course(2, 3))))
        val wrapped = code.chunked(20).joinToString("\n  ")
        assertEquals(MatchProfile.decode(code), MatchProfile.decode(wrapped))
    }

    @Test
    fun 乱码和旧版码都返回空而不是抛异常() {
        assertNull(MatchProfile.decode(""))
        assertNull(MatchProfile.decode("这不是一段码"))
        assertNull(MatchProfile.decode("AAAAAAAAAAAA"))
    }

    // ── 打分 ────────────────────────────────────────────

    @Test
    fun 同一张课表得满分() {
        val mine = build(listOf(course(1, 1, 2), course(3, 5, 6)))
        val r = MatchProfile.compare(mine, mine)
        assertEquals(100, r.facets.first { it.label == "共同空闲" }.score)
        assertEquals(100, r.facets.first { it.label == "同课" }.score)
        assertEquals(100, r.overall)
    }

    @Test
    fun 完全错开的作息分数低于完全重合() {
        // 一个全上午、一个全晚上：能一起的时间只剩下午。
        val morning = build((1..5).flatMap { d -> (1..4).map { s -> course(d, s, s, "M$d$s") } })
        val night = build((1..5).flatMap { d -> (9..11).map { s -> course(d, s, s, "N$d$s") } })
        val apart = MatchProfile.compare(morning, night)
        val together = MatchProfile.compare(morning, morning)
        assertTrue(
            "错开的共同空闲应低于重合的",
            apart.facets.first { it.label == "共同空闲" }.score <
                together.facets.first { it.label == "共同空闲" }.score
        )
        assertEquals(0, apart.facets.first { it.label == "作息" }.score)
        assertEquals(100, together.facets.first { it.label == "作息" }.score)
    }

    @Test
    fun 周末不参与打分() {
        // 只有周六课不同：周末不进分母，共同空闲应当仍是满分。
        val a = build(listOf(course(1, 1, 2)))
        val b = build(listOf(course(1, 1, 2), course(6, 1, 4, "SAT")))
        assertEquals(100, MatchProfile.compare(a, b).facets.first { it.label == "共同空闲" }.score)
    }

    @Test
    fun 共同空档按长度排序且只取连着两节以上() {
        // 周一 3-4 空两节，周二 1-11 全空（对方周二也没课）。
        val mine = build(
            listOf(course(1, 1, 2), course(1, 5, 11), course(3, 1, 11))
        )
        val theirs = build(listOf(course(1, 1, 2), course(1, 5, 11), course(3, 1, 11)))
        val blocks = MatchProfile.compare(mine, theirs).freeBlocks
        assertTrue(blocks.isNotEmpty())
        // 最长的一段排最前
        assertEquals(blocks.maxOf { it.length }, blocks.first().length)
        assertTrue("不该出现单节空档", blocks.all { it.length >= 2 })
        assertTrue("周末不列进可约时段", blocks.all { it.day < MatchProfile.WEEKDAYS })
        assertTrue(blocks.any { it.day == 0 && it.from == 3 && it.to == 4 })
    }

    @Test
    fun 叠加网格三档取值() {
        val mine = build(listOf(course(1, 1, 1)))
        val theirs = build(listOf(course(1, 1, 1), course(1, 2, 2)))
        val grid = MatchProfile.compare(mine, theirs).overlapGrid
        assertNotNull(grid)
        assertEquals(MatchProfile.Cell.BOTH_BUSY, grid!![0])
        assertEquals(MatchProfile.Cell.ONE_FREE, grid[1])
        assertEquals(MatchProfile.Cell.BOTH_FREE, grid[2])
    }

    @Test
    fun 对方少分享几项不会被算成不合拍() {
        // 只分享课表的人和什么都分享的人比，参与打分的只有课表相关那几项。
        val full = build(
            listOf(course(1, 1, 2)),
            dining = mapOf(12 to 3),
            tags = setOf("辣"),
            dims = MatchProfile.Dimensions(diningHours = true, dietTags = true),
        )
        val shy = build(
            listOf(course(1, 1, 2)),
            dims = MatchProfile.Dimensions(diningHours = false, dietTags = false),
        )
        val r = MatchProfile.compare(full, shy)
        assertTrue(r.facets.none { it.label == "饭点" || it.label == "口味" })
        assertEquals(100, r.overall)
    }

    @Test
    fun 共同课程带出课名() {
        val mine = build(listOf(course(1, 1, 2, "PHY101", "大学物理")))
        val theirs = build(listOf(course(3, 5, 6, "PHY101", "大学物理")))
        val r = MatchProfile.compare(mine, theirs)
        assertEquals(listOf("大学物理"), r.sharedCourses)
    }

    @Test
    fun 不同校区是硬门槛而不是打折() {
        val mine = build(listOf(course(1, 1, 2))).copy(campus = "兴庆校区")
        val theirs = build(listOf(course(1, 1, 2))).copy(campus = "创新港", nickname = "小王")
        val r = MatchProfile.compare(mine, theirs)
        assertNotNull(r.blocker)
        // 门槛不并进百分比：分数照旧是 100，界面把这句话摆在数字上面。
        assertEquals(100, r.overall)
    }

    @Test
    fun 权重让共同空闲说话比口味大声() {
        val base = listOf(course(1, 1, 2))
        val dims = MatchProfile.Dimensions(dietTags = true)
        // 课表完全一致、口味完全不同
        val a = MatchProfile.build("", base, emptyMap(), setOf("辣"), null, dims)
        val b = MatchProfile.build("", base, emptyMap(), setOf("甜"), null, dims)
        val r = MatchProfile.compare(a, b)
        assertEquals(0, r.facets.first { it.label == "口味" }.score)
        // 共同空闲 100×3 + 同课 100×2 + 作息 100×1 + 口味 0×1 = 600/7
        assertEquals(85, r.overall)
    }
}
