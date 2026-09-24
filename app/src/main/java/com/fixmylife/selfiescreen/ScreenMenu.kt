package com.fixmylife.selfiescreen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.max

/**
 * A menu drawn onto the selfie screen and driven by its buttons:
 * FLIP / ZOOM+ = next, ZOOM- = previous, SHUTTER = select.
 * The phone renders it into each frame; the screen itself stays a dumb display.
 */
class ScreenMenu(private val items: List<Item>) {

    /** [label] is re-read every frame so it can show live state; [select] returns true to keep the menu open. */
    class Item(val label: () -> String, val select: () -> Boolean)

    companion object {
        const val IDLE_CLOSE_MS = 10_000L
    }

    @Volatile var isOpen = false
        private set
    @Volatile private var index = 0
    @Volatile private var lastInput = 0L

    fun open() {
        index = 0
        touch()
        isOpen = true
    }

    fun close() {
        isOpen = false
    }

    fun move(dir: Int) {
        index = Math.floorMod(index + dir, items.size)
        touch()
    }

    fun select() {
        touch()
        if (!items[index].select()) close()
    }

    /** Closes the menu if nobody has pressed a button for a while. */
    fun expireIfIdle() {
        if (isOpen && SystemClock.elapsedRealtime() - lastInput > IDLE_CLOSE_MS) close()
    }

    private fun touch() {
        lastInput = SystemClock.elapsedRealtime()
    }

    /** Draws the menu at [w]x[h] over a dimmed [background] (e.g. the live camera frame). */
    fun render(w: Int, h: Int, background: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(16, 16, 28))
        if (background != null) {
            val s = max(w / background.width.toFloat(), h / background.height.toFloat())
            val m = Matrix().apply {
                setScale(s, s)
                postTranslate((w - background.width * s) / 2f, (h - background.height * s) / 2f)
            }
            c.drawBitmap(background, m, Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawColor(Color.argb(170, 0, 0, 0))
        }

        val unit = h / 12f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.62f
            isFakeBoldText = true
        }
        val hint = Paint(text).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = unit * 0.45f
            isFakeBoldText = false
            textAlign = Paint.Align.CENTER
        }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 196, 0) }

        c.drawText("MENU", w / 2f - text.measureText("MENU") / 2f, unit * 0.9f, text)

        // Scroll so the highlighted row stays visible
        val rowH = unit * 1.3f
        val top = unit * 1.4f
        val visible = ((h - top - unit * 1.2f) / rowH).toInt().coerceAtLeast(1)
        val first = (index - visible + 1).coerceAtLeast(0)
        for (i in first until minOf(items.size, first + visible)) {
            val y = top + (i - first) * rowH
            val selected = i == index
            if (selected) c.drawRoundRect(RectF(4f, y, w - 4f, y + rowH - 3f), 8f, 8f, bar)
            text.color = if (selected) Color.BLACK else Color.WHITE
            c.drawText(fit(items[i].label(), text, w - 16f), 10f, y + rowH * 0.68f, text)
        }

        c.drawText("FLIP next · SHUTTER select", w / 2f, h - unit * 0.4f, hint)
        return out
    }

    private fun fit(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
