package de.hxp.hxpaddons.mixin.mixins;

import com.mojang.blaze3d.platform.InputConstants;
import de.hxp.hxpaddons.HxPMod;
import de.hxp.hxpaddons.features.impl.render.Zoom;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Shadow
    private double xpos;
    @Shadow
    private double ypos;
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Unique
    private double beforeX;
    @Unique
    private double beforeY;

    @Inject(method = "grabMouse", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;xpos:D", ordinal = 0, opcode = Opcodes.PUTFIELD))
    private void odin$lockXPos(CallbackInfo ci) {
        this.beforeX = this.xpos;
        this.beforeY = this.ypos;
    }

    @Inject(method = "releaseMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getWindow()Lcom/mojang/blaze3d/platform/Window;"))
    private void odin$correctCursorPosition(CallbackInfo ci) {
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void hxp$handleZoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (Zoom.onScroll(yOffset)) {
            ci.cancel();
        }
    }

    // turnPlayer reads accumulatedDX/DY directly off this instance every time it needs them (never caches
    // them into a local first), and they're unconditionally zeroed out again right after this method returns
    // (see handleAccumulatedMovement) regardless of what we do to them here - so scaling them at HEAD, before
    // any of turnPlayer's own reads, correctly and safely reduces the effective mouse delta this whole turn
    // uses without needing to touch anything downstream.
    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void hxp$scaleZoomSensitivity(double partialTick, CallbackInfo ci) {
        double scale = Zoom.sensitivityMultiplier();
        if (scale != 1.0) {
            this.accumulatedDX *= scale;
            this.accumulatedDY *= scale;
        }
    }
}
