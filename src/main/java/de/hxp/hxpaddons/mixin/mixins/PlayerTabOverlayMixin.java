package de.hxp.hxpaddons.mixin.mixins;

import de.hxp.hxpaddons.features.impl.general.NameChanger;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Inject(
            method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void hxp$applyNameChanger(PlayerInfo playerInfo, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(NameChanger.applyIfEnabled(cir.getReturnValue()));
    }
}
