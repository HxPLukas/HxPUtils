package de.hxp.hxpaddons.mixin.mixins;

import de.hxp.hxpaddons.features.impl.render.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public class CameraMixin {

    // calculateFov computes the effective per-frame FOV fresh every frame (base FOV option + sprint/nausea/
    // underwater modifiers) as a raw float, unlike the persisted Options.fov Integer slider - overriding its
    // return value here lets Zoom go arbitrarily far in (fractional FOV) with no integer floor, and never
    // touches the real FOV option at all.
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void hxp$applyZoomFov(float partialTick, CallbackInfoReturnable<Float> cir) {
        Float override = Zoom.currentFovOverride();
        if (override != null) cir.setReturnValue(override);
    }
}
