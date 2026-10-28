package io.github.solitaire.spwisland.ui

import io.github.solitaire.spwisland.core.IslandShape
import kotlin.test.Test
import kotlin.test.assertSame

class IslandGeometryCacheTest {
    @Test
    fun recentSilhouettesRemainCached() {
        val first = IslandGeometry.silhouette(640, 120, IslandShape.ROUNDED_RECTANGLE, 60, true)
        val second = IslandGeometry.silhouette(320, 80, IslandShape.CAPSULE, 100, true)
        assertSame(first, IslandGeometry.silhouette(640, 120, IslandShape.ROUNDED_RECTANGLE, 60, true))
        assertSame(second, IslandGeometry.silhouette(320, 80, IslandShape.CAPSULE, 100, true))
    }
}
