package tv.herrin.app

/** Reusable feathered reveal shaders and fixed vector paths; no full-screen bitmap/layer per frame. */
internal class WordmarkRenderer : AutoCloseable {
    override fun close() { paint.shader = null; path.reset(); lm.reset(); rm.reset() }
    private val path=requireNotNull(androidx.core.graphics.PathParser.createPathFromPathData(PATH))
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE}
    private val left=android.graphics.LinearGradient(0f,0f,80f,0f,android.graphics.Color.TRANSPARENT,android.graphics.Color.WHITE,android.graphics.Shader.TileMode.CLAMP)
    private val right=android.graphics.LinearGradient(0f,0f,80f,0f,android.graphics.Color.WHITE,android.graphics.Color.TRANSPARENT,android.graphics.Shader.TileMode.CLAMP)
    private val lm=android.graphics.Matrix();private val rm=android.graphics.Matrix()

    // Native Canvas draws the V13 vector directly each vsync. Three lightweight
    // translucent outlines produce letter-local bloom without bitmap allocation.
    private fun drawLitPath(
        canvas: android.graphics.Canvas,
        shader: android.graphics.Shader?,
        brightness: Float,
        glow: Float
    ) {
        paint.shader = shader
        if (glow > 0.01f) {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.strokeWidth = 28f
            paint.alpha = (glow * 9f).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
            paint.strokeWidth = 16f
            paint.alpha = (glow * 22f).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
            paint.strokeWidth = 8f
            paint.alpha = (glow * 39f).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        }
        paint.style = android.graphics.Paint.Style.FILL
        paint.alpha = (brightness * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(path, paint)
    }

    fun draw(canvas:android.graphics.Canvas,width:Float,height:Float,time:Float){
        if(time<=0f)return // No dim prepaint anywhere in the wordmark.
        val scale=minOf(width/960f,height/180f)
        val saved=canvas.save()
        canvas.translate((width-960*scale)/2,(height-180*scale)/2);canvas.scale(scale,scale)
        val progress = WordmarkMotion.reveal(time)
        val brightness = WordmarkMotion.brightness(time)
        val glow = WordmarkMotion.glow(time)
        if (progress >= 1f) {
            drawLitPath(canvas, null, brightness, glow)
        } else {
            // Fixed HERRIN glyphs feather from the center to both edges.
            // Both the glyph and its glow use the same reveal mask.
            val eased = progress * progress * (3f - 2f * progress)
            val radius = 560f * eased
            lm.setTranslate(480f - radius, 0f)
            left.setLocalMatrix(lm)
            var clip = canvas.save()
            canvas.clipRect(0f, 0f, 480f, 180f)
            drawLitPath(canvas, left, brightness, glow)
            canvas.restoreToCount(clip)
            rm.setTranslate(480f + radius - 80f, 0f)
            right.setLocalMatrix(rm)
            clip = canvas.save()
            canvas.clipRect(480f, 0f, 960f, 180f)
            drawLitPath(canvas, right, brightness, glow)
            canvas.restoreToCount(clip)
        }
        canvas.restoreToCount(saved)
    }
    private companion object { const val PATH="M162.8 12.8V164H138.2V96.7H62.3V164H37.7V12.8H62.3V79H138.2V12.8Z M323.2 144.2 323.1 164H228.8V12.8H323.1V32.6H253.5V78.2H309.2V97.4H253.5V144.2Z M423.7 85.2Q432.1 85.2 438.4 83.2Q444.7 81.2 448.9 77.5Q453.1 73.8 455.2 68.7Q457.3 63.6 457.3 57.4Q457.3 45 449.1 38.5Q440.9 32 424.3 32H404.4V85.2ZM494.9 164H472.9Q466.4 164 463.5 159L428.2 108Q426.4 105.4 424.3 104.3Q422.2 103.1 418 103.1H404.4V164H379.8V12.8H424.3Q439.2 12.8 450 15.8Q460.7 18.9 467.7 24.5Q474.6 30.1 477.9 38Q481.2 45.9 481.2 55.5Q481.2 63.4 478.9 70.2Q476.5 77.1 472 82.6Q467.6 88.2 461.1 92.3Q454.6 96.4 446.3 98.7Q450.8 101.4 454 106.4Z M587 85.2Q595.4 85.2 601.7 83.2Q608 81.2 612.2 77.5Q616.4 73.8 618.5 68.7Q620.6 63.6 620.6 57.4Q620.6 45 612.4 38.5Q604.3 32 587.7 32H567.7V85.2ZM658.2 164H636.3Q629.8 164 626.8 159L591.5 108Q589.8 105.4 587.7 104.3Q585.6 103.1 581.4 103.1H567.7V164H543.1V12.8H587.7Q602.6 12.8 613.3 15.8Q624.1 18.9 631 24.5Q638 30.1 641.3 38Q644.6 45.9 644.6 55.5Q644.6 63.4 642.2 70.2Q639.8 77.1 635.4 82.6Q630.9 88.2 624.4 92.3Q617.9 96.4 609.6 98.7Q614.1 101.4 617.4 106.4Z M731.2 164H706.5V12.8H731.2Z M922.2 12.8V164H909.7Q906.9 164 904.9 163Q902.9 162 901.1 159.7L818.1 53Q818.4 56.1 818.6 59.1Q818.7 62 818.7 64.6V164H797.1V12.8H809.9Q811.5 12.8 812.6 13Q813.7 13.1 814.6 13.5Q815.6 14 816.4 14.8Q817.2 15.6 818.2 16.9L901.3 123.7Q901 120.4 900.9 117.3Q900.7 114.1 900.7 111.4V12.8Z" }
}
