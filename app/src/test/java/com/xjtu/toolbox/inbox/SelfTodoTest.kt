package com.xjtu.toolbox.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SelfTodoTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L

    private fun todo(title: String, offset: Int?, created: Long = nowMs) =
        SelfTodoRules.newTodo(title, dueDate = SelfTodoRules.dueAt(offset, now), now = created)

    @Test
    fun `截止是当天深夜，带时区偏移，格式和滴答一致`() {
        assertEquals("2026-10-14T23:59:00+0800", SelfTodoRules.dueAt(0, now))
        assertEquals("2026-10-15T23:59:00+0800", SelfTodoRules.dueAt(1, now))
        assertNull(SelfTodoRules.dueAt(null, now))
    }

    @Test
    fun `滴答的取值，优先级 0 1 3 5，状态 0 和 2`() {
        assertEquals(listOf(0, 1, 3, 5), listOf(
            SelfTodoRules.PRIORITY_NONE, SelfTodoRules.PRIORITY_LOW, SelfTodoRules.PRIORITY_MEDIUM, SelfTodoRules.PRIORITY_HIGH,
        ))
        assertEquals(0, SelfTodoRules.STATUS_OPEN)
        assertEquals(2, SelfTodoRules.STATUS_DONE)
    }

    @Test
    fun `本周日，周日当天是 0，周一是 6`() {
        assertEquals(0, SelfTodoRules.daysToSunday(LocalDate.of(2026, 10, 18)))
        assertEquals(6, SelfTodoRules.daysToSunday(LocalDate.of(2026, 10, 12)))
        assertEquals(4, SelfTodoRules.daysToSunday(LocalDate.of(2026, 10, 14)))
    }

    @Test
    fun `未办完的按截止先后排，没截止的排后面`() {
        val a = todo("没截止", null, created = nowMs)
        val b = todo("明天", 1, created = nowMs + 1)
        val c = todo("今天", 0, created = nowMs + 2)
        val done = SelfTodoRules.complete(todo("已办", 0), nowMs)
        assertEquals(listOf("今天", "明天", "没截止"), SelfTodoRules.open(listOf(a, b, c, done)).map { it.title })
    }

    @Test
    fun `办完，状态 2 并记完成时间，重复办不改时间`() {
        val t = todo("交报告", 0)
        val done = SelfTodoRules.complete(t, nowMs + day)
        assertTrue(done.done)
        assertEquals(SelfTodoRules.STATUS_DONE, done.status)
        assertTrue(done.completedTime!!.startsWith("2026-10-15T"))
        assertEquals(done, SelfTodoRules.complete(done, nowMs + 5 * day))
    }

    @Test
    fun `收纳 id 能互相换算，别家的待办 id 不认`() {
        val t = todo("x", null)
        assertEquals(t.id, SelfTodoRules.todoIdOf(SelfTodoRules.inboxId(t.id)))
        assertNull(SelfTodoRules.todoIdOf("lms:1:2"))
    }

    @Test
    fun `自建待办过了截止还在，作业过了截止就没了`() {
        val self = SelfTodoRules.toInboxItem(todo("交报告", 0), nowMs)
        val homework = OwnInbox.todo(InboxCategories.LMS, "lms:1:1", "高数", "作业", null, expiresAt = self.expiresAt).copy(time = nowMs)
        val data = InboxData(todos = mapOf(InboxCategories.SELF to listOf(self), InboxCategories.LMS to listOf(homework)))
        val twoDaysLater = nowMs + 2 * day
        // 自建的 time 也得是「现在」，否则消息保留期会误伤——这里只测过期这一条规则
        assertEquals(listOf(self.id), InboxRules.todos(data, twoDaysLater).map { it.id })
    }

    @Test
    fun `办完的自建待办进已完成，过期未办的不算办完`() {
        val t = todo("交报告", 0)
        val item = SelfTodoRules.toInboxItem(t, nowMs)
        val before = InboxData(todos = mapOf(InboxCategories.SELF to listOf(item)))
        // 办完 = 下一次推送里没有它
        val after = InboxRules.replaceTodos(before, InboxCategories.SELF, emptyList(), nowMs + 2 * day)
        assertEquals(listOf(item.id), after.finished.map { it.item.id })
        assertFalse(after.finished.single().ignored)
    }

    @Test
    fun `删除，先忽略再去掉，标成已忽略而不是已完成`() {
        val item = SelfTodoRules.toInboxItem(todo("不做了", 1), nowMs)
        val before = InboxData(todos = mapOf(InboxCategories.SELF to listOf(item)))
        val ignored = InboxRules.ignore(before, item.id, nowMs)
        val after = InboxRules.replaceTodos(ignored, InboxCategories.SELF, emptyList(), nowMs)
        assertEquals(1, after.finished.size)
        assertTrue(after.finished.single().ignored)
    }

    @Test
    fun `提醒串解析，到点、提前、全天九点`() {
        assertEquals(0L, SelfTodoRules.triggerOffsetMs("TRIGGER:PT0S"))
        assertEquals(-30 * 60_000L, SelfTodoRules.triggerOffsetMs("TRIGGER:-PT30M"))
        assertEquals(9 * 3_600_000L, SelfTodoRules.triggerOffsetMs("TRIGGER:P0DT9H0M0S"))
        assertEquals(-(86_400_000L + 9 * 3_600_000L), SelfTodoRules.triggerOffsetMs("TRIGGER:-P1DT9H"))
        assertNull(SelfTodoRules.triggerOffsetMs("TRIGGER:garbage"))
    }

    @Test
    fun `全天待办当天九点提醒，已过就不再提醒`() {
        val t = SelfTodoRules.newTodo("交报告", dueDate = SelfTodoRules.dueAt(1, now), allDay = true, remind = true, now = nowMs)
        // 明天 09:00（上海）
        val tomorrow9 = ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(tomorrow9, SelfTodoRules.nextReminderAt(t, nowMs))
        assertNull(SelfTodoRules.nextReminderAt(t, tomorrow9 + 1))
    }

    @Test
    fun `有具体时刻的待办到点提醒`() {
        val (due, allDay) = SelfTodoRules.parseDue("2026-10-16 14:30")!!
        assertFalse(allDay)
        val t = SelfTodoRules.newTodo("开题答辩材料", dueDate = due, remind = true, now = nowMs)
        val expect = ZonedDateTime.of(2026, 10, 16, 14, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expect, SelfTodoRules.nextReminderAt(t, nowMs))
    }

    @Test
    fun `没截止、没要求提醒、办完了，都不提醒`() {
        assertNull(SelfTodoRules.nextReminderAt(SelfTodoRules.newTodo("a", remind = true, now = nowMs), nowMs))
        assertNull(SelfTodoRules.nextReminderAt(SelfTodoRules.newTodo("b", dueDate = SelfTodoRules.dueAt(1, now), now = nowMs), nowMs))
        val done = SelfTodoRules.complete(SelfTodoRules.newTodo("c", dueDate = SelfTodoRules.dueAt(1, now), allDay = true, remind = true, now = nowMs), nowMs)
        assertNull(SelfTodoRules.nextReminderAt(done, nowMs))
    }

    @Test
    fun `屁岱给的截止，只到天按全天，带时刻按具体时间，格式不对返回空`() {
        assertEquals("2026-10-16T23:59:00+0800" to true, SelfTodoRules.parseDue("2026-10-16"))
        assertEquals("2026-10-16T14:30:00+0800" to false, SelfTodoRules.parseDue("2026-10-16 14:30"))
        assertEquals("2026-10-16T08:05:00+0800" to false, SelfTodoRules.parseDue("2026-10-16T8:05"))
        assertNull(SelfTodoRules.parseDue("下周五"))
        assertNull(SelfTodoRules.parseDue("2026-13-40"))
    }

    @Test
    fun `滴答兼容字段能序列化来回，新字段缺省不影响旧数据`() {
        val t = todo("交报告", 1).copy(
            priority = SelfTodoRules.PRIORITY_HIGH, tags = listOf("课程"), repeatFlag = "RRULE:FREQ=WEEKLY",
            reminders = listOf("TRIGGER:P0DT9H0M0S"), items = listOf(SelfTodoItem("写摘要")), externalId = "dida-1",
        )
        val json = com.xjtu.toolbox.util.AppJson.encodeToString(listOf(t))
        assertEquals(listOf(t), com.xjtu.toolbox.util.AppJson.decodeFromString<List<SelfTodo>>(json))
        val legacy = """[{"id":"1","title":"老数据"}]"""
        assertEquals("老数据", com.xjtu.toolbox.util.AppJson.decodeFromString<List<SelfTodo>>(legacy).single().title)
    }
}
