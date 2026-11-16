package com.example.txtreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Draws one pre-built page layout plus a small status line; reports tap zones (-1 / 0 / 1). */
class PageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val density = resources.displayMetrics.density
    val padH = (22 * density).toInt()
    val padTop = (24 * density).toInt()
    val padBottom = (30 * density).toInt()

    var bgColor: Int = Color.WHITE
    var textColor: Int = Color.BLACK
    var statusLeft: String = ""
    var statusRight: String = ""
    var pageLayout: StaticLayout? = null

    /** -1 = previous page, 0 = menu, 1 = next page. */
    var onZone: ((Int) -> Unit)? = null
    var onSize: ((Int, Int) -> Unit)? = null

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * density }

    val textWidth: Int get() = maxOf(1, width - 2 * padH)
    val textHeight: Int get() = maxOf(1, height - padTop - padBottom)

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val x = e.x / maxOf(1, width)
            onZone?.invoke(if (x < 0.33f) -1 else if (x > 0.67f) 1 else 0)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > 80 * density && abs(dx) > abs(dy) * 1.5f) {
                onZone?.invoke(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onSize?.invoke(textWidth, textHeight)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        pageLayout?.let { l ->
            canvas.save()
            canvas.translate(padH.toFloat(), padTop.toFloat())
            canvas.clipRect(0, 0, textWidth, textHeight)
            l.draw(canvas)
            canvas.restore()
        }
        statusPaint.color = (textColor and 0x00FFFFFF) or (0x99000000.toInt())
        val y = height - padBottom / 2f + statusPaint.textSize / 3f
        val rw = statusPaint.measureText(statusRight)
        val avail = width - 2 * padH - rw - 16 * density
        if (avail > 0) {
            val left = TextUtils.ellipsize(statusLeft, statusPaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(left, padH.toFloat(), y, statusPaint)
        }
        canvas.drawText(statusRight, width - padH - rw, y, statusPaint)
    }
}
