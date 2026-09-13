package com.xjtu.toolbox.agent

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.xjtu.toolbox.agent.bot.BotEngine
import com.xjtu.toolbox.agent.bot.BotFrame
import com.xjtu.toolbox.agent.bot.NOTIF_BLUE

/**
 * bloub 机器人的 Compose 渲染器：把 [BotEngine] 采出的帧画到一块小画布上。
 *
 * 眼睛是身体上的「洞」（露出底栏背景色），不是白色形状贴在上面——所以先画一层
 * 与身体同形的背景色垫底，再在身体裁剪内画墨色、以背景色回填眼洞。burst 的粒子
 * 在身体后面（先画，被垫底遮住），通知点带凹槽（身体先让出一圈背景色再叠蓝点），
 * 彗星彩带按 z 分量分前后两半（后半段先画、被身体遮住，才是「轨道」而不是平面画）。
 *
 * 性能：底栏常驻，待命态只有眨眼和视线漂移在动（渲染器节流到 ~30fps，眨眼
 * 0.18s ≈ 5 帧足够平滑），主动全速播动画的只有微动、提醒、被点击三种情况。
 */
@Composable
internal fun BloubBotIcon(
    beat: PidaiBeat,
    ink: Color,
    paper: Color,
    modifier: Modifier = Modifier,
) {
    val engine = remember { BotEngine() }
    val clock = remember { BotClock() }
    val currentBeat by rememberUpdatedState(beat)

    // 状态切换用与帧循环相同的时钟，保证 setState 的时刻与采样时刻同源。
    LaunchedEffect(beat) {
        val state = when (beat) {
            PidaiBeat.REST -> "idle"
            PidaiBeat.IDLE -> "wink"
            PidaiBeat.THINKING -> "orbit"
            PidaiBeat.ALERT -> "notify"
            PidaiBeat.TAP -> "comet"
        }
        val now = clock.now()
        clock.stateChangedAt = now
        engine.setState(state, now)
    }

    var frame by remember { mutableStateOf<BotFrame?>(null) }
    LaunchedEffect(Unit) {
        var lastSampleAt = 0.0
        while (true) {
            withFrameNanos { nanos ->
                val now = clock.at(nanos)
                val sinceChange = now - clock.stateChangedAt
                // 待命节流：入场形变结束后只剩眨眼/漂移，~30fps 足够；其余状态全速
                val minInterval =
                    if (currentBeat == PidaiBeat.REST && sinceChange > 0.6) 0.033 else 0.0
                if (now - lastSampleAt >= minInterval) {
                    lastSampleAt = now
                    frame = engine.sample(now)
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        val f = frame
        if (f == null) {
            // 首帧采样前的占位（最多一帧）：画一个素球，避免中间塌洞
            drawCircle(ink, radius = size.minDimension * 0.4f, center = center)
            return@Canvas
        }
        // 引擎单位（球半径 = 100）-> 像素。留 1/1.22 的余量给通知点（半径 1.003R + 凹槽）
        // 和彗星彩带（半径 0.94R），静息球占画布约 82%。
        val unitPx = size.minDimension / 2f / 1.22f / 100f
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            scale(unitPx, unitPx, pivot = Offset.Zero)
        }) {
            drawFrame(f, ink, paper)
        }
    }
}

private fun DrawScope.drawFrame(f: BotFrame, ink: Color, paper: Color) {
    fun drawDots() {
        for (d in f.dots) {
            val color = if (d.depth < 0.0) ink else lerp(paper, ink, d.depth.toFloat())
            drawCircle(
                color = color,
                radius = d.r.toFloat(),
                center = Offset(d.x.toFloat(), d.y.toFloat()),
                alpha = d.opacity.toFloat(),
            )
        }
    }

    fun drawArcs() {
        for (a in f.arcs) {
            val brush = Brush.linearGradient(
                0f to a.colors[0],
                0.5f to a.colors[1],
                1f to a.colors[2],
                start = Offset(a.gradX1.toFloat(), a.gradY1.toFloat()),
                end = Offset(a.gradX2.toFloat(), a.gradY2.toFloat()),
            )
            val style = Stroke(width = a.width.toFloat(), cap = StrokeCap.Round)
            drawPath(a.back, brush, style = style, alpha = a.opacity.toFloat())
            drawPath(a.front, brush, style = style, alpha = a.opacity.toFloat())
        }
    }

    if (f.dotsBehind) drawDots()

    // 彩带后半段：画在身体之前，被身体遮住
    drawArcs()

    // 与身体同形的背景色垫底：眼洞和凹槽露出的就是它，同时遮住身后的粒子和彩带
    drawPath(f.bodyPath, paper, alpha = f.bodyAlpha.toFloat())

    clipPath(f.bodyPath) {
        // 墨色身体：裁剪到轮廓，画满整个裁剪区即可
        drawRect(
            color = ink,
            topLeft = Offset(-250f, -250f),
            size = Size(500f, 500f),
            alpha = f.bodyAlpha.toFloat(),
        )
        // 眼洞与通知点凹槽：以背景色回填
        for (eye in f.eyes) {
            drawPath(eye.path, paper, alpha = eye.alpha.toFloat())
        }
        f.notif?.let { n ->
            drawCircle(paper, radius = n.notchR.toFloat(), center = Offset(n.x.toFloat(), n.y.toFloat()))
        }
    }

    if (!f.dotsBehind) drawDots()

    f.notif?.let { n ->
        drawCircle(
            color = Color(NOTIF_BLUE),
            radius = n.r.toFloat(),
            center = Offset(n.x.toFloat(), n.y.toFloat()),
        )
    }

    // 彩带前半段：最后画，压在身体上
    drawArcs()
}

/** 引擎时钟：以组合时刻为零点，帧回调和状态切换共用同一时间原点。 */
private class BotClock {
    val t0: Long = System.nanoTime()

    /** 最近一次状态切换的时刻，供静止态的保活节流判断入场形变是否已结束。 */
    var stateChangedAt: Double = 0.0

    fun now(): Double = at(System.nanoTime())

    fun at(frameNanos: Long): Double = (frameNanos - t0) / 1_000_000_000.0
}
