package de.hxp.hxpaddons.mixin.accessors;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> getTrimmedMessages();

    @Accessor("allMessages")
    List<GuiMessage> getAllMessages();

    @Accessor("chatScrollbarPos")
    int getChatScrollbarPos();

    @Accessor("chatScrollbarPos")
    void setChatScrollbarPos(int value);

    @Invoker("refreshTrimmedMessages")
    void invokeRefreshTrimmedMessages();
}
