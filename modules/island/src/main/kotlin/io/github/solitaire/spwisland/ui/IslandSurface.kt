// SPDX-License-Identifier: GPL-3.0-only
package io.github.solitaire.spwisland.ui

import io.github.solitaire.spwisland.ui.gpu.IslandGpuFrame
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel

/** Stable translucent backing surface; animation changes the child silhouette, not the native bitmap. */
class IslandSurface(val island: IslandPanel) : JPanel(null) {
    internal var hoverPose = IslandHoverMotion.Pose.SHOWN
    @Volatile internal var presentedFrames = 0L
    internal val renderCapacity = Dimension()
    internal var gpuRedraw: (() -> Unit)? = null
    private val latestGpuFrame = AtomicReference<IslandGpuFrame?>()
    private data class InputRegionKey(
        val width: Int,
        val height: Int,
        val shape: io.github.solitaire.spwisland.core.IslandShape,
        val cornerRoundness: Int,
        val squircle: Boolean,
    )
    private var inputRegionKey: InputRegionKey? = null
    private var inputRegionBase: Shape? = null

    init { isOpaque = false; isDoubleBuffered = false; island.isDoubleBuffered = false; add(island) }

    internal fun publishGpuFrame(frame: IslandGpuFrame) {
        latestGpuFrame.set(frame)
    }

    internal fun currentGpuFrame(): IslandGpuFrame? = latestGpuFrame.get()

    internal fun clearGpuFrame() {
        latestGpuFrame.set(null)
    }

    override fun paintChildren(graphics: Graphics) {
        gpuRedraw?.let { it(); return }
        val g = graphics.create() as Graphics2D
        try {
            g.translate(island.x.toDouble(), island.y.toDouble())
            island.paintPresentation(g, IslandHoverGeometry.frame(island.width, island.height,
                island.settings.shape, island.settings.cornerRoundness, island.settings.squircle, hoverPose))
            presentedFrames++
        } finally { g.dispose() }
    }

    override fun paintComponent(graphics: Graphics) {
        if (gpuRedraw != null) return
        val g = graphics.create() as Graphics2D
        try {
            g.composite = AlphaComposite.Clear
            g.fillRect(0, 0, width, height)
        } finally { g.dispose() }
    }

    @Synchronized
    fun inputRegion(): Shape {
        val settings = island.settings
        val key = InputRegionKey(island.width, island.height, settings.shape, settings.cornerRoundness, settings.squircle)
        val padded = if (key == inputRegionKey) {
            checkNotNull(inputRegionBase)
        } else {
            val shape = IslandGeometry.silhouette(island.width, island.height, settings.shape,
                settings.cornerRoundness, settings.squircle)
            // Include the antialiased outer edge instead of clipping it to a hard pixel boundary.
            Area(shape).apply { add(Area(BasicStroke(2f).createStrokedShape(shape))) }.also {
                inputRegionKey = key
                inputRegionBase = it
            }
        }
        return AffineTransform.getTranslateInstance(island.x.toDouble(), island.y.toDouble()).createTransformedShape(padded)
    }
}