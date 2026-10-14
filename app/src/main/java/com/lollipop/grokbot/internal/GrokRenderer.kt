// Vendored from Mr-XiaoLiang/GrokBot at 2cd2e82f31cddcec7b825877830362b3ec364e33 (MIT, see
// third_party/grokbot/). Ported off Compose onto android.graphics: this file draws onto a
// android.graphics.Canvas and holds the frame size instead of a DrawScope's `size`.

package com.lollipop.grokbot.internal

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.lollipop.grokbot.parseHexColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Colour stops of a belt gradient, the five `stop-color` entries of the web build. */
private const val RIBBON_STOPS = 5

/** Alpha of an opaque colour, the value the paint keeps until a draw call asks for another. */
private const val OPAQUE = 255

/**
 * Colours the renderer paints with.
 *
 * They follow the configuration rather than the frame, so the host refreshes this once per
 * configuration change with [updateFrom] instead of once per frame.
 *
 * Ported from Compose: every colour is an ARGB int, the form `Paint` takes.
 */
internal class GrokFrameStyle {
    /** `--fg` of the web build: body fill and the tint of every overlay element. */
    var bodyColor: Int = 0xFF000000.toInt()

    /** `--bg` of the web build: the fill of the eye cut outs. */
    var eyeFill: Int = GrokTabs.EYE_BG

    /** Fill of the badge blob, the `circle` inside the body group. */
    var badgeColor: Int = GrokEyeOptions.DEFAULT_BADGE

    /** Reads the palette [engine] currently resolves to. */
    fun updateFrom(engine: GrokEngine) {
        bodyColor = parseHexColor(engine.fgHex)
        eyeFill = engine.eyeBg
        badgeColor = engine.badgeColor
    }
}

/**
 * Draws one [GrokFrame] onto a canvas.
 *
 * This is the Kotlin counterpart of the SVG tree the web build keeps around: the engine produced the
 * frame as data, and everything the DOM used to do implicitly is spelled out here.
 *
 * The draw order follows `FX.attach` (`temp/replica/src/fx.js`), which builds the tree as the
 * particle `back` container, then the overlay elements, then the body group, then the particle
 * `front` container: the `z < 0` halves of a belt stay behind the character while the `z >= 0`
 * halves wrap around it, and everything else happens behind the body outline, the eyes clipped to
 * it and the badge. Only the body group carries the frame transform and opacity.
 *
 * Instances are stateful (path cache, scratch objects, the two reusable paints) and are meant to be
 * created once per view.
 */
internal class GrokRenderer(capacity: Int = 64) {
    private val paths = GrokPathCache(capacity)
    private val matrix = Matrix()
    private val scratch = Path()

    /** Filled shapes: the body, the eyes, the badge, the sparks and the ribbons. */
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Stroked shapes: the overlay rings and the radar progress arc. */
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Bounds handed to `drawArc` and `drawRoundRect`, reused so no frame allocates. */
    private val bounds = RectF()

    /** Parsed particle colours, cached: the spark palette is a handful of repeated hex strings. */
    private val colors = HashMap<String, Int>(8)

    /**
     * Paints [frame] with [style], mapping the frame view box onto the largest centred square of the
     * canvas, the `preserveAspectRatio` default of the web build.
     *
     * [poseScale] is the per silhouette zoom of `_applyPoseScale` (`character.js` lines 299-313),
     * which the web build applied as a CSS `transform: scale()` around the centre of the element.
     * A canvas is uniformly scaled instead, which is the same thing once the fit above has centred
     * the artwork.
     */
    fun drawGrokFrame(
        canvas: Canvas,
        width: Float,
        height: Float,
        frame: GrokFrame,
        style: GrokFrameStyle,
        poseScale: Float = 1f,
    ) {
        val box = frame.viewBox
        if (box.size <= 0f) return
        val scale = minOf(width, height) / box.size
        if (scale <= 0f) return
        val left = (width - box.size * scale) / 2f - box.x * scale
        val top = (height - box.size * scale) / 2f - box.y * scale
        val save = canvas.save()
        if (poseScale != 1f) {
            canvas.scale(poseScale, poseScale, width / 2f, height / 2f)
        }
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        drawParticles(canvas, frame, back = true)
        drawOverlay(canvas, frame, style)
        drawBody(canvas, frame, style)
        drawParticles(canvas, frame, back = false)
        canvas.restoreToCount(save)
    }

    /**
     * The particle layer of [frame]: the sparks and belt ribbons that sit [back] of the body, or the
     * part of a ribbon that has swung in front of it.
     *
     * The web build keeps the two apart (`FX.attach`): sparks and the `z < 0` halves of a ribbon are
     * appended to `back`, which is attached before the overlay elements, the `z >= 0` halves to
     * `front`, attached after the body group. Sparks never reach `front`, so the second pass only
     * has ribbons to do.
     */
    private fun drawParticles(canvas: Canvas, frame: GrokFrame, back: Boolean) {
        val particles = frame.particles
        for (slot in particles.indices) {
            val particle = particles[slot]
            val alpha = particle.alpha
            if (alpha <= 0f) continue
            if (particle.orbit == null) {
                if (back) drawSpark(canvas, particle, alpha)
            } else {
                drawRibbon(canvas, particle, alpha, front = !back)
            }
        }
    }

    /**
     * One burst spark, the `star`, `round` and streak branches of the web build's `step`.
     *
     * A star is the unit [GrokGeo.STAR_PATH] placed by `translate(x y) rotate(rot) scale(size)`, a
     * round spark is the plain `circle` the web build writes for it, and the rest are capsules of
     * `width` x `height` rotated onto the velocity, drawn from the top left corner the `rect` element
     * uses.
     */
    private fun drawSpark(canvas: Canvas, particle: GrokParticle, alpha: Float) {
        val paint = fillPaint(colorOf(particle.color), alpha)
        when {
            particle.star -> {
                val save = canvas.save()
                canvas.translate(particle.x, particle.y)
                canvas.rotate(particle.rot)
                canvas.scale(particle.size, particle.size)
                canvas.drawPath(paths.path(GrokGeo.STAR_PATH), paint)
                canvas.restoreToCount(save)
            }

            particle.round -> canvas.drawCircle(particle.x, particle.y, particle.size, paint)

            else -> {
                val save = canvas.save()
                canvas.rotate(particle.angle, particle.x, particle.y)
                bounds.set(
                    particle.x - particle.width / 2f,
                    particle.y - particle.height / 2f,
                    particle.x + particle.width / 2f,
                    particle.y + particle.height / 2f,
                )
                canvas.drawRoundRect(bounds, particle.rx, particle.rx, paint)
                canvas.restoreToCount(save)
            }
        }
    }

    /**
     * One belt ribbon: the half of it that sits [front] of the body, or behind it.
     *
     * Every band of a side becomes a sub-path of one outline, so that the bands of a trail that
     * overlap after a depth change are filled as a single shape and share one alpha instead of
     * stacking up where they cross. The outline is then filled with the gradient the web build
     * builds out of five `hsl` stops running from the oldest trail sample to the newest, which is
     * the "colourful" half of the effect: both sides share it, so the front half of a ribbon keeps
     * the colour of the part that went behind the character.
     */
    private fun drawRibbon(canvas: Canvas, particle: GrokParticle, alpha: Float, front: Boolean) {
        val count = if (front) particle.frontBandCount else particle.backBandCount
        if (count <= 0) return
        val hist = particle.hist
        if (hist.size < 2) return
        val bands = if (front) particle.frontBands else particle.backBands
        scratch.reset()
        for (slot in 0 until count) band(scratch, bands[slot])
        val from = hist[0]
        val to = hist[hist.size - 1]
        // The platform shader rejects a gradient with a non finite axis, where the browser silently
        // paints nothing, so a trail that collapsed into NaN is left out.
        if (!from.x.isFinite() || !from.y.isFinite() || !to.x.isFinite() || !to.y.isFinite()) return
        val paint = fillPaint(0, alpha)
        val stops = ribbonColors(particle)
        // A zero length axis is a legal degenerate gradient on the platform, and the browser paints
        // the last stop there; the shader is built either way, exactly as the web build does.
        paint.shader = LinearGradient(from.x, from.y, to.x, to.y, stops, null, Shader.TileMode.CLAMP)
        canvas.drawPath(scratch, paint)
        paint.shader = null
    }

    /**
     * Appends one band to [target], the `band()` builder of the web build's `ribbon()`.
     *
     * The outline runs down the outer side to the head, turns the corner there when the run reaches
     * the head of the trail, comes back along the inner side and turns again when the run starts at
     * the tail. [GrokRibbonBand.headCap] / [GrokRibbonBand.tailCap] carry the radius of those turns,
     * or `0` when the run was cut by a depth change and the edge closes as a straight line instead.
     */
    private fun band(target: Path, band: GrokRibbonBand) {
        val points = band.pointCount
        if (points < 2) return
        val outer = band.plus
        val inner = band.minus
        target.moveTo(outer[0], outer[1])
        for (point in 1 until points) target.lineTo(outer[point * 2], outer[point * 2 + 1])
        cap(target, outer, inner, points - 1, band.headCap)
        for (point in points - 1 downTo 0) target.lineTo(inner[point * 2], inner[point * 2 + 1])
        cap(target, inner, outer, 0, band.tailCap)
        target.close()
    }

    /**
     * Closes one end of a band with the half turn the web build draws as `A <r> <r> 0 0 0`.
     *
     * That sweep flag is always `0`, a counter clockwise half turn, and the direction it bulges in
     * follows from the offset side of a band being its tangent turned by 90 degrees: from the outer
     * point of the head it leaves along the trail, from the inner point of the tail it leaves back
     * along it. The chord of the turn is the diameter of the circle, so the arc lands exactly on the
     * point the outline continues from.
     */
    private fun cap(target: Path, from: FloatArray, to: FloatArray, point: Int, radius: Float) {
        if (radius <= 0f) return
        val startX = from[point * 2]
        val startY = from[point * 2 + 1]
        val centerX = (startX + to[point * 2]) / 2f
        val centerY = (startY + to[point * 2 + 1]) / 2f
        bounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        target.arcTo(
            bounds,
            atan2(startY - centerY, startX - centerX) * 180f / PI.toFloat(),
            -180f,
            false,
        )
    }

    /**
     * Stops of a belt gradient, the five `stop-color` entries the web build writes onto it.
     *
     * The hues start at the particle hue, drift by `hueVel * life` and are spread by `hueSpan`, the
     * lightness runs from 56% to 100% over the five stops and the hue is rounded to whole degrees,
     * which is the value the browser is handed after the web build formats the attribute.
     */
    private fun ribbonColors(particle: GrokParticle): IntArray {
        val hue = particle.hue + particle.hueVel * particle.life
        return IntArray(RIBBON_STOPS) { stop ->
            val t = stop / (RIBBON_STOPS - 1f)
            hsl(
                hueDegrees = hue + t * particle.hueSpan,
                saturation = 0.56f,
                lightness = (56f + 11f * t).roundToInt() / 100f,
            )
        }
    }

    /** `hsl(<hue> <saturation> <lightness>)` of CSS, the colour space the belt stops are written in. */
    private fun hsl(hueDegrees: Float, saturation: Float, lightness: Float): Int {
        // The browser writes the hue as `hsl(<number> …)` and a NaN would become `hsl(NaN …)`, which
        // it drops; `roundToInt` throws on NaN instead, so the wrap of a non finite hue is pinned.
        val hue = if (hueDegrees.isFinite()) {
            (((hueDegrees % 360f) + 360f) % 360f).roundToInt()
        } else {
            0
        }
        val sector = hue / 60f
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val second = chroma * (1f - abs(sector % 2f - 1f))
        val red: Float
        val green: Float
        val blue: Float
        when (sector.toInt()) {
            0 -> {
                red = chroma
                green = second
                blue = 0f
            }

            1 -> {
                red = second
                green = chroma
                blue = 0f
            }

            2 -> {
                red = 0f
                green = chroma
                blue = second
            }

            3 -> {
                red = 0f
                green = second
                blue = chroma
            }

            4 -> {
                red = second
                green = 0f
                blue = chroma
            }

            else -> {
                red = chroma
                green = 0f
                blue = second
            }
        }
        val match = lightness - chroma / 2f
        return argb(red + match, green + match, blue + match)
    }

    /** Component of a `0..1` triple as the byte of an opaque ARGB int. */
    private fun argb(red: Float, green: Float, blue: Float): Int =
        (red.coerceIn(0f, 1f) * 255f).roundToInt().shl(16) or
            (green.coerceIn(0f, 1f) * 255f).roundToInt().shl(8) or
            (blue.coerceIn(0f, 1f) * 255f).roundToInt() or
            (OPAQUE shl 24)

    /** [parseHexColor] memoised per string, so a spark does not parse its colour on every frame. */
    private fun colorOf(hex: String): Int = colors.getOrPut(hex) { parseHexColor(hex) }

    /**
     * Shared filled paint at [color] and [alpha]; the caller may still set a shader on it and has to
     * clear it again, as [drawRibbon] does.
     */
    private fun fillPaint(color: Int, alpha: Float): Paint {
        fill.color = color
        fill.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        fill.shader = null
        return fill
    }

    /** Everything the effect layer shows this frame, all of it behind the body. */
    private fun drawOverlay(canvas: Canvas, frame: GrokFrame, style: GrokFrameStyle) {
        val elements = frame.overlay.elements
        for (slot in elements.indices) {
            val element = elements[slot]
            if (!element.visible) continue
            val alpha = element.opacity
            if (alpha <= 0f) continue
            val color = style.bodyColor
            // SVG default when a painter leaves the attribute alone.
            val width = if (element.strokeWidth > 0f) element.strokeWidth else 1f
            val d = element.path
            val save = canvas.save()
            canvas.concat(matrixOf(element))
            when {
                d != null && element.stroke -> canvas.drawPath(paths.path(d), strokePaint(color, alpha, width))
                d != null -> canvas.drawPath(paths.path(d), fillPaint(color, alpha))
                !element.stroke -> canvas.drawCircle(element.circleX, element.circleY, element.radius, fillPaint(color, alpha))
                element.dash > 0f -> drawDashProgress(canvas, element, color, alpha, width)
                else -> canvas.drawCircle(element.circleX, element.circleY, element.radius, strokePaint(color, alpha, width))
            }
            canvas.restoreToCount(save)
        }
    }

    /** Shared stroked paint at [color], [alpha] and [width]. */
    private fun strokePaint(color: Int, alpha: Float, width: Float): Paint {
        stroke.color = color
        stroke.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        stroke.strokeWidth = width
        stroke.shader = null
        return stroke
    }

    /**
     * The `radar` progress ring: a circle stroked with `stroke-dasharray: <circumference>` and a
     * `stroke-dashoffset` that leaves `1 - offset / dash` of the outline visible.
     *
     * A dash pattern of `dash, dash` starting at `offset` shows exactly the last
     * `1 - offset / dash` of the circle, which is an arc from that angle back to the start, so the
     * arc is drawn directly instead of relying on the dash phase of the platform.
     */
    private fun drawDashProgress(
        canvas: Canvas,
        element: GrokOverlayItem,
        color: Int,
        alpha: Float,
        width: Float,
    ) {
        val visible = (1f - element.dashOffset / element.dash).coerceIn(0f, 1f)
        if (visible <= 0f) return
        val start = 360f * (1f - visible)
        val radius = element.radius
        bounds.set(
            element.circleX - radius,
            element.circleY - radius,
            element.circleX + radius,
            element.circleY + radius,
        )
        canvas.drawArc(bounds, start, 360f - start, false, strokePaint(color, alpha, width))
    }

    /**
     * Body outline, eyes clipped to it, and the badge blob, all inside the frame transform.
     *
     * The four calls transcribe the web build's transform attribute
     * (`character.js` 723-733) one for one, and that list is
     * `translate(R + tx, R + ty) rotate(rot) scale(sx, sy) translate(-R, -R)`. The `rotate` and the
     * `scale` of an SVG transform list act around the *origin*, which is the pivot a canvas uses
     * when none is given - the Compose port had to pass `Offset.Zero` explicitly, and the mistake it
     * guarded against (a pivot applied twice, once inside the call and once through the trailing
     * `translate(-pivot, -pivot)`) cannot happen here.
     */
    private fun drawBody(canvas: Canvas, frame: GrokFrame, style: GrokFrameStyle) {
        val opacity = frame.opacity
        if (opacity <= 0f || frame.bodyPath.isEmpty()) return
        val body = paths.path(frame.bodyPath)
        val eyes = frame.eyeFrame
        val pivot = frame.pivot
        val save = canvas.save()
        canvas.translate(frame.translateX, frame.translateY)
        canvas.rotate(frame.rotation)
        canvas.scale(frame.scaleX, frame.scaleY)
        canvas.translate(-pivot, -pivot)
        canvas.drawPath(body, fillPaint(style.bodyColor, opacity))
        val clip = canvas.save()
        canvas.clipPath(body)
        for (eye in 0 until 2) {
            if (!eyes.visible[eye]) continue
            polygon(scratch, eyes.polys[eye])
            canvas.drawPath(scratch, fillPaint(style.eyeFill, opacity))
        }
        canvas.restoreToCount(clip)
        if (eyes.badgeVisible) {
            canvas.drawCircle(eyes.badgeX, eyes.badgeY, eyes.badgeR, fillPaint(style.badgeColor, opacity))
        }
        canvas.restoreToCount(save)
    }

    /**
     * The `2x3` affine transform of [element] as a platform matrix.
     *
     * The frame stores the SVG form, which is `x' = a*x + c*y + e` and `y' = b*x + d*y + f` for
     * `matrix(a b c d e f)`; [Matrix.setValues] takes the transposed layout, so the skew entries
     * swap places.
     */
    private fun matrixOf(element: GrokOverlayItem): Matrix {
        val m = element.matrix
        if (m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 1f && m[4] == 0f && m[5] == 0f) return identity
        matrix.setValues(
            floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f),
        )
        return matrix
    }

    /** Closed polygon through the flattened `x, y` pairs of [points], the `polyPath` of the web build. */
    private fun polygon(target: Path, points: FloatArray) {
        target.reset()
        var index = 0
        while (index + 1 < points.size) {
            if (index == 0) {
                target.moveTo(points[0], points[1])
            } else {
                target.lineTo(points[index], points[index + 1])
            }
            index += 2
        }
        target.close()
    }

    private companion object {
        /** Handed back for the untransformed overlay elements. */
        val identity = Matrix()
    }
}
