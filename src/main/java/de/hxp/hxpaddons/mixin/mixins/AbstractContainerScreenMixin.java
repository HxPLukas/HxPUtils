package de.hxp.hxpaddons.mixin.mixins;

import de.hxp.hxpaddons.events.GuiEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void onRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (new GuiEvent.Render((Screen) (Object) this, guiGraphics, mouseX, mouseY).postAndCatch()) ci.cancel();
    }

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void onDrawSlot(GuiGraphicsExtractor guiGraphics, Slot slot, int i, int j, CallbackInfo ci) {
        if (new GuiEvent.RenderSlot((Screen) (Object) this, guiGraphics, slot, i, j).postAndCatch()) ci.cancel();
    }

    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void onDrawSlotPost(GuiGraphicsExtractor guiGraphics, Slot slot, int i, int j, CallbackInfo ci) {
        new GuiEvent.RenderSlotPost((Screen) (Object) this, guiGraphics, slot, i, j).postAndCatch();
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClickedSlot(Slot slot, int i, int j, ContainerInput clickType, CallbackInfo ci) {
        if (new GuiEvent.SlotClick((Screen) (Object) this, i, j).postAndCatch()) ci.cancel();
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void onDrawMouseoverTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY, CallbackInfo ci) {
        if (new GuiEvent.DrawTooltip((Screen) (Object) this, context, mouseX, mouseY).postAndCatch()) ci.cancel();
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onRenderPost(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        new GuiEvent.RenderPost((Screen) (Object) this, guiGraphics, mouseX, mouseY).postAndCatch();
    }
}
