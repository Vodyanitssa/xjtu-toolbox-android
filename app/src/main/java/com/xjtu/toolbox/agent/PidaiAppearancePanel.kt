package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.bot.BOT_COLORS
import com.xjtu.toolbox.agent.bot.BOT_SHAPES
import com.xjtu.toolbox.agent.bot.botColorById
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

/**
 * 屁岱形象选择：形状与颜色。移植自 bloub 的定制器，UI 语言换成本项目的 miuix 卡片。
 *
 * 缩略图**冻结**在同一时刻（[PREVIEW_AT]），不是会动的：一排各自跑帧循环的缩略图
 * 既费电又让人眼花，而且静止帧就足以判断形状是否好看。底栏那张才是活的。
 *
 * 改动即时生效且设备级持久化（[PidaiAppearanceHost]）：不进 [AgentConfig]，因为形象
 * 是外观偏好而不是某个账号的业务数据；也不做「保存」按钮，选完就该看见底栏变了。
 */
@Composable
fun PidaiAppearancePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shapeId = PidaiAppearanceHost.shapeId
    val colorId = PidaiAppearanceHost.colorId
    // 选中的墨色：跟随主题时用底栏前景色，深浅色都有对比度
    val ink = pidaiInk(colorId)

    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("屁岱形象", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)

            Text("形状", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
            // 4 列：8 种形状刚好两行。缩略图直接画引擎采出的一帧，不是抽象色块——
            // 选之前就能看见眼睛有没有被轮廓啃掉。
            BOT_SHAPES.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { def ->
                        ShapeTile(
                            label = def.label,
                            selected = def.id == shapeId,
                            ink = ink,
                            shape = def.radii,
                            onClick = { PidaiAppearanceHost.set(context, shape = def.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 补齐空位，保持每格等宽
                    repeat(4 - row.size) {
                        Box(Modifier.weight(1f))
                    }
                }
            }

            Text(
                "颜色",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            BOT_COLORS.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { def ->
                        ColorDot(
                            label = def.label,
                            selected = def.id == colorId,
                            // 跟随主题没有固定色值，用底栏前景色示意
                            swatch = def.argb?.let { Color(it) } ?: MiuixTheme.colorScheme.onSurface,
                            auto = def.argb == null,
                            onClick = { PidaiAppearanceHost.set(context, color = def.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(6 - row.size) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 缩略图定格的时刻：与 bloub 的 `POSES.idle` 一致，是最有代表性的静息脸。 */
private const val PREVIEW_AT = 1.0

private val TILE_RADIUS = 14.dp

@Composable
private fun ShapeTile(
    label: String,
    selected: Boolean,
    ink: Color,
    shape: DoubleArray,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paper = MiuixTheme.colorScheme.surfaceVariant
    val borderColor = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline
    Column(
        modifier = modifier
            .squircleSurface(color = paper, cornerRadius = TILE_RADIUS)
            .squircleBorder(
                width = { if (selected) 2.dp else 1.dp },
                color = { borderColor },
                cornerRadius = TILE_RADIUS,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
            ) { onClick() }
            .semantics {
                contentDescription = "形状：$label"
                this.selected = selected
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 画布比触摸区大一点，给非圆形状的尖角留空间
        BloubBotIcon(
            beat = PidaiBeat.REST,
            ink = ink,
            paper = paper,
            shape = shape,
            frozenAt = PREVIEW_AT,
            modifier = Modifier.size(46.dp),
        )
        Text(
            label,
            style = MiuixTheme.textStyles.footnote2,
            maxLines = 1,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ColorDot(
    label: String,
    selected: Boolean,
    swatch: Color,
    auto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
            ) { onClick() }
            .semantics {
                contentDescription = "颜色：$label"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        val ringColor =
            if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline
        Box(
            Modifier
                .size(32.dp)
                .squircleBorder(
                    width = { if (selected) 2.dp else 1.dp },
                    color = { ringColor },
                    cornerRadius = 16.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (auto) {
                // 跟随主题：左半 = 前景色、右半 = 背景色，一眼看出「随明暗自动」
                Row(Modifier.size(22.dp).clip(CircleShape)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MiuixTheme.colorScheme.onSurface)
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MiuixTheme.colorScheme.surface)
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(swatch)
                )
            }
        }
    }
}

/** 形象墨色：跟随主题时取底栏前景色，否则取所选色。 */
@Composable
private fun pidaiInk(colorId: String): Color =
    botColorById(colorId)?.argb?.let { Color(it) } ?: MiuixTheme.colorScheme.onSurface
