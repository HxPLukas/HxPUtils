package de.hxp.hxpaddons.mixin.accessors;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("xLast")
    double getServerX();

    @Accessor("yLast")
    double getServerY();

    @Accessor("zLast")
    double getServerZ();

    @Accessor("yRotLast")
    float getServerYaw();

    @Accessor("xRotLast")
    float getServerPitch();

    @Accessor("yRotLast")
    void setServerYaw(float yaw);

    @Accessor("xRotLast")
    void setServerPitch(float pitch);

    @Accessor("crouching")
    boolean isCrouchingServer();
}
