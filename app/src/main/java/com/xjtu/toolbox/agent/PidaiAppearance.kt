package com.xjtu.toolbox.agent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.agent.bot.BOT_COLORS
import com.xjtu.toolbox.agent.bot.BOT_SHAPES
import com.xjtu.toolbox.agent.bot.DEFAULT_COLOR_ID
import com.xjtu.toolbox.agent.bot.DEFAULT_SHAPE_ID
import com.xjtu.toolbox.agent.bot.botColorById
import com.xjtu.toolbox.agent.bot.botShapeById
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 屁岱形象（形状 + 颜色）的全局入口。
 *
 * 用 [mutableStateOf] 是为了让底栏能直接跟着设置页的选择重组：底栏常驻，设置页改一下
 * 就要立刻在底栏看到 morph，而不是等下次冷启动。
 *
 * 与账号数据分开存（不挂 [com.xjtu.toolbox.account.AccountContext]）：这是**设备级**的
 * 外观偏好，不是某个学号的业务数据。跟着账号走会让切账号时形象莫名重置。
 */
object PidaiAppearanceHost {
    var shapeId by mutableStateOf(DEFAULT_SHAPE_ID)
    var colorId by mutableStateOf(DEFAULT_COLOR_ID)

    /** 从磁盘读一次并写入 host。幂等。 */
    fun load(context: Context) {
        val (s, c) = PidaiAppearanceStore(context).load()
        shapeId = s
        colorId = c
    }

    /** 选择之后立即落盘 + 更新 host。 */
    fun set(context: Context, shape: String? = null, color: String? = null) {
        if (shape != null) shapeId = shape
        if (color != null) colorId = color
        PidaiAppearanceStore(context).save(shapeId, colorId)
    }
}

/** 形象偏好的落盘。设备级，与账号无关。 */
class PidaiAppearanceStore(context: Context) {
    private val appContext = context.applicationContext

    private val prefs
        get() = appContext.getSharedPreferences("pidai_appearance", Context.MODE_PRIVATE)

    /** 读出形象，非法/失踪的值一律回落到默认，免得旧版残留把底栏搞成空形状。 */
    fun load(): Pair<String, String> {
        val shape = prefs.getString("shape", DEFAULT_SHAPE_ID)
            ?.takeIf { id -> BOT_SHAPES.any { it.id == id } }
            ?: DEFAULT_SHAPE_ID
        val color = prefs.getString("color", DEFAULT_COLOR_ID)
            ?.takeIf { id -> BOT_COLORS.any { it.id == id } }
            ?: DEFAULT_COLOR_ID
        return shape to color
    }

    fun save(shape: String, color: String) {
        prefs.edit().putString("shape", shape).putString("color", color).apply()
    }
}

/**
 * 底栏渲染需要的形象参数：形状轮廓 + 身体墨色。
 *
 * 墨色在「跟随主题」时取 `onSurface`（底栏前景），深色底栏上仍然可见；用户选了具体
 * 颜色才用那个色值。这个取舍是必须的——写死一个墨色在深色模式会直接看不见。
 */
@Composable
fun pidaiNavAppearance(): Pair<DoubleArray?, Color> {
    val shape = botShapeById(PidaiAppearanceHost.shapeId)?.radii
    val ink = botColorById(PidaiAppearanceHost.colorId)?.argb
        ?.let { Color(it) }
        ?: MiuixTheme.colorScheme.onSurface
    return shape to ink
}
