package com.bontecou.syncmd.ui.adaptive

import com.bontecou.syncmd.ui.util.AdaptiveInfo
import com.bontecou.syncmd.ui.util.AdaptiveLayout
import com.bontecou.syncmd.ui.util.FoldInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size-class and posture decisions behind the Tablet and Fold* boards. */
class AdaptiveLayoutTest {
    private val verticalHinge = FoldInfo(419, 0, 421, 700, vertical = true, halfOpened = false, separating = true)
    private val flatVertical = FoldInfo(420, 0, 420, 700, vertical = true, halfOpened = false, separating = false)
    private val tabletopFold = FoldInfo(0, 349, 840, 351, vertical = false, halfOpened = true, separating = false)

    @Test fun phoneStaysPhone() {
        assertEquals(AdaptiveLayout.Phone, AdaptiveInfo(412, 915).layout)
        assertFalse(AdaptiveInfo(412, 915).isTabletDevice)
    }

    @Test fun tabletBoardIsExpanded() {
        val tablet = AdaptiveInfo(1280, 800, displaySmallestDp = 800)
        assertEquals(AdaptiveLayout.Expanded, tablet.layout)
        assertTrue(tablet.isTabletDevice)
    }

    @Test fun threePanesStartAt1000Not1100() {
        assertEquals(AdaptiveLayout.Medium, AdaptiveInfo(999, 800).layout)
        assertEquals(AdaptiveLayout.Expanded, AdaptiveInfo(1000, 800).layout)
        assertEquals(AdaptiveLayout.Expanded, AdaptiveInfo(1050, 800).layout)
    }

    @Test fun portraitTabletIsMediumButStillSaysTablet() {
        val portrait = AdaptiveInfo(800, 1280, displaySmallestDp = 800)
        assertEquals(AdaptiveLayout.Medium, portrait.layout)
        assertTrue(portrait.isTabletDevice)
    }

    @Test fun foldableInnerIsMediumAndSaysPhone() {
        val inner = AdaptiveInfo(840, 700, fold = flatVertical, foldable = true, displaySmallestDp = 700)
        assertEquals(AdaptiveLayout.Medium, inner.layout)
        assertFalse(inner.isTabletDevice)
        assertEquals(null, inner.verticalSplit)
        assertEquals(verticalHinge, AdaptiveInfo(840, 700, fold = verticalHinge, foldable = true).verticalSplit)
    }

    @Test fun halfOpenHorizontalFoldIsTabletop() {
        assertEquals(AdaptiveLayout.Tabletop, AdaptiveInfo(840, 700, fold = tabletopFold, foldable = true).layout)
        // Emulators report a half-open hinge as flat but separating.
        val separating = tabletopFold.copy(halfOpened = false, separating = true)
        assertEquals(AdaptiveLayout.Tabletop, AdaptiveInfo(840, 700, fold = separating, foldable = true).layout)
        // A flat, non-separating horizontal fold is just a large screen.
        assertEquals(AdaptiveLayout.Medium, AdaptiveInfo(840, 700, fold = tabletopFold.copy(halfOpened = false), foldable = true).layout)
    }

    @Test fun halfOpenVerticalFoldIsNotTabletop() {
        val book = verticalHinge.copy(halfOpened = true)
        assertEquals(AdaptiveLayout.Medium, AdaptiveInfo(840, 700, fold = book, foldable = true).layout)
    }

    @Test fun foldedCoverDisplayGetsCover() {
        assertEquals(AdaptiveLayout.Cover, AdaptiveInfo(412, 800, fold = null, foldable = true, displaySmallestDp = 412).layout)
        // Not on a regular phone, and not when the foldable reports its fold (inner display, narrow window).
        assertEquals(AdaptiveLayout.Phone, AdaptiveInfo(412, 800, foldable = false).layout)
        assertEquals(AdaptiveLayout.Phone, AdaptiveInfo(500, 800, fold = flatVertical, foldable = true).layout)
    }

    @Test fun narrowSplitScreenOnALargeScreenIsSplit() {
        assertEquals(AdaptiveLayout.Split, AdaptiveInfo(403, 700, multiWindow = true, foldable = true, displaySmallestDp = 700).layout)
        assertEquals(AdaptiveLayout.Split, AdaptiveInfo(500, 800, multiWindow = true, displaySmallestDp = 800).layout)
        // Split screen on a phone keeps the phone screens; a wide multi-window keeps the panes.
        assertEquals(AdaptiveLayout.Phone, AdaptiveInfo(412, 400, multiWindow = true, displaySmallestDp = 412).layout)
        assertEquals(AdaptiveLayout.Medium, AdaptiveInfo(700, 800, multiWindow = true, displaySmallestDp = 800).layout)
    }

    @Test fun tabletopWinsOverSplit() {
        assertEquals(AdaptiveLayout.Tabletop, AdaptiveInfo(403, 700, fold = tabletopFold, multiWindow = true, foldable = true, displaySmallestDp = 700).layout)
    }
}
