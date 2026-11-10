// SPDX-License-Identifier: GPL-3.0-only
package io.github.solitaire.spwisland.ui

/** Grow immediately; reclaim a materially oversized backing canvas only after a settled interval. */
internal class IslandCanvasCapacity(private val shrinkDelayNs: Long = 2_000_000_000L) {
    var width = 0
        private set
    var height = 0
        private set
    private var candidateWidth = 0
    private var candidateHeight = 0
    private var candidateSince: Long? = null

    fun update(requiredWidth: Int, requiredHeight: Int, now: Long, settled: Boolean) {
        val wantedWidth = requiredWidth.coerceAtLeast(1)
        val wantedHeight = requiredHeight.coerceAtLeast(1)
        if (wantedWidth > width || wantedHeight > height) {
            width = maxOf(width, wantedWidth)
            height = maxOf(height, wantedHeight)
            candidateSince = null
            return
        }
        val oversized = width - wantedWidth >= 64 && wantedWidth * 4L <= width * 3L ||
            height - wantedHeight >= 64 && wantedHeight * 4L <= height * 3L
        if (!settled || !oversized) {
            candidateSince = null
            return
        }
        if (candidateSince == null || candidateWidth != wantedWidth || candidateHeight != wantedHeight) {
            candidateWidth = wantedWidth
            candidateHeight = wantedHeight
            candidateSince = now
        } else if (now - candidateSince!! >= shrinkDelayNs) {
            width = wantedWidth
            height = wantedHeight
            candidateSince = null
        }
    }

    fun reset() {
        width = 0
        height = 0
        candidateSince = null
    }
}
