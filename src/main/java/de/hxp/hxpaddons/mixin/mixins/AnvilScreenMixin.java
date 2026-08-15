package de.hxp.hxpaddons.mixin.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.hxp.hxpaddons.features.impl.general.ModIdHider;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AnvilScreen.class)
public class AnvilScreenMixin {
    @WrapOperation(method = "slotChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
    public String slotChanged$getString(Component instance, Operation<String> original) {
        return ModIdHider.getString(instance);
    }

    @WrapOperation(method = "onNameChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
    public String onNameChanged$getString(Component instance, Operation<String> original) {
        return ModIdHider.getString(instance);
    }
}
