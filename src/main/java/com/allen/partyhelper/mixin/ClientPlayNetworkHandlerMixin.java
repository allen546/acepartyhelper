package com.allen.partyhelper.mixin;

import com.allen.partyhelper.manager.PartyHelperManager;
import com.allen.partyhelper.PartyHelperMod;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "sendChatMessage(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void onSendChatMessage(String content, CallbackInfo ci) {
        // Prevent infinite loops when sending our own command/chat packets
        if (PartyHelperMod.processingMessage.get()) {
            return;
        }

        if (PartyHelperManager.getForcePartyChat()) {
            ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
            PartyHelperMod.processingMessage.set(true);
            try {
                handler.sendChatCommand("pc " + content);
            } finally {
                PartyHelperMod.processingMessage.set(false);
            }
            ci.cancel(); // Prevent the original chat packet from being sent to the server
        }
    }
}
