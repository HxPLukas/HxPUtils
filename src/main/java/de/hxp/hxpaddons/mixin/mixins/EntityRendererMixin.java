package de.hxp.hxpaddons.mixin.mixins;

import de.hxp.hxpaddons.utils.render.EntityOutlineESP;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void onRender(T entity, Frustum frustum, double d, double e, double f, CallbackInfoReturnable<Boolean> cir) {
    }

    // Runs after vanilla's own outlineColor decision (Glowing effect/spectator highlight, team-colored)
    // so it only overrides entities we're actually tracking, leaving everything else untouched.
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void hxp$applyOutlineESP(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        int color = EntityOutlineESP.colorFor(entity);
        if (color != 0) state.outlineColor = color;
    }
}
