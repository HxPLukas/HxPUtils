package de.hxp.hxpaddons.mixin.accessors;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Window.class)
public interface WindowAccessor {
    @Invoker("setMode")
    void invokeSetMode();
}
