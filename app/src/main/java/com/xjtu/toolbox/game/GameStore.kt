package com.xjtu.toolbox.game

import android.content.Context
import android.content.SharedPreferences

/**
 * 小游戏的本地记录：最高分与胜负战绩。
 *
 * 全部存在一份 `SharedPreferences("games")` 里，本身**不上传、不进任何网络请求**。
 *
 * 唯一的例外是分数类游戏的**全服榜**：够上榜的成绩会由 [com.xjtu.toolbox.game.board.Leaderboard]
 * 另发一份到自建服务端，上传的只有游戏、分数、名字和一个设备级匿名 ID。这份记录仍是本地的
 * 权威——榜拉不到、网断了都不影响这里的数字，对弈类战绩则始终不出设备。
 *
 * key 由各游戏自己定，约定见下面的常量：分数类用 `best_<game>`，
 * 对弈类按难度分别计 `<game>_win_<difficulty>` / `<game>_loss_<difficulty>`，
 * 免得「简单档赢一百盘」和「地狱档赢一盘」混成同一个数。
 */
object GameStore {

    private const val PREFS = "games"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 分数类（2048、合成西交大…）──

    fun bestScore(context: Context, game: String): Int =
        prefs(context).getInt("best_$game", 0)

    /** 只增不减：传进来的分数低于已有记录时什么都不做，调用方不必自己比。 */
    fun submitScore(context: Context, game: String, score: Int) {
        val p = prefs(context)
        if (score > p.getInt("best_$game", 0)) {
            p.edit().putInt("best_$game", score).apply()
        }
    }

    // ── 对弈类（五子棋、围棋、象棋）──
    //
    // difficulty 对人机模式是难度档（easy / hard / hell），
    // 同屏双人传 "local"——它也是一种「对手」，战绩分开记才有意义。

    fun wins(context: Context, game: String, difficulty: String): Int =
        prefs(context).getInt("${game}_win_$difficulty", 0)

    fun losses(context: Context, game: String, difficulty: String): Int =
        prefs(context).getInt("${game}_loss_$difficulty", 0)

    fun draws(context: Context, game: String, difficulty: String): Int =
        prefs(context).getInt("${game}_draw_$difficulty", 0)

    fun recordResult(context: Context, game: String, difficulty: String, result: GameResult) {
        val suffix = when (result) {
            GameResult.WIN -> "win"
            GameResult.LOSS -> "loss"
            GameResult.DRAW -> "draw"
        }
        val key = "${game}_${suffix}_$difficulty"
        val p = prefs(context)
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }
}

enum class GameResult { WIN, LOSS, DRAW }

/**
 * 游戏标识。合集页、记录 key、路由后缀共用同一份，避免三处各写一个字符串然后对不上。
 */
object GameIds {
    const val MERGE = "merge"       // 合成西交大
    const val G2048 = "2048"        // GPA 2048
    const val BLOCKS = "blocks"     // 方块，记录再按玩法分
    const val HOP = "hop"           // 跳一跳
    const val GOMOKU = "gomoku"     // 五子棋
    const val GO = "go"             // 围棋
    const val XIANGQI = "xiangqi"   // 象棋
}
