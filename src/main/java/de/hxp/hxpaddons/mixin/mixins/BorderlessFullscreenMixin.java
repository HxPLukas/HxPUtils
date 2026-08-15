package de.hxp.hxpaddons.mixin.mixins;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.blaze3d.systems.GpuBackend;
import de.hxp.hxpaddons.features.impl.render.BorderlessFullscreen;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class BorderlessFullscreenMixin {

    // Mirror MC's saved windowed fields so vanilla restore works correctly after module is disabled
    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;

    private int bfs_savedX = 100, bfs_savedY = 100, bfs_savedW = 854, bfs_savedH = 480;

    @Inject(
        method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;Ljava/lang/String;Lcom/mojang/blaze3d/systems/GpuBackend;)V",
        at = @At("TAIL")
    )
    private void centerOnCreate(WindowEventHandler eventHandler, DisplayData displayData,
                                 String videoMode, String title, GpuBackend gpuBackend, CallbackInfo ci) {
        long handle = ((Window)(Object)this).handle();
        if (GLFW.glfwGetWindowMonitor(handle) != 0L) return; // fullscreen – no need to center
        long monitor = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
        if (vm == null) return;
        int[] mx = new int[1], my = new int[1];
        GLFW.glfwGetMonitorPos(monitor, mx, my);
        int[] ww = new int[1], wh = new int[1];
        GLFW.glfwGetWindowSize(handle, ww, wh);
        GLFW.glfwSetWindowPos(handle, mx[0] + (vm.width() - ww[0]) / 2, my[0] + (vm.height() - wh[0]) / 2);
    }

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void onSetMode(CallbackInfo ci) {
        Window window = (Window)(Object)this;
        long handle = window.handle();
        boolean isFullscreen = window.isFullscreen();
        boolean glfwFullscreen = GLFW.glfwGetWindowMonitor(handle) != 0L;
        // Our borderless: no GLFW exclusive, window has no decoration
        boolean inBorderless = !glfwFullscreen && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED) == 0;

        if (BorderlessFullscreen.INSTANCE.getEnabled()) {
            ci.cancel();

            if (isFullscreen) {
                if (!inBorderless) {
                    if (glfwFullscreen) {
                        // Coming from exclusive fullscreen: use MC's remembered windowed position
                        // (window.getX/Y() returns the monitor origin in exclusive mode, not the real windowed pos)
                        bfs_savedX = windowedX;
                        bfs_savedY = windowedY;
                        bfs_savedW = windowedWidth > 0 ? windowedWidth : 854;
                        bfs_savedH = windowedHeight > 0 ? windowedHeight : 480;
                        GLFW.glfwSetWindowMonitor(handle, 0L, bfs_savedX, bfs_savedY, bfs_savedW, bfs_savedH, 0);
                    } else {
                        // Coming from true windowed mode – save current position and sync to MC's fields
                        bfs_savedX = window.getX();
                        bfs_savedY = window.getY();
                        bfs_savedW = window.getWidth();
                        bfs_savedH = window.getHeight();
                        windowedX = bfs_savedX;
                        windowedY = bfs_savedY;
                        windowedWidth = bfs_savedW;
                        windowedHeight = bfs_savedH;
                    }
                }
                applyBorderless(handle);
            } else if (inBorderless) {
                // Only restore windowed when actually exiting FROM our borderless mode
                applyWindowed(handle);
            }
            // else: windowed→windowed while module enabled – do nothing, window is already windowed

        } else if (inBorderless && isFullscreen) {
            // Module was disabled while in our borderless mode – switch to GLFW exclusive fullscreen
            ci.cancel();
            windowedX = bfs_savedX;
            windowedY = bfs_savedY;
            windowedWidth = bfs_savedW;
            windowedHeight = bfs_savedH;
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, 1);
            long[] monitor = getMonitorForWindow(handle);
            GLFW.glfwSetWindowMonitor(handle, monitor[0], (int) monitor[1], (int) monitor[2],
                    (int) monitor[3], (int) monitor[4], (int) monitor[5]);
        }
        // Otherwise: vanilla setMode() runs normally (windowed↔exclusive when module is disabled)
    }

    private void applyBorderless(long handle) {
        long[] monitor = getMonitorForWindow(handle);
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, 0);
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, 0);
        GLFW.glfwSetWindowPos(handle, (int) monitor[1], (int) monitor[2]);
        GLFW.glfwSetWindowSize(handle, (int) monitor[3], (int) monitor[4]);
    }

    private void applyWindowed(long handle) {
        if (GLFW.glfwGetWindowMonitor(handle) != 0L) {
            GLFW.glfwSetWindowMonitor(handle, 0L, bfs_savedX, bfs_savedY, bfs_savedW, bfs_savedH, 0);
        }
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, 1);
        GLFW.glfwSetWindowPos(handle, bfs_savedX, bfs_savedY);
        GLFW.glfwSetWindowSize(handle, bfs_savedW, bfs_savedH);
    }

    // Returns [monitorHandle, posX, posY, width, height, refreshRate]
    private long[] getMonitorForWindow(long handle) {
        int[] wx = new int[1], wy = new int[1], ww = new int[1], wh = new int[1];
        GLFW.glfwGetWindowPos(handle, wx, wy);
        GLFW.glfwGetWindowSize(handle, ww, wh);
        int cx = wx[0] + ww[0] / 2;
        int cy = wy[0] + wh[0] / 2;

        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors != null) {
            for (int i = monitors.position(); i < monitors.limit(); i++) {
                long mon = monitors.get(i);
                GLFWVidMode vm = GLFW.glfwGetVideoMode(mon);
                if (vm == null) continue;
                int[] mx = new int[1], my = new int[1];
                GLFW.glfwGetMonitorPos(mon, mx, my);
                if (cx >= mx[0] && cy >= my[0] && cx < mx[0] + vm.width() && cy < my[0] + vm.height()) {
                    return new long[]{mon, mx[0], my[0], vm.width(), vm.height(), vm.refreshRate()};
                }
            }
        }

        long primary = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode vm = GLFW.glfwGetVideoMode(primary);
        int[] mx = new int[1], my = new int[1];
        GLFW.glfwGetMonitorPos(primary, mx, my);
        return vm != null
                ? new long[]{primary, mx[0], my[0], vm.width(), vm.height(), vm.refreshRate()}
                : new long[]{primary, 0, 0, 1920, 1080, 60};
    }
}
