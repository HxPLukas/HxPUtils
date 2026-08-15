package de.hxp.hxpaddons.mixin.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.hxp.hxpaddons.features.impl.general.SmoothWorldLoading;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin_SmoothWorldLoading {

    // Skip the LevelLoadingScreen when startWaitingForNewLevel tries to show it.
    // Also clears whatever screen was previously showing (e.g. ServerReconfigScreen).
    @WrapOperation(
            method = "startWaitingForNewLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V")
    )
    private void hxp$skipLevelLoadingScreen(Minecraft minecraft, Screen screen, Operation<Void> operation) {
        if (SmoothWorldLoading.INSTANCE.getEnabled()) {
            minecraft.setScreen(null);
        } else {
            operation.call(minecraft, screen);
        }
    }
}
