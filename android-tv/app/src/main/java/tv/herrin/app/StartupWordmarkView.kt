package tv.herrin.app

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** Starts the same V13 reveal before inflating the Compose hierarchy. */
internal class StartupWordmarkView(context: Context, private val afterFirstFrame: () -> Unit) : View(context) {
    private val renderer = WordmarkRenderer()
    private val clock = WordmarkFrameClock(16f)
    private var drewFirst = false
    private var handedOff = false
    val elapsedMs get() = clock.elapsedMs
    var uncovered = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        StartupTiming.firstDraw()
        if (!handedOff) clock.frame(System.nanoTime(), uncovered)
        val widthPx = minOf(width.toFloat(), 420f * resources.displayMetrics.density)
        val heightPx = minOf(height.toFloat(), widthPx * 0.25f)
        val saved = canvas.save()
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.translate((width - widthPx) / 2f, (height - heightPx) / 2f)
        renderer.draw(canvas, widthPx, heightPx, elapsedMs)
        canvas.restoreToCount(saved)
        if (!drewFirst) {
            drewFirst = true
            postOnAnimation { afterFirstFrame() }
        }
        if (!handedOff) postInvalidateOnAnimation()
    }

    fun handOff() { handedOff = true }

    override fun onDetachedFromWindow() {
        renderer.close()
        super.onDetachedFromWindow()
    }
}
