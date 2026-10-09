package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.feature.ime.CompactCameraPlacement.Bounds
import com.tyraen.voicekeyboard.feature.ime.CompactCameraPlacement.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CompactCameraPlacementTest {
    private fun choose(vararg cutouts: Bounds, width: Int = 400, height: Int = 64, minSpan: Int = 260) =
        CompactCameraPlacement.choose(width, height, minSpan, cutouts.toList())

    @Test fun `no cameras use full width with no extra height`() {
        assertEquals(Placement(), choose())
    }

    @Test fun `left camera leaves the right hand span`() {
        assertEquals(Placement(leftPadding = 100, reason = "side_span"), choose(Bounds(0, -20, 100, 64)))
    }

    @Test fun `right camera leaves the left hand span`() {
        assertEquals(Placement(rightPadding = 100, reason = "side_span"), choose(Bounds(300, -20, 400, 64)))
    }

    @Test fun `center camera chooses widest complete span rather than splitting controls`() {
        assertEquals(
            Placement(leftPadding = 300, reason = "side_span"),
            choose(Bounds(250, 0, 300, 64), width = 700)
        )
    }

    @Test fun `equal free spans choose the leftmost consistently`() {
        assertEquals(
            Placement(rightPadding = 410, reason = "side_span"),
            choose(Bounds(390, 0, 410, 64), width = 800)
        )
    }

    @Test fun `space between cameras can be the widest usable span`() {
        assertEquals(
            Placement(leftPadding = 80, rightPadding = 80, reason = "side_span"),
            choose(Bounds(0, -10, 80, 64), Bounds(520, -10, 600, 64), width = 600)
        )
    }

    @Test fun `minimum span is inclusive`() {
        assertEquals(Placement(leftPadding = 140, reason = "side_span"), choose(Bounds(0, 0, 140, 64)))
    }

    @Test fun `one pixel below minimum falls back above the entire camera`() {
        assertEquals(
            Placement(bottomPadding = 88, reason = "above_cutouts"),
            choose(Bounds(0, -24, 141, 64))
        )
    }

    @Test fun `full width camera moves the whole row above its top`() {
        assertEquals(
            Placement(bottomPadding = 94, reason = "above_cutouts"),
            choose(Bounds(0, -30, 400, 100))
        )
    }

    @Test fun `a centered camera falls back when neither side is wide enough`() {
        assertEquals(
            Placement(bottomPadding = 44, reason = "above_cutouts"),
            choose(Bounds(180, 20, 220, 80))
        )
    }

    @Test fun `overlapping and nested camera bounds act as one obstruction`() {
        val cutouts = arrayOf(Bounds(40, 0, 100, 64), Bounds(0, -10, 80, 64), Bounds(30, 10, 50, 30))
        val expected = Placement(leftPadding = 100, reason = "side_span")
        assertEquals(expected, choose(*cutouts))
        assertEquals(expected, choose(*cutouts.reversedArray()))
    }

    @Test fun `partly offscreen horizontal bounds are clipped`() {
        assertEquals(Placement(leftPadding = 90, reason = "side_span"), choose(Bounds(-50, -10, 90, 64)))
        assertEquals(Placement(rightPadding = 90, reason = "side_span"), choose(Bounds(310, -10, 900, 64)))
    }

    @Test fun `rectangles outside or just touching the row do not obstruct it`() {
        assertEquals(
            Placement(),
            choose(
                Bounds(-100, 0, 0, 64), Bounds(400, 0, 450, 64),
                Bounds(0, -100, 400, 0), Bounds(0, 64, 400, 100)
            )
        )
    }

    @Test fun `empty and reversed rectangles are ignored`() {
        assertEquals(Placement(), choose(Bounds(20, 0, 20, 64), Bounds(100, 0, 50, 64), Bounds(0, 30, 400, 20)))
    }

    @Test fun `fallback also clears a camera reached only after moving upward`() {
        assertEquals(
            Placement(bottomPadding = 164, reason = "above_cutouts"),
            choose(Bounds(0, 10, 400, 64), Bounds(100, -100, 300, -40))
        )
    }

    @Test fun `fallback stops in a clear vertical gap without passing unrelated higher cameras`() {
        assertEquals(
            Placement(bottomPadding = 54, reason = "above_cutouts"),
            choose(Bounds(0, 10, 400, 64), Bounds(100, -200, 300, -100))
        )
    }

    @Test fun `fallback accounts for cameras above the row but ignores offscreen horizontal ones`() {
        assertEquals(
            Placement(bottomPadding = 54, reason = "above_cutouts"),
            choose(Bounds(0, 10, 400, 64), Bounds(500, -100, 600, -40))
        )
    }

    @Test fun `zero size before layout produces no padding`() {
        assertEquals(Placement(), choose(Bounds(0, -20, 400, 80), width = 0))
        assertEquals(Placement(), choose(Bounds(0, -20, 400, 80), height = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative width is rejected`() {
        choose(width = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative row height is rejected`() {
        choose(height = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive minimum span is rejected`() {
        choose(minSpan = 0)
    }

    @Test fun `generated placements never overlap a valid camera or shrink the row`() {
        val random = Random(17)
        repeat(1000) {
            val width = 260 + random.nextInt(500)
            val height = 48 + random.nextInt(25)
            val cutouts = List(random.nextInt(8)) {
                val left = random.nextInt(width + 200) - 100
                val top = random.nextInt(300) - 200
                Bounds(left, top, left + 1 + random.nextInt(180), top + 1 + random.nextInt(100))
            }
            val placement = CompactCameraPlacement.choose(width, height, 260, cutouts)
            val left = placement.leftPadding
            val right = width - placement.rightPadding
            val top = -placement.bottomPadding
            val bottom = height - placement.bottomPadding
            assertTrue("$placement", left >= 0 && right <= width && right - left >= 260)
            assertEquals(height, bottom - top)
            for (cutout in cutouts) {
                val overlap = cutout.left < right && cutout.right > left &&
                    cutout.top < bottom && cutout.bottom > top
                assertTrue("$placement overlaps $cutout in $cutouts", !overlap)
            }
            assertEquals(placement, CompactCameraPlacement.choose(width, height, 260, cutouts.reversed()))
        }
    }
}
