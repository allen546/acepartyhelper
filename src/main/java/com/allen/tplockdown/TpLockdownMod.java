package com.allen.tplockdown;

import com.allen.tplockdown.manager.TpLockManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.text.Text;

import java.util.Set;

public class TpLockdownMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TpLockManager.init();

        // Auto-run /party list with delay on connection
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            TpLockManager.onJoinWorld();
        });

        // Register Event listeners using Fabric API
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            return !TpLockManager.handleOutgoingCommand(command);
        });

        // Log incoming game messages and run check (isChat = false)
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (message != null) {
                TpLockManager.logIncomingMessage("GAME", message.getString());
                return !TpLockManager.handleIncomingMessage(message, false);
            }
            return true;
        });

        // Log incoming chat messages and run check (isChat = true)
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (message != null) {
                TpLockManager.logIncomingMessage("CHAT", message.getString());
                return !TpLockManager.handleIncomingMessage(message, true);
            }
            return true;
        });

        // Register /tplock client commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("tplock")
                .then(ClientCommandManager.literal("unlock")
                    .then(ClientCommandManager.argument("code", StringArgumentType.string())
                        .executes(context -> {
                            String code = StringArgumentType.getString(context, "code");
                            if (TpLockManager.unlock(code)) {
                                context.getSource().sendFeedback(Text.literal(
                                    "§a[TP-Lock] Unlocked successfully! Bypass active for 30 seconds."
                                ));
                            } else {
                                context.getSource().sendError(Text.literal(
                                    "§c[TP-Lock] Invalid TOTP code or replay code rejected!"
                                ));
                            }
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("party-refresh")
                    .executes(context -> {
                        TpLockManager.refreshParty();
                        context.getSource().sendFeedback(Text.literal(
                            "§a[TP-Lock] Party list auto-refresh triggered (output will be hidden)."
                        ));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("status")
                    .executes(context -> {
                        boolean bypass = TpLockManager.isBypassActive();
                        boolean autoAccept = TpLockManager.isAutoAcceptEnabled();
                        String partyName = TpLockManager.getActivePartyName();
                        String partyNameStr = (partyName != null) ? partyName : "NONE (Disabled - allowing all teleports)";
                        Set<String> scraped = TpLockManager.getScrapedParty();
                        String members = String.join(", ", scraped);
                        String lockStatus = bypass ? "§2UNLOCKED §7(" + TpLockManager.getBypassSecondsRemaining() + "s remaining)"
                                         : (partyName == null ? "§2DISABLED (allowing all)" : "§4LOCKED");

                        context.getSource().sendFeedback(Text.literal(
                            "§a[TP-Lock] Status: " + lockStatus + "\n" +
                            "§7Active Party: §f" + partyNameStr + "\n" +
                            "§7Members: §f" + (members.isEmpty() ? "None" : members) + "\n" +
                            "§7Auto-Accept: " + (autoAccept ? "§aON" : "§cOFF")
                        ));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("autoaccept")
                    .then(ClientCommandManager.literal("on")
                        .executes(context -> {
                            TpLockManager.setAutoAccept(true);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-accept §2ON §7— party member TPs will be accepted automatically."
                            ));
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("off")
                        .executes(context -> {
                            TpLockManager.setAutoAccept(false);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-accept §cOFF §7— you will handle TP requests manually."
                            ));
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("toggle")
                        .executes(context -> {
                            boolean now = TpLockManager.toggleAutoAccept();
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-accept toggled " + (now ? "§2ON" : "§cOFF") + "§a."
                            ));
                            return 1;
                        })
                    )
                )
            );
        });
    }
}
