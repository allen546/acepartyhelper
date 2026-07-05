package com.allen.tplockdown;

import com.allen.tplockdown.manager.TpLockManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.text.Text;
import java.util.Set;

public class TpLockdownMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TpLockManager.init();

        // Register Event listeners using Fabric API
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            // Note: Fabric's event receives command WITHOUT leading slash
            return !TpLockManager.handleOutgoingCommand(command);
        });

        // Log incoming game messages and run check
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (message != null) {
                TpLockManager.logIncomingMessage("GAME", message.getString());
                return !TpLockManager.handleIncomingMessage(message);
            }
            return true;
        });

        // Log incoming chat messages and run check
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (message != null) {
                TpLockManager.logIncomingMessage("CHAT", message.getString());
                return !TpLockManager.handleIncomingMessage(message);
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
                .then(ClientCommandManager.literal("party")
                    .then(ClientCommandManager.literal("add")
                        .then(ClientCommandManager.argument("player", StringArgumentType.string())
                            .executes(context -> {
                                String player = StringArgumentType.getString(context, "player");
                                TpLockManager.addManualPartyMember(player);
                                context.getSource().sendFeedback(Text.literal(
                                    "§a[TP-Lock] Added player to allowed party: §e" + player
                                ));
                                return 1;
                            })
                        )
                    )
                    .then(ClientCommandManager.literal("remove")
                        .then(ClientCommandManager.argument("player", StringArgumentType.string())
                            .executes(context -> {
                                String player = StringArgumentType.getString(context, "player");
                                TpLockManager.removeManualPartyMember(player);
                                context.getSource().sendFeedback(Text.literal(
                                    "§a[TP-Lock] Removed player from allowed party: §e" + player
                                ));
                                return 1;
                            })
                        )
                    )
                    .then(ClientCommandManager.literal("clear")
                        .executes(context -> {
                            TpLockManager.clearManualParty();
                            TpLockManager.clearScrapedParty();
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Cleared all party lists."
                            ));
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("list")
                        .executes(context -> {
                            Set<String> manual = TpLockManager.getManualParty();
                            Set<String> scraped = TpLockManager.getScrapedParty();
                            context.getSource().sendFeedback(Text.literal(
                                "§6[TP-Lock] Allowed Players:\n" +
                                "§7Manual: §f" + String.join(", ", manual) + "\n" +
                                "§7Scraped (Party Chat): §f" + String.join(", ", scraped)
                            ));
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("status")
                    .executes(context -> {
                        boolean bypass = TpLockManager.isBypassActive();
                        if (bypass) {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Status: §2UNLOCKED §7(" + TpLockManager.getBypassSecondsRemaining() + "s remaining)"
                            ));
                        } else {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Status: §4LOCKED §7(Enforcing party-only teleportation)"
                            ));
                        }
                        return 1;
                    })
                )
            );
        });
    }
}
