package com.takano.wifiscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * HUD：顶部一条状态带 + 底部一条消息带，中间留给实时相机画面（预览在下面一层）。
 * 中间画一个取景框，方便对准二维码。
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var title: String = ""
    var status: String = ""
    var message: String = ""
    var messageHighlight: Boolean = false
    var hint: String = ""
    /** true = 中间露出下面的相机预览；false = 整屏黑底（无预览时） */
    var previewMode: Boolean = true

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isFakeBoldText = false
    }
    private val bar = Paint().apply { color = Color.WHITE }
    private val scrim = Paint().apply { color = Color.BLACK }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8F6FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 18f

        // 顶部状态带
        val statusLines = status.split("\n")
        val topH = 46f + 8f + statusLines.size * 38f + 14f
        if (!previewMode) {
            canvas.drawColor(Color.BLACK)
        } else {
            scrim.alpha = 175
            canvas.drawRect(0f, 0f, w, topH, scrim)
        }

        var y = 46f
        p.textSize = 30f; p.color = Color.WHITE; p.isFakeBoldText = true
        canvas.drawText(clip(title, p, w - pad * 2), pad, y, p)
        y += 8f
        bar.alpha = 90
        canvas.drawRect(pad, y, w - pad, y + 1.5f, bar)
        y += 44f
        p.isFakeBoldText = false; p.textSize = 27f
        for (line in statusLines) {
            canvas.drawText(clip(line, p, w - pad * 2), pad, y, p)
            y += 38f
        }

        // 底部消息带
        val msgLines = message.split("\n")
        val bottomH = msgLines.size * 38f + (if (hint.isNotEmpty()) 34f else 0f) + 12f
        val bottomTop = h - bottomH
        if (previewMode) {
            scrim.alpha = 175
            canvas.drawRect(0f, bottomTop, w, h, scrim)
        }
        var my = bottomTop + 30f
        p.textSize = 27f
        p.color = if (messageHighlight) Color.WHITE else Color.parseColor("#D5DDE1")
        p.isFakeBoldText = messageHighlight
        for (line in msgLines) {
            canvas.drawText(clip(line, p, w - pad * 2), pad, my, p)
            my += 38f
        }
        if (hint.isNotEmpty()) {
            p.color = Color.parseColor("#8A9499")
            p.textSize = 22f
            p.isFakeBoldText = false
            canvas.drawText(clip(hint, p, w - pad * 2), pad, h - 14f, p)
        }

        // 取景框（中间那块）
        if (previewMode) {
            val side = minOf(w, h - topH - bottomH) * 0.62f
            val cx = w / 2f
            val cy = topH + (h - topH - bottomH) / 2f
            val r = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            val corner = 22f
            // 四角
            val arm = side * 0.22f
            frame.strokeWidth = 4f
            canvas.drawLine(r.left, r.top + corner, r.left, r.top, frame)
            canvas.drawLine(r.left, r.top, r.left + corner, r.top, frame)
            canvas.drawLine(r.right - corner, r.top, r.right, r.top, frame)
            canvas.drawLine(r.right, r.top, r.right, r.top + corner, frame)
            canvas.drawLine(r.left, r.bottom - corner, r.left, r.bottom, frame)
            canvas.drawLine(r.left, r.bottom, r.left + corner, r.bottom, frame)
            canvas.drawLine(r.right - corner, r.bottom, r.right, r.bottom, frame)
            canvas.drawLine(r.right, r.bottom, r.right, r.bottom - corner, frame)
            // 四边中点小刻度
            frame.strokeWidth = 2.5f
            bar.color = Color.parseColor("#7FE0FF"); bar.alpha = 160
            canvas.drawRect(cx - arm / 2, r.top, cx + arm / 2, r.top + 2f, bar)
            canvas.drawRect(cx - arm / 2, r.bottom - 2f, cx + arm / 2, r.bottom, bar)
            canvas.drawRect(r.left, cy - arm / 2, r.left + 2f, cy + arm / 2, bar)
            canvas.drawRect(r.right - 2f, cy - arm / 2, r.right, cy + arm / 2, bar)
            bar.color = Color.WHITE
        }
    }

    private fun clip(s: String, paint: Paint, max: Float): String {
        if (paint.measureText(s) <= max) return s
        var n = s.length
        while (n > 1 && paint.measureText(s.substring(0, n) + "…") > max) n--
        return s.substring(0, n) + "…"
    }
}
