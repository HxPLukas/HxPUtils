package de.hxp.hxpaddons.mixin.mixins;

import de.hxp.hxpaddons.events.ScreenEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void onCharTyped(long window, CharacterEvent event, CallbackInfo ci) {
        var screen = Minecraft.getInstance().screen;
        if (screen != null && new ScreenEvent.CharTyped(screen, event.codepointAsString()).postAndCatch()) {
            ci.cancel();
        }
    }
}
