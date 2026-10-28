// SPDX-License-Identifier: GPL-3.0-only
package io.github.solitaire.spwisland.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser.MONITORINFO
import java.awt.Window

/** Windows-only capabilities. All access is from the Swing event thread. */
class WindowsOverlay {
    companion object {
        private const val ERROR_INVALID_WINDOW_HANDLE = 1400
        // Int overloads zero-extend -1 on x64, producing an invalid HWND instead of HWND_TOPMOST.
        private val HWND_TOPMOST = HWND(Pointer.createConstant(-1L))
        private val HWND_NOTOPMOST = HWND(Pointer.createConstant(-2L))
    }
    private val user32 = if (Platform.isWindows()) User32.INSTANCE else null
    private var lastTopmostPeer: HWND? = null
    private var lastForeground: HWND? = null
    private var nextTopmostRefresh = 0L
    val supportsClickThrough: Boolean get() = user32 != null
    val supportsStableTranslucentCanvas: Boolean get() = user32 != null
    private fun handle(window: Window) = HWND(Native.getWindowPointer(window))
    private fun existingHandle(window: Window, api: User32): HWND? {
        val pointer = Native.getWindowPointer(window) ?: return null
        if (Pointer.nativeValue(pointer) == 0L) return null
        val hwnd = HWND(pointer)
        return hwnd.takeIf { api.IsWindow(it) }
    }

    fun reinforceTopmost(window: Window, refreshIntervalNs: Long): Boolean {
        val api = user32 ?: return true
        if (!window.isDisplayable || !window.isVisible) return false
        val hwnd = existingHandle(window, api) ?: run {
            lastTopmostPeer = null
            return false
        }
        val now = System.nanoTime()
        val foreground = api.GetForegroundWindow()
        val peerChanged = hwnd != lastTopmostPeer
        if (peerChanged) lastTopmostPeer = hwnd
        val wasTopmost = api.GetWindowLong(hwnd, -20) and 8 != 0
        val wasVisible = api.IsWindowVisible(hwnd)
        val wasMinimized = api.GetWindowLong(hwnd, -16) and 0x20000000 != 0
        if (!peerChanged && wasTopmost && wasVisible && !wasMinimized &&
            foreground == lastForeground && now < nextTopmostRefresh) return true

        // Recover only a policy-visible island, without activating another application.
        if (wasMinimized) api.ShowWindow(hwnd, 4) // SW_SHOWNOACTIVATE
        val flags = 0x0001 or 0x0002 or 0x0010 or 0x0200 or
            if (!wasVisible || wasMinimized) 0x0040 else 0 // SWP_SHOWWINDOW
        Native.setLastError(0)
        val requested = api.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, flags)
        val errorCode = if (requested) 0 else Native.getLastError()
        val exists = api.IsWindow(hwnd)
        val isTopmost = exists && api.GetWindowLong(hwnd, -20) and 8 != 0
        val isVisible = exists && api.IsWindowVisible(hwnd)
        val isMinimized = exists && api.GetWindowLong(hwnd, -16) and 0x20000000 != 0
        if (!exists) {
            lastTopmostPeer = null
            return false
        }
        if (!requested || !isTopmost || !isVisible || isMinimized) {
            nextTopmostRefresh = 0L
            val detail = when {
                errorCode == ERROR_INVALID_WINDOW_HANDLE -> "目标窗口仍有效；置顶请求句柄无效"
                !requested -> "系统拒绝置顶请求"
                else -> "请求成功但原生状态校验未通过"
            }
            error("无法维持词岛置顶：$errorCode（$detail）")
        }
        lastForeground = foreground
        nextTopmostRefresh = now + refreshIntervalNs
        return true
    }

    /** Force a fresh native topmost transition without touching persistent settings. */
    fun forceTopmost(window: Window) {
        val api = user32 ?: return
        if (!window.isDisplayable || !window.isVisible) return
        val hwnd = existingHandle(window, api) ?: return
        val flags = 0x0001 or 0x0002 or 0x0010 or 0x0200 // NOSIZE | NOMOVE | NOACTIVATE | NOOWNERZORDER
        fun place(target: HWND) {
            Native.setLastError(0)
            if (!api.SetWindowPos(hwnd, target, 0, 0, 0, 0, flags)) {
                error("无法重建词岛置顶状态：${Native.getLastError()}")
            }
        }
        // Some AWT peers ignore a repeated HWND_TOPMOST request. Leave the topmost band
        // first so the following request performs a real WS_EX_TOPMOST transition.
        place(HWND_NOTOPMOST)
        place(HWND_TOPMOST)
        if (!api.IsWindow(hwnd) || !api.IsWindowVisible(hwnd) || api.GetWindowLong(hwnd, -20) and 8 == 0) {
            error("词岛置顶状态未生效")
        }
    }

    fun clickThrough(window: Window, enabled: Boolean) {
        val api = user32 ?: return
        if (!window.isDisplayable) return
        val hwnd = handle(window)
        val old = api.GetWindowLong(hwnd, -20) // GWL_EXSTYLE
        val flags = 0x20 or 0x80000 // WS_EX_TRANSPARENT | WS_EX_LAYERED
        // Keep LAYERED: AWT uses it for per-pixel transparency even when unlocked.
        val next = if (enabled) old or flags else old and 0x20.inv()
        if (next != old) {
            Native.setLastError(0)
            val result = api.SetWindowLong(hwnd, -20, next)
            check(result != 0 || Native.getLastError() == 0) { "无法更新词岛鼠标穿透：${Native.getLastError()}" }
        }
    }

    fun foregroundIsFullscreen(window: Window): Boolean {
        val api = user32 ?: return false
        if (!window.isDisplayable) return false
        val foreground = api.GetForegroundWindow() ?: return false
        if (foreground == handle(window)) return false
        val className = CharArray(256)
        api.GetClassName(foreground, className, className.size)
        if (Native.toString(className) in setOf("Progman", "WorkerW", "Shell_TrayWnd")) return false
        val monitor = api.MonitorFromWindow(foreground, 2) ?: return false
        // Use native monitor coordinates on both sides, avoiding mixed-DPI coordinate comparisons.
        if (monitor != api.MonitorFromWindow(handle(window), 2)) return false
        val info = MONITORINFO()
        if (!api.GetMonitorInfo(monitor, info).booleanValue()) return false
        val rect = RECT()
        if (!api.GetWindowRect(foreground, rect)) return false
        val screen = info.rcMonitor
        return rect.left <= screen.left && rect.top <= screen.top &&
            rect.right >= screen.right && rect.bottom >= screen.bottom
    }
}
