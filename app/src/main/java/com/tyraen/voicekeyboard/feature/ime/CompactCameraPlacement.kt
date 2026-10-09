package com.tyraen.voicekeyboard.feature.ime

/** Geometry for placing one complete compact row beside cameras, or safely above them. */
object CompactCameraPlacement {
    const val REASON_CLEAR = "clear"
    const val REASON_SIDE_SPAN = "side_span"
    const val REASON_ABOVE_CUTOUTS = "above_cutouts"

    /** Bounds use the nominal, unshifted row's coordinates. Right and bottom are exclusive. */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Placement(
        val leftPadding: Int = 0,
        val rightPadding: Int = 0,
        val bottomPadding: Int = 0,
        val reason: String = REASON_CLEAR
    )

    /**
     * The nominal row occupies [0, width] horizontally and [0, rowHeight] vertically. Callers
     * inflate camera bounds by their desired safety gap before calling, and apply the returned
     * padding outside the row without reducing its height or its minimum usable width.
     *
     * A side span keeps the row at its nominal height. Otherwise bottomPadding moves the entire
     * full-width row upward, so its new vertical bounds are [-bottomPadding, rowHeight -
     * bottomPadding]. Keep the input coordinates fixed while applying that result; feeding back
     * bounds relative to the shifted row would make repeated layout passes oscillate.
     *
     * Equal widest spans select the leftmost. The caller owns viewport-size limits; this solver
     * never shrinks controls or wraps them onto a second row.
     */
    fun choose(width: Int, rowHeight: Int, minSpan: Int, cutouts: List<Bounds>): Placement {
        require(width >= 0) { "width must be non-negative" }
        require(rowHeight >= 0) { "rowHeight must be non-negative" }
        require(minSpan > 0) { "minSpan must be positive" }
        if (width == 0 || rowHeight == 0) return Placement()

        // Ignore empty/reversed rectangles, and clip horizontal bounds to the row. Retain the
        // full vertical coordinates: moving above one camera can encounter another higher up.
        val relevant = cutouts.mapNotNull { bounds ->
            if (bounds.left >= bounds.right || bounds.top >= bounds.bottom) return@mapNotNull null
            val left = bounds.left.coerceIn(0, width)
            val right = bounds.right.coerceIn(0, width)
            if (left >= right) null else bounds.copy(left = left, right = right)
        }
        val overlapping = relevant.filter { it.top < rowHeight && it.bottom > 0 }
        if (overlapping.isEmpty()) return Placement()

        var cursor = 0
        var bestStart = 0
        var bestEnd = 0
        for (bounds in overlapping.sortedWith(compareBy<Bounds> { it.left }.thenBy { it.right })) {
            if (bounds.left - cursor > bestEnd - bestStart) {
                bestStart = cursor
                bestEnd = bounds.left
            }
            cursor = maxOf(cursor, bounds.right)
        }
        if (width - cursor > bestEnd - bestStart) {
            bestStart = cursor
            bestEnd = width
        }
        if (bestEnd - bestStart >= minSpan) {
            return Placement(
                leftPadding = bestStart,
                rightPadding = width - bestEnd,
                reason = REASON_SIDE_SPAN
            )
        }

        var offset = 0L
        while (true) {
            val rowTop = -offset
            val rowBottom = rowHeight.toLong() - offset
            val obstructionTop = relevant
                .filter { it.top.toLong() < rowBottom && it.bottom.toLong() > rowTop }
                .minOfOrNull { it.top }
                ?: break
            // Each step moves above at least one obstruction and can never meet it again.
            offset = rowHeight.toLong() - obstructionTop.toLong()
        }
        require(offset <= Int.MAX_VALUE) { "cutout coordinates require unrepresentable padding" }
        return Placement(bottomPadding = offset.toInt(), reason = REASON_ABOVE_CUTOUTS)
    }
}
