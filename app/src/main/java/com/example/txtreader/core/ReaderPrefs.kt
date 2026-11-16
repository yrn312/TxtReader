package com.example.txtreader.core

import android.content.Context

class ReaderPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var fontSp: Int
        get() = sp.getInt("fontSp", 19)
        set(v) { sp.edit().putInt("fontSp", v.coerceIn(12, 40)).apply() }

    /** Line spacing multiplier percent, 100..250. */
    var lineSpacingPct: Int
        get() = sp.getInt("lineSpacingPct", 150)
        set(v) { sp.edit().putInt("lineSpacingPct", v.coerceIn(100, 250)).apply() }

    val lineSpacing: Float get() = lineSpacingPct / 100f

    var bgIndex: Int
        get() = sp.getInt("bgIndex", 0)
        set(v) { sp.edit().putInt("bgIndex", v).apply() }

    var night: Boolean
        get() = sp.getBoolean("night", false)
        set(v) { sp.edit().putBoolean("night", v).apply() }

    /** -1 = follow system, otherwise 0..100. */
    var brightness: Int
        get() = sp.getInt("brightness", -1)
        set(v) { sp.edit().putInt("brightness", v).apply() }

    var volumeKeys: Boolean
        get() = sp.getBoolean("volumeKeys", true)
        set(v) { sp.edit().putBoolean("volumeKeys", v).apply() }

    var volumeReverse: Boolean
        get() = sp.getBoolean("volumeReverse", false)
        set(v) { sp.edit().putBoolean("volumeReverse", v).apply() }

    /** Custom chapter rules, one regex per line. Empty = built-in rules. */
    var ruleText: String
        get() = sp.getString("ruleText", "") ?: ""
        set(v) { sp.edit().putString("ruleText", v).apply() }

    fun bgColor(): Int =
        if (night) NIGHT_BG else BG_COLORS[bgIndex.coerceIn(0, BG_COLORS.size - 1)]

    fun textColor(): Int = if (night) NIGHT_TEXT else DAY_TEXT

    companion object {
        val BG_COLORS = intArrayOf(
            0xFFF6EFDD.toInt(), // paper
            0xFFFFFFFF.toInt(), // white
            0xFFD3E8D3.toInt(), // green
            0xFFD6E4F0.toInt(), // blue
            0xFFE4E4E4.toInt()  // gray
        )
        val NIGHT_BG = 0xFF121212.toInt()
        val NIGHT_TEXT = 0xFF8F8F8F.toInt()
        val DAY_TEXT = 0xFF2B2B2B.toInt()
    }
}
