@file:OptIn(ExperimentalLayoutApi::class)

package com.xjtu.toolbox.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.XjtuTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 课表匹配度。
 *
 * 全程离线：生成一段分享码给朋友，粘贴朋友的码算契合度。没有服务器、没有账号，
 * 我们一条数据都不经手——给谁看、看多少由用户自己决定，见 [MatchProfile] 的说明。
 *
 * 结果不只给一个百分比：一张 7×11 的叠加网格画出"哪几格你俩同时空着"，
 * 下面直接列出可以约的时段。百分比是结论，网格才是理由。
 */
@Composable
fun MatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    var nickname by remember { mutableStateOf("") }
    var dims by remember { mutableStateOf(MatchProfile.Dimensions()) }
    var dietInput by remember { mutableStateOf("") }
    var courses by remember { mutableStateOf<List<CourseItem>>(emptyList()) }
    var diningCounts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    var profile by remember { mutableStateOf<com.xjtu.toolbox.hello.HelloProfile?>(null) }
    var theirCode by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<MatchProfile.Result?>(null) }
    var theirName by remember { mutableStateOf("") }
    var decodeError by remember { mutableStateOf<String?>(null) }

    // 课表和消费记录都读本地缓存，这个功能不为自己发任何请求。
    // 缓存是空的（没进过日程页/校园卡页）时对应维度就没数据，界面会说明。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            courses = runCatching {
                val dc = com.xjtu.toolbox.util.DataCache(context)
                val gson = com.google.gson.Gson()
                val term = dc.get("schedule_term_list", Long.MAX_VALUE)
                    ?.let { gson.fromJson(it, Array<String>::class.java)?.firstOrNull() }
                term?.let { t ->
                    dc.get("schedule_$t", Long.MAX_VALUE)?.let { json ->
                        gson.fromJson(json, Array<CourseItem>::class.java).toList()
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
            diningCounts = runCatching { DiningHabit.readCachedHourCounts(context) }
                .getOrDefault(emptyMap())
            // 只读缓存，不为这个功能触发登录。
            profile = runCatching { com.xjtu.toolbox.hello.HelloProfileStore.cached(context) }
                .getOrNull()
        }
        loading = false
    }

    val hasSchedule = courses.isNotEmpty()
    val hasDining = diningCounts.isNotEmpty()
    val myProfile = remember(nickname, courses, diningCounts, dietInput, profile, dims) {
        MatchProfile.build(
            nickname = nickname,
            courses = courses,
            diningHourCounts = diningCounts,
            dietTags = dietInput.split(Regex("""[,，、\s]+""")).filter { it.isNotBlank() }.toSet(),
            profile = profile,
            dims = dims,
        )
    }
    val myCode = remember(myProfile) { MatchProfile.encode(myProfile) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "课表匹配",
                largeTitle = "课表匹配",
                color = MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                LinearProgressIndicator(Modifier.width(120.dp))
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .overScrollVertical(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("要分享什么") {
                    Text(
                        "全程在你手机上算，不上传任何地方。关掉的项不会写进分享码，对方也就看不到。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = "怎么称呼你（可留空）",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    DimRow(
                        "空课时间",
                        if (!hasSchedule) "没读到课表，先去日程页看一次" else "一周 ${courses.size} 节课",
                        dims.schedule,
                        hasSchedule,
                    ) { dims = dims.copy(schedule = it) }
                    DimRow(
                        "共同课程",
                        if (!hasSchedule) "没读到课表，先去日程页看一次"
                        else "分享课程号和课名，不含成绩、教师、教室",
                        dims.sameCourses,
                        hasSchedule,
                    ) { dims = dims.copy(sameCourses = it) }
                    DimRow(
                        "年级 · 专业 · 校区",
                        if (profile == null) "没读到个人信息，先去首页看一次"
                        else "不含学号本身，只有年级、专业、书院、校区",
                        dims.identity,
                        profile != null,
                    ) { dims = dims.copy(identity = it) }
                    DimRow(
                        "常去食堂的时段",
                        if (!hasDining) "没读到消费记录，先去校园卡页看一次"
                        else "只分享小时，不含金额和商户",
                        dims.diningHours,
                        hasDining,
                    ) { dims = dims.copy(diningHours = it) }
                    DimRow("口味标签", "自己填，随便写", dims.dietTags, true) {
                        dims = dims.copy(dietTags = it)
                    }
                    if (dims.dietTags) {
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = dietInput,
                            onValueChange = { dietInput = it },
                            label = "如：辣, 面食, 咖啡, 不吃香菜",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
            item {
                SectionCard("我的分享码") {
                    Text(
                        myCode,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = "复制，发给朋友",
                        onClick = { clipboard.setText(AnnotatedString(myCode)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                SectionCard("对方的分享码") {
                    TextField(
                        value = theirCode,
                        onValueChange = { theirCode = it; decodeError = null },
                        label = "粘贴到这里",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    decodeError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = "算一下",
                        onClick = {
                            val compact = theirCode.filterNot { c -> c.isWhitespace() }
                            val theirs = MatchProfile.decode(compact)
                            when {
                                theirs == null -> {
                                    decodeError = "这段码读不出来。确认复制完整了，或者对方用的是旧版本。"
                                    result = null
                                }
                                // 自己跟自己算必然是满分，与其展示一个假的 100%，不如说穿。
                                compact == myCode -> {
                                    decodeError = "这是你自己的码，跟自己当然完全合拍。粘对方的那段。"
                                    result = null
                                }
                                else -> {
                                    decodeError = null
                                    theirName = theirs.nickname.ifBlank { "对方" }
                                    result = MatchProfile.compare(myProfile, theirs)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = theirCode.isNotBlank(),
                    )
                }
            }
            result?.let { r ->
                item {
                    ResultCard(
                        theirName = theirName,
                        result = r,
                        onCopy = {
                            clipboard.setText(AnnotatedString(MatchProfile.summaryText(theirName, r)))
                        },
                    )
                }
            }
        }
    }
}

// ── 结果 ─────────────────────────────────────────────────

@Composable
private fun ResultCard(
    theirName: String,
    result: MatchProfile.Result,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (result.facets.isEmpty()) {
                Text("和 $theirName 的匹配", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "没有双方都分享了的维度，算不出来。让对方也打开几项试试。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                return@Column
            }

            ScoreRing(result.overall, result.verdict, theirName)

            // 校区不同先摆最上面：共同空闲再高也约不上，这时百分比是误导。
            result.blocker?.let { b ->
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "⚠️  $b",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (result.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.notes.forEach { TagChip(it, accent = true) }
                }
            }

            result.overlapGrid?.let { grid ->
                Spacer(Modifier.height(16.dp))
                Text("什么时候能一起", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OverlapGrid(grid)
                Spacer(Modifier.height(8.dp))
                GridLegend()
                Spacer(Modifier.height(10.dp))
                if (result.freeBlocks.isEmpty()) {
                    Text(
                        "工作日没有连着两节的共同空档，只能靠课间和周末了。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    result.freeBlocks.take(3).forEach { FreeBlockRow(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("分项", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            result.facets.forEachIndexed { index, f -> FacetBar(f, index) }

            if (result.sharedCourses.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("你俩都在上", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.sharedCourses.forEach { TagChip(it) }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                // 说清这个数是怎么来的。只对双方都开的维度算，
                // 否则"对方注重隐私"会被读成"你俩不合"。
                "只统计你俩都分享了的 ${result.facets.size} 项，按重要程度加权；空闲比例只看周一到周五。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(text = "复制战报", onClick = onCopy, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 大圆环。数字从 0 扫到分数，是这个页面唯一一处"发生了什么"的动效。 */
@Composable
private fun ScoreRing(score: Int, verdict: String, theirName: String) {
    val sweep by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
        label = "matchSweep",
    )
    val shown by animateFloatAsState(
        targetValue = score.toFloat(),
        animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
        label = "matchNumber",
    )
    val track = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val ink = scoreColor(score)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(148.dp), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = ink,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${shown.toInt()}",
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                )
                Text(
                    "分",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(verdict, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
        Text(
            "和 $theirName",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 7 列 × 11 行的叠加网格。
 *
 * 只画三档——都空 / 一个人空 / 都有课。分到"谁空"那一层的信息量抵不上多两种颜色
 * 带来的辨认成本，真要知道是谁，下面的时段列表写得更清楚。
 */
@Composable
private fun OverlapGrid(grid: String) {
    val both = MiuixTheme.colorScheme.primary
    val one = MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
    val none = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Spacer(Modifier.width(18.dp))
            MatchProfile.DAY_NAMES.forEach { day ->
                Text(
                    day.removePrefix("周"),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        for (section in 1..MatchProfile.SECTIONS) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$section",
                    modifier = Modifier.width(18.dp),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                for (day in 0 until MatchProfile.DAYS) {
                    val cell = grid[day * MatchProfile.SECTIONS + (section - 1)]
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1.6f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when (cell) {
                                    MatchProfile.Cell.BOTH_FREE -> both
                                    MatchProfile.Cell.ONE_FREE -> one
                                    else -> none
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun GridLegend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(MiuixTheme.colorScheme.primary, "都空")
        LegendDot(MiuixTheme.colorScheme.primary.copy(alpha = 0.18f), "一个人空")
        LegendDot(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), "都有课")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun FreeBlockRow(block: MatchProfile.FreeBlock) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                MatchProfile.DAY_NAMES[block.day],
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "第 ${block.from}-${block.to} 节 · 连着 ${block.length} 节都空",
                style = MiuixTheme.textStyles.footnote1,
            )
            Text(
                XjtuTime.getTimeRangeStr(block.from, block.to),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 分项条。逐条错开进场，让"一项项算出来"这件事看得见。 */
@Composable
private fun FacetBar(facet: MatchProfile.Facet, index: Int) {
    var appeared by remember(facet.label) { mutableStateOf(false) }
    LaunchedEffect(facet.label, facet.score) { appeared = true }
    val fraction by animateFloatAsState(
        targetValue = if (appeared) facet.score / 100f else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = 120 * index, easing = LinearOutSlowInEasing),
        label = "facet_${facet.label}",
    )
    val ink = scoreColor(facet.score)

    AnimatedVisibility(visible = appeared, enter = fadeIn() + expandVertically()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${facet.emoji}  ${facet.label}", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.weight(1f))
                Text(
                    "${facet.score}%",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ink)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                facet.detail,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun TagChip(label: String, accent: Boolean = false) {
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (accent) MiuixTheme.colorScheme.tertiaryContainer
                else MiuixTheme.colorScheme.secondaryContainer
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (accent) MiuixTheme.colorScheme.onTertiaryContainer
            else MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 低分不该跟高分一个颜色，但也不该用 error 红——那是"出错了"，不是"约不上"。 */
@Composable
private fun scoreColor(score: Int): Color = when {
    score >= 70 -> MiuixTheme.colorScheme.primary
    score >= 40 -> MiuixTheme.colorScheme.primaryVariant
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DimRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body2)
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        // 没数据的维度直接禁用而不是隐藏：让人知道这一项存在、以及为什么现在用不了。
        Switch(checked = checked && enabled, enabled = enabled, onCheckedChange = onChange)
    }
}
