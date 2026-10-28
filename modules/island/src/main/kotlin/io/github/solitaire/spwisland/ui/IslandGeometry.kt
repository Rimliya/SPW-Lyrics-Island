// SPDX-License-Identifier: GPL-3.0-only
package io.github.solitaire.spwisland.ui

import io.github.solitaire.spwisland.core.IslandShape
import java.awt.geom.AffineTransform
import java.awt.Shape
import java.util.LinkedHashMap

object IslandGeometry {
    private data class SilhouetteKey(val width: Int, val height: Int, val shape: IslandShape,
                                     val roundness: Int, val squircle: Boolean)
    private val silhouettes = object : LinkedHashMap<SilhouetteKey, Shape>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SilhouetteKey, Shape>) = size > 16
    }

    fun contentInset(width: Int, height: Int, shape: IslandShape, top: Int, bottom: Int,
                     cornerRoundness: Int = 60, squircle: Boolean = true): Float {
        val silhouette = silhouette(width, height, shape, cornerRoundness, squircle)
        var inset = 0
        for (y in top.coerceAtLeast(1)..bottom.coerceAtMost(height - 2)) {
            var left = 0; var right = width / 2
            while (left < right) {
                val middle = (left + right) / 2
                if (silhouette.contains(middle.toDouble(), y.toDouble())) right = middle else left = middle + 1
            }
            inset = maxOf(inset, left)
        }
        return inset + 4f
    }

    /** Width reserved outside the element-safe rectangle for each curved side. */
    fun frameInset(height: Double, shape: IslandShape, cornerRoundness: Int): Double {
        val h = (height - 1.0).coerceAtLeast(0.0)
        val scale = shape.roundness(cornerRoundness) / 100.0
        val radius = when (shape) {
            IslandShape.NOTCH -> minOf(h * .45, 32.0) * scale + 10.0
            IslandShape.CAPSULE -> h / 2.0
            IslandShape.SQUARE -> 0.0
            IslandShape.ROUNDED_RECTANGLE -> h / 2.0 * scale
        }
        return .5 + radius
    }

    @Synchronized
    fun silhouette(width: Int, height: Int, shape: IslandShape, cornerRoundness: Int = 60,
                   squircle: Boolean = true): Shape {
        val roundness = shape.roundness(cornerRoundness)
        val key = SilhouetteKey(width, height, shape, roundness, squircle)
        silhouettes[key]?.let { return it }
        val w = (width - 1).coerceAtLeast(0).toDouble()
        val h = (height - 1).coerceAtLeast(0).toDouble()
        val built = if (shape != IslandShape.NOTCH) {
            val rounded = ContinuousCornerPath.roundedRectangle(w, h, roundness, squircle)
            AffineTransform.getTranslateInstance(.5, .5).createTransformedShape(rounded)
        } else {
            IslandNotchGeometry.silhouette(w, h, cornerRoundness.coerceIn(0, 100))
        }
        silhouettes[key] = built
        return built
    }

    fun progressEdgeInset(width: Int, height: Int, shape: IslandShape, cornerRoundness: Int): Double =
        if (shape == IslandShape.NOTCH)
            10.5 + IslandNotchGeometry.bodyRadius(width - 1.0, height - 1.0, cornerRoundness.coerceIn(0, 100))
        else minOf(frameInset(height.toDouble(), shape, cornerRoundness), width / 2.0)

}
