package de.hxp.hxpaddons.mixin.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.hxp.hxpaddons.events.BlockInteractEvent;
import de.hxp.hxpaddons.events.EntityInteractEvent;
import de.hxp.hxpaddons.features.ModuleManager;
import de.hxp.hxpaddons.features.impl.general.SmoothTransitionScreen;
import de.hxp.hxpaddons.features.impl.general.SmoothWorldLoading;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    @Nullable
    public HitResult hitResult;

    @Inject(method = "destroy", at = @At("HEAD"))
    private void onDestroy(CallbackInfo ci) {
        ModuleManager.INSTANCE.saveConfigurations();
    }

    // Skip setScreenAndShow(ServerReconfigScreen) inside clearClientLevel when SmoothWorldLoading
    // is enabled. The world/player teardown still runs; only the screen-set call is suppressed.
    // Doing this in a Minecraft mixin avoids the clientLevelTeardownInProgress crash that occurs
    // when setScreen(null) is called while the teardown flag is active.
    @WrapOperation(
            method = "clearClientLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V")
    )
    private void hxp$suppressReconfigScreen(Minecraft instance, Screen screen, Operation<Void> operation) {
        if (SmoothWorldLoading.INSTANCE.getEnabled() && screen instanceof ServerReconfigScreen) {
            // Replace with a blank transparent screen instead of skipping entirely.
            // A non-null screen is required during the config phase so the game loop
            // skips handleKeybinds (which would NPE when player is null).
            operation.call(instance, new SmoothTransitionScreen());
        } else {
            operation.call(instance, screen);
        }
    }

    @Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"), cancellable = true)
    private void cancelBlockUse(CallbackInfo ci) {
        if (!(this.hitResult instanceof BlockHitResult blockHitResult)) return;
        if ((new BlockInteractEvent(blockHitResult.getBlockPos()).postAndCatch())) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"), cancellable = true)
    private void cancelEntityUse(CallbackInfo ci) {
        if (!(this.hitResult instanceof EntityHitResult entityHitResult)) return;
        if (new EntityInteractEvent(entityHitResult.getLocation(), entityHitResult.getEntity()).postAndCatch()) ci.cancel();
    }
}
