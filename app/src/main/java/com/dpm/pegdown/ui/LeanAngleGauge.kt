package com.dpm.pegdown.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import com.dpm.pegdown.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class LeanAngleGauge @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // -------------------------------------------------------------------------
    // Rohwerte
    // -------------------------------------------------------------------------

    private var rawCurrentAngle = 0.0

    private var rawTempLeft = 0.0
    private var rawTempRight = 0.0

    private var rawTourLeft = 0.0
    private var rawTourRight = 0.0

    /*
     * Wird ausschließlich hier in der Gauge ausgewertet.
     *
     * Bei Invertierung:
     *   current = -current
     *   left     = -right
     *   right    = -left
     */
    private var isAxisInverted = false

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    private var bikeBitmap: Bitmap? = null

    // -------------------------------------------------------------------------
    // Motorrad
    // -------------------------------------------------------------------------

    private fun getBikeBitmap(): Bitmap? {
        if (bikeBitmap != null) {
            return bikeBitmap
        }

        bikeBitmap = try {
            val drawable = ContextCompat.getDrawable(
                context,
                R.drawable.ic_motorcycle_rear
            )

            drawable?.let {
                val targetWidth = 600
                val targetHeight = 320

                if (it is BitmapDrawable) {
                    Bitmap.createScaledBitmap(
                        it.bitmap,
                        targetWidth,
                        targetHeight,
                        true
                    )
                } else {
                    val bitmap = createBitmap(
                        targetWidth,
                        targetHeight
                    )

                    val canvas = Canvas(bitmap)

                    it.setBounds(
                        0,
                        0,
                        canvas.width,
                        canvas.height
                    )

                    it.draw(canvas)

                    bitmap
                }
            }
        } catch (_: Exception) {
            null
        }

        return bikeBitmap
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun updateData(
        current: Double,
        tempL: Double,
        tempR: Double,
        tourL: Double,
        tourR: Double
    ) {
        rawCurrentAngle = current

        rawTempLeft = tempL
        rawTempRight = tempR

        rawTourLeft = tourL
        rawTourRight = tourR

        invalidate()
    }

    /**
     * Echte Achsen-Invertierung.
     *
     * Nicht nur das Vorzeichen wird gedreht:
     * Links und rechts werden ebenfalls getauscht.
     */
    fun setInverted(inverted: Boolean) {
        if (isAxisInverted == inverted) {
            return
        }

        isAxisInverted = inverted
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Displaywerte
    // -------------------------------------------------------------------------

    private fun getDisplayCurrent(): Double {
        return if (isAxisInverted) {
            -rawCurrentAngle
        } else {
            rawCurrentAngle
        }
    }

    private fun getDisplayTempLeft(): Double {
        return if (isAxisInverted) {
            -rawTempRight
        } else {
            rawTempLeft
        }
    }

    private fun getDisplayTempRight(): Double {
        return if (isAxisInverted) {
            -rawTempLeft
        } else {
            rawTempRight
        }
    }

    private fun getDisplayTourLeft(): Double {
        return if (isAxisInverted) {
            -rawTourRight
        } else {
            rawTourLeft
        }
    }

    private fun getDisplayTourRight(): Double {
        return if (isAxisInverted) {
            -rawTourLeft
        } else {
            rawTourRight
        }
    }

    // -------------------------------------------------------------------------
    // onDraw
    // -------------------------------------------------------------------------

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        if (width <= 0f || height <= 0f) {
            return
        }

        val isLandscape = width > height

        // ---------------------------------------------------------------------
        // Originale Größen / Positionen
        // ---------------------------------------------------------------------

        val radius = if (isLandscape) {
            (height * 0.82f).coerceAtMost(width * 0.38f)
        } else {
            (width.coerceAtMost(height * 2.2f)) * 0.42f
        }

        val centerX = width / 2f

        val centerY = if (isLandscape) {
            height * 0.95f
        } else {
            height * 0.52f
        }

        val maxScale = 60.0

        rectF.apply {
            left = centerX - radius
            top = centerY - radius
            right = centerX + radius
            bottom = centerY + radius
        }

        // ---------------------------------------------------------------------
        // Gauge Arc
        // ---------------------------------------------------------------------

        paint.reset()
        paint.isAntiAlias = true

        paint.style = Paint.Style.STROKE

        val arcStrokeWidth = if (isLandscape) {
            46f
        } else {
            60f
        }

        paint.strokeWidth = arcStrokeWidth
        paint.strokeCap = Paint.Cap.BUTT

        paint.color = "#151515".toColorInt()
        paint.alpha = 255

        canvas.drawArc(
            rectF,
            210f,
            120f,
            false,
            paint
        )

        paint.color = "#FF3D00".toColorInt()

        canvas.drawArc(
            rectF,
            210f,
            25f,
            false,
            paint
        )

        paint.color = "#FFEA00".toColorInt()

        canvas.drawArc(
            rectF,
            235f,
            15f,
            false,
            paint
        )

        paint.color = "#00E676".toColorInt()

        canvas.drawArc(
            rectF,
            250f,
            40f,
            false,
            paint
        )

        paint.color = "#FFEA00".toColorInt()

        canvas.drawArc(
            rectF,
            290f,
            15f,
            false,
            paint
        )

        paint.color = "#FF3D00".toColorInt()

        canvas.drawArc(
            rectF,
            305f,
            25f,
            false,
            paint
        )

        // ---------------------------------------------------------------------
        // Tick marks
        // ---------------------------------------------------------------------

        val halfStroke = arcStrokeWidth / 2f

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = "#DDDDDD".toColorInt()

        for (angle in -60..60 step 15) {
            val degInCanvas = 270.0 + angle
            val rad = Math.toRadians(degInCanvas)

            val innerR = radius - halfStroke
            val outerR = radius + halfStroke

            val startX =
                centerX + (innerR * cos(rad)).toFloat()

            val startY =
                centerY + (innerR * sin(rad)).toFloat()

            val stopX =
                centerX + (outerR * cos(rad)).toFloat()

            val stopY =
                centerY + (outerR * sin(rad)).toFloat()

            canvas.drawLine(
                startX,
                startY,
                stopX,
                stopY,
                paint
            )
        }

        // ---------------------------------------------------------------------
        // Werte
        // ---------------------------------------------------------------------

        val currentAngle = getDisplayCurrent()

        val tempLeft = getDisplayTempLeft()
        val tempRight = getDisplayTempRight()

        val tourLeft = getDisplayTourLeft()
        val tourRight = getDisplayTourRight()

        // ---------------------------------------------------------------------
        // Marker
        // ---------------------------------------------------------------------

        fun drawTriangleMarker(
            angle: Double,
            colorInt: Int,
            isAbove: Boolean
        ) {
            val clamped = angle.coerceIn(
                -maxScale,
                maxScale
            )

            val canvasDeg = 270.0 + clamped
            val rad = Math.toRadians(canvasDeg)

            val triSize = if (!isLandscape) {
                48f
            } else {
                60f
            }

            val baseRadius = if (isAbove) {
                radius +
                        halfStroke +
                        if (!isLandscape) 32f else 42f
            } else {
                radius -
                        halfStroke -
                        if (!isLandscape) 32f else 42f
            }

            val cx =
                centerX +
                        (baseRadius * cos(rad)).toFloat()

            val cy =
                centerY +
                        (baseRadius * sin(rad)).toFloat()

            val perpRad =
                rad + Math.PI / 2.0

            val cosPerp =
                cos(perpRad).toFloat()

            val sinPerp =
                sin(perpRad).toFloat()

            val cosRad =
                cos(rad).toFloat()

            val sinRad =
                sin(rad).toFloat()

            val p1x: Float
            val p1y: Float

            val p2x =
                cx +
                        (triSize * 0.8f * cosPerp)

            val p2y =
                cy +
                        (triSize * 0.8f * sinPerp)

            val p3x =
                cx -
                        (triSize * 0.8f * cosPerp)

            val p3y =
                cy -
                        (triSize * 0.8f * sinPerp)

            if (isAbove) {
                p1x =
                    cx -
                            (triSize * cosRad)

                p1y =
                    cy -
                            (triSize * sinRad)
            } else {
                p1x =
                    cx +
                            (triSize * cosRad)

                p1y =
                    cy +
                            (triSize * sinRad)
            }

            val markerPath = Path().apply {
                moveTo(p1x, p1y)
                lineTo(p2x, p2y)
                lineTo(p3x, p3y)
                close()
            }

            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = colorInt
            paint.alpha = 255

            canvas.drawPath(
                markerPath,
                paint
            )
        }

        drawTriangleMarker(
            tourLeft,
            "#00B0FF".toColorInt(),
            isAbove = false
        )

        drawTriangleMarker(
            tourRight,
            "#00B0FF".toColorInt(),
            isAbove = false
        )

        drawTriangleMarker(
            tempLeft,
            "#FFAB00".toColorInt(),
            isAbove = true
        )

        drawTriangleMarker(
            tempRight,
            "#FFAB00".toColorInt(),
            isAbove = true
        )

        // ---------------------------------------------------------------------
        // Aktuelle Farbe
        // ---------------------------------------------------------------------

        val absAngle = abs(currentAngle)

        val currentNeonColor = when {
            absAngle < 20.0 ->
                "#00E676".toColorInt()

            absAngle < 35.0 ->
                "#FFEA00".toColorInt()

            else ->
                "#FF1744".toColorInt()
        }

        // ---------------------------------------------------------------------
        // Motorrad
        // ---------------------------------------------------------------------

        val clampedCurrent =
            currentAngle.coerceIn(
                -maxScale,
                maxScale
            )

        val currentCanvasDeg =
            270.0 + clampedCurrent

        val bikeRad =
            Math.toRadians(currentCanvasDeg)

        val indicatorRadius =
            radius - halfStroke - 60f

        val bikeX =
            centerX +
                    (indicatorRadius * cos(bikeRad)).toFloat()

        val bikeY =
            centerY +
                    (indicatorRadius * sin(bikeRad)).toFloat()

        val rotationAngle =
            clampedCurrent.toFloat()

        getBikeBitmap()?.let { bitmap ->

            canvas.withTranslation(
                bikeX,
                bikeY
            ) {
                rotate(rotationAngle)

                val bWidth =
                    bitmap.width.toFloat()

                val bHeight =
                    bitmap.height.toFloat()

                val color = currentNeonColor

                val r =
                    Color.red(color) / 255f

                val g =
                    Color.green(color) / 255f

                val b =
                    Color.blue(color) / 255f

                val matrix = floatArrayOf(
                    r * 1.6f, 0f, 0f, 0f, 45f,
                    0f, g * 1.6f, 0f, 0f, 45f,
                    0f, 0f, b * 1.6f, 0f, 45f,
                    0f, 0f, 0f, 1f, 0f
                )

                paint.reset()
                paint.isAntiAlias = true
                paint.colorFilter =
                    ColorMatrixColorFilter(matrix)

                drawBitmap(
                    bitmap,
                    -bWidth / 2f,
                    -bHeight / 2f,
                    paint
                )

                paint.colorFilter = null
            }
        }

        // ---------------------------------------------------------------------
        // Haupt-Kreis
        //
        // WICHTIG:
        // Wieder exakt die ursprünglichen Größen.
        // ---------------------------------------------------------------------

        val circleCenterX = centerX

        val circleCenterY =
            if (isLandscape) {
                centerY - (radius * 0.45f)
            } else {
                centerY - (radius * 0.38f)
            }

        val circleRadius =
            if (isLandscape) {
                295f
            } else {
                145f
            }

        // Hintergrund
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = "#050505".toColorInt()

        canvas.drawCircle(
            circleCenterX,
            circleCenterY,
            circleRadius,
            paint
        )

        // Rand
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.color = "#555555".toColorInt()

        canvas.drawCircle(
            circleCenterX,
            circleCenterY,
            circleRadius,
            paint
        )

        // ---------------------------------------------------------------------
        // Hauptgradzahl
        // ---------------------------------------------------------------------

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize =
            if (isLandscape) {
                215f
            } else {
                95f
            }

        paint.isFakeBoldText = true
        paint.color = currentNeonColor

        val fontMetrics =
            paint.fontMetrics

        val textY =
            circleCenterY -
                    (fontMetrics.ascent +
                            fontMetrics.descent) / 2f

        canvas.drawText(
            String.format(
                Locale.US,
                "%.1f°",
                currentAngle
            ),
            circleCenterX,
            textY,
            paint
        )

        paint.isFakeBoldText = false

        // ---------------------------------------------------------------------
        // Seitenwerte
        // ---------------------------------------------------------------------

        paint.textSize =
            if (isLandscape) {
                98f
            } else {
                50f
            }

        paint.isFakeBoldText = true
        paint.color = "#FFAB00".toColorInt()

        val sideTextY =
            if (isLandscape) {
                centerY - (radius * 0.15f)
            } else {
                centerY + (radius * 0.25f)
            }

        paint.textAlign = Paint.Align.LEFT

        canvas.drawText(
            String.format(
                Locale.US,
                "%.1f°",
                abs(tempLeft)
            ),
            centerX - (radius * 0.75f),
            sideTextY,
            paint
        )

        paint.textAlign = Paint.Align.RIGHT

        canvas.drawText(
            String.format(
                Locale.US,
                "%.1f°",
                abs(tempRight)
            ),
            centerX + (radius * 0.75f),
            sideTextY,
            paint
        )

        paint.isFakeBoldText = false
    }
}