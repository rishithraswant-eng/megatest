package org.seedhecode.incidentassistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import org.seedhecode.incidentassistant.data.MapData
import org.seedhecode.incidentassistant.data.MapLayout
import org.seedhecode.incidentassistant.data.PriorityRank

/** Draws one floor of the zone map; geometry and hit-testing live in [MapLayout]. */
@SuppressLint("ViewConstructor")
class ZoneMapView(context: Context, private val onHit: (MapLayout.Hit) -> Unit) : View(context) {
    private var data: MapData? = null
    private var floor: String? = null
    private var layout: MapLayout? = null
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    // Qualified: inside TextPaint.apply, a bare `density` would be TextPaint's own field (1.0), not the screen's.
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * this@ZoneMapView.density; color = context.getColor(R.color.text_primary) }
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE }

    init { isClickable = true }

    fun show(data: MapData, floor: String?) {
        this.data = data; this.floor = floor
        relayout(); requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val (planW, planH) = data?.let(MapLayout::planSize) ?: (1f to 0.6f)
        setMeasuredDimension(width, (width * planH / planW).toInt().coerceAtLeast((160 * density).toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        val current = data ?: return
        if (width == 0 || height == 0) return
        layout = MapLayout(current, floor, width.toFloat(), height.toFloat(), PIN_RADIUS_DP * density, 8 * density).also { built ->
            contentDescription = "Map of ${floor ?: "unassigned zones"}: " + built.shapes.joinToString("; ") { shape ->
                val open = shape.incidents.count { it.status != org.seedhecode.incidentassistant.data.IncidentStatus.RESOLVED }
                "${shape.zone.name}, $open unresolved of ${shape.incidents.size}"
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val built = layout ?: return
        for (shape in built.shapes) {
            val path = Path().apply {
                moveTo(shape.outline[0].x, shape.outline[0].y)
                shape.outline.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            val color = colorFor(shape.urgentRank)
            fill.color = Color.argb(if (shape.urgentRank == null) 70 else 150, Color.red(color), Color.green(color), Color.blue(color))
            stroke.color = color
            canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
            // Names are shortened to the zone's width and clipped to its outline, so neighbours never overlap.
            val left = shape.outline.minOf { it.x } + 6 * density
            val room = shape.outline.maxOf { it.x } - left - 6 * density
            val name = TextUtils.ellipsize(shape.zone.name, label, room.coerceAtLeast(0f), TextUtils.TruncateAt.END)
            canvas.save(); canvas.clipPath(path)
            canvas.drawText(name, 0, name.length, left, shape.outline.minOf { it.y } + 16 * density, label)
            canvas.restore()
        }
        val radius = PIN_RADIUS_DP * density
        for (pin in built.pins) {
            pinFill.color = if (pin.unresolved) colorFor(PriorityRank.of(pin.incident.priority)) else NEUTRAL
            canvas.drawCircle(pin.center.x, pin.center.y, radius, pinFill)
            canvas.drawCircle(pin.center.x, pin.center.y, radius, pinRing)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            layout?.hit(event.x, event.y)?.let(onHit)
            performClick()
        }
        return true
    }

    companion object {
        const val PIN_RADIUS_DP = 9f
        val NEUTRAL = Color.rgb(0x7A, 0x86, 0x94)
        /** Rank from [PriorityRank]: P1 red, P2 orange, P3 amber, P4 blue; null (no unresolved incident) is neutral. */
        fun colorFor(rank: Int?): Int = when (rank) {
            0 -> Color.rgb(0xE5, 0x48, 0x3B)
            1 -> Color.rgb(0xF2, 0x8C, 0x28)
            2 -> Color.rgb(0xE8, 0xB9, 0x2E)
            3 -> Color.rgb(0x4A, 0x90, 0xD9)
            null -> NEUTRAL
            else -> Color.rgb(0xA0, 0x7C, 0xC9)
        }
    }
}
