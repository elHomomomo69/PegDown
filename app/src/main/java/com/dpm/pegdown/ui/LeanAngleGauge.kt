package com.dpm.pegdown.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.dpm.pegdown.R
import android.content.Context
import android.annotation.SuppressLint

class LeanAngleGauge @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var currentAngle = 0.0
    private var maxTempLeft = 0.0
    private var maxTempRight = 0.0
    private var maxTourLeft = 0.0
    private var maxTourRight = 0.0

    private var isAxisInverted = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    private val bikeBitmap: Bitmap? = try {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_motorcycle_rear)
        drawable?.let {
            val targetWidth = 600
            val targetHeight = 320
            if (it is BitmapDrawable) {
                Bitmap.createScaledBitmap(it.bitmap, targetWidth, targetHeight, true)
            } else {
                val bitmap = createBitmap(targetWidth, targetHeight)
                val canvas = Canvas(bitmap)
                it.setBounds(0, 0, canvas.width, canvas.height)
                it.draw(canvas)
                bitmap
            }
        }
    } catch (_: Exception) {
        null
    }

    fun updateData(current: Double, tempL: Double, tempR: Double, tourL: Double, tourR: Double) {
        if (isAxisInverted) {
            currentAngle = -current
            maxTempLeft = -tempR
            maxTempRight = -tempL
            maxTourLeft = -tourR
            maxTourRight = -tourL
        } else {
            currentAngle = current
            maxTempLeft = tempL
            maxTempRight = tempR
            maxTourLeft = tourL
            maxTourRight = tourR
        }
        invalidate()
    }

    fun setInverted(inverted: Boolean) {
        isAxisInverted = inverted
        invalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val isLandscape = width > height

        val radius = if (isLandscape) {
            (height * 0.82f).coerceAtMost(width * 0.38f)
        } else {
            (width.coerceAtMost(height * 2.2f)) * 0.42f
        }

        // --- POSITIONIERUNG ---
        val centerX = width / 2f
        val centerY = if (isLandscape) height * 0.95f else height * 0.52f

        val maxScale = 60.0
        rectF.apply {
            left = centerX - radius
            top = centerY - radius
            right = centerX + radius
            bottom = centerY + radius
        }

        // --- 1. HINTERGRUND-BOGEN (Insgesamt 120 Grad, von 210° bis 330°) ---
        paint.style = Paint.Style.STROKE
        val arcStrokeWidth = if (isLandscape) 46f else 60f
        paint.strokeWidth = arcStrokeWidth
        paint.strokeCap = Paint.Cap.BUTT

        paint.color = "#151515".toColorInt()
        paint.alpha = 255
        canvas.drawArc(rectF, 210f, 120f, false, paint)

        // Farbverläufe exakt auf die Schräglagen-Zonen angepasst (-60° bis +60° auf 120° Bogen):
        // Mitte (0°) ist bei 270°.
        // Grün: -20° bis +20° -> Canvas 250° bis 290° (40° breit)
        // Gelb: 20° bis 35° (bzw. -35° bis -20°) -> je 15° breit
        // Rot: 35° bis 60° (bzw. -60° bis -35°) -> je 25° breit

        // Linke Seite (Rot: -60° bis -35°)
        paint.color = "#FF3D00".toColorInt()
        canvas.drawArc(rectF, 210f, 25f, false, paint)

        // Linke Seite (Gelb: -35° bis -20°)
        paint.color = "#FFEA00".toColorInt()
        canvas.drawArc(rectF, 235f, 15f, false, paint)

        // Mitte (Grün: -20° bis +20°)
        paint.color = "#00E676".toColorInt()
        canvas.drawArc(rectF, 250f, 40f, false, paint)

        // Rechte Seite (Gelb: +20° bis +35°)
        paint.color = "#FFEA00".toColorInt()
        canvas.drawArc(rectF, 290f, 15f, false, paint)

        // Rechte Seite (Rot: +35° bis +60°)
        paint.color = "#FF3D00".toColorInt()
        canvas.drawArc(rectF, 305f, 25f, false, paint)

        val halfStroke = arcStrokeWidth / 2f

        // Skalenstriche von -60 bis 60 Grad
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = "#DDDDDD".toColorInt()

        for (angle in ((-60..60) step 15)) {
            val degInCanvas = 270.0 + angle
            val rad = Math.toRadians(degInCanvas)
            val innerR = radius - halfStroke
            val outerR = radius + halfStroke
            canvas.drawLine(
                centerX + (innerR * cos(rad)).toFloat(),
                centerY + (innerR * sin(rad)).toFloat(),
                centerX + (outerR * cos(rad)).toFloat(),
                centerY + (outerR * sin(rad)).toFloat(),
                paint,
            )
        }

        // --- 2. MARKIERUNGEN ---
        fun drawTriangleMarker(angle: Double, colorInt: Int, isAbove: Boolean) {
            val clamped = angle.coerceIn(-maxScale, maxScale)
            val canvasDeg = 270.0 + clamped
            val rad = Math.toRadians(canvasDeg)

            val triSize = if (!isLandscape) 48f else 60f
            val baseRadius = if (isAbove) {
                radius + halfStroke + (if (!isLandscape) 32f else 42f)
            } else {
                radius - halfStroke - (if (!isLandscape) 32f else 42f)
            }

            val cx = centerX + (baseRadius * cos(rad)).toFloat()
            val cy = centerY + (baseRadius * sin(rad)).toFloat()

            val perpRad = rad + Math.PI / 2.0
            val cosPerp = cos(perpRad).toFloat()
            val sinPerp = sin(perpRad).toFloat()
            val cosRad = cos(rad).toFloat()
            val sinRad = sin(rad).toFloat()

            val p1x: Float
            val p1y: Float
            val p2x = cx + (triSize * 0.8f * cosPerp)
            val p2y = cy + (triSize * 0.8f * sinPerp)
            val p3x = cx - (triSize * 0.8f * cosPerp)
            val p3y = cy - (triSize * 0.8f * sinPerp)

            if (isAbove) {
                p1x = cx - (triSize * cosRad)
                p1y = cy - (triSize * sinRad)
            } else {
                p1x = cx + (triSize * cosRad)
                p1y = cy + (triSize * sinRad)
            }

            val markerPath = Path().apply {
                moveTo(p1x, p1y)
                lineTo(p2x, p2y)
                lineTo(p3x, p3y)
                close()
            }

            paint.style = Paint.Style.FILL
            paint.color = colorInt
            paint.alpha = 255
            canvas.drawPath(markerPath, paint)
        }

        drawTriangleMarker(maxTourLeft, "#00B0FF".toColorInt(), isAbove = false)
        drawTriangleMarker(maxTourRight, "#00B0FF".toColorInt(), isAbove = false)
        drawTriangleMarker(maxTempLeft, "#FFAB00".toColorInt(), isAbove = true)
        drawTriangleMarker(maxTempRight, "#FFAB00".toColorInt(), isAbove = true)

        // --- 3. FARBDEFINITION FÜR MOTORRAD & TEXT ---
        val absAngle = abs(currentAngle)
        val currentNeonColor = when {
            absAngle < 20.0 -> "#00E676".toColorInt()
            absAngle < 35.0 -> "#FFEA00".toColorInt()
            else -> "#FF1744".toColorInt()
        }

        // --- 4. MOTORRAD-BITMAP UNTER DER SKALA ---
        val clampedCurrent = currentAngle.coerceIn(-maxScale, maxScale)
        val currentCanvasDeg = 270.0 + clampedCurrent
        val rad = Math.toRadians(currentCanvasDeg)

        val indicatorRadius = radius - halfStroke - 60f
        val bikeX = centerX + (indicatorRadius * cos(rad)).toFloat()
        val bikeY = centerY + (indicatorRadius * sin(rad)).toFloat()

        val rotationAngle = clampedCurrent.toFloat()

        canvas.withTranslation(bikeX, bikeY) {
            rotate(rotationAngle)

            bikeBitmap?.let { bitmap ->
                val bWidth = bitmap.width.toFloat()
                val bHeight = bitmap.height.toFloat()

                // High-intensity color filter that boosts brightness and preserves details
                val color = currentNeonColor
                val r = Color.red(color) / 255f
                val g = Color.green(color) / 255f
                val b = Color.blue(color) / 255f
                
                // We increase the scale (1.6x) and add an offset (45) to make it "pop" and look like it's glowing
                val matrix = floatArrayOf(
                    r * 1.6f, 0f, 0f, 0f, 45f,
                    0f, g * 1.6f, 0f, 0f, 45f,
                    0f, 0f, b * 1.6f, 0f, 45f,
                    0f, 0f, 0f, 1f, 0f
                )
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                drawBitmap(bitmap, -bWidth / 2f, -bHeight / 2f, paint)
                paint.colorFilter = null
            }
        }

        // --- 5. ZENTRALES SCHWARZES FRAME + GRADANZEIGE ---
        val circleCenterX = centerX
        val circleCenterY = if (isLandscape) centerY - (radius * 0.45f) else centerY - (radius * 0.38f)
        val circleRadius = if (isLandscape) 295f else 145f

        paint.style = Paint.Style.FILL
        paint.color = "#050505".toColorInt()
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.color = "#555555".toColorInt()
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (isLandscape) 215f else 95f
        paint.isFakeBoldText = true
        paint.color = currentNeonColor

        val fontMetrics = paint.fontMetrics
        val textY = circleCenterY - (fontMetrics.ascent + fontMetrics.descent) / 2f

        canvas.drawText(String.format(java.util.Locale.US, "%.1f°", currentAngle), circleCenterX, textY, paint)
        paint.isFakeBoldText = false

        // --- 6. SEPARATE LINKE & RECHTE KURVENWERTE ---
        paint.textSize = if (isLandscape) 98f else 50f
        paint.isFakeBoldText = true
        paint.color = "#FFAB00".toColorInt()

        paint.textAlign = Paint.Align.LEFT
        val sideTextY = if (isLandscape) centerY - (radius * 0.15f) else centerY + (radius * 0.25f)
        canvas.drawText(String.format(java.util.Locale.US, "%.1f°", abs(maxTempLeft)), centerX - (radius * 0.75f), sideTextY, paint)

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(java.util.Locale.US, "%.1f°", abs(maxTempRight)), centerX + (radius * 0.75f), sideTextY, paint)

        paint.isFakeBoldText = false
    }
}