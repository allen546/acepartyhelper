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

        // Block outgoing commands
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            return !TpLockManager.handleOutgoingCommand(command);
        });

        // Log and filter incoming game (system) messages
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (message != null) {
                TpLockManager.logIncomingMessage("GAME", message.getString());
                return !TpLockManager.handleIncomingMessage(message, false);
            }
            return true;
        });

        // Log and filter incoming player chat messages
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
                // /tplock help
                .then(ClientCommandManager.literal("help")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(
                            "§6§l[TP-Lock] Commands:\n" +
                            "§e/tplock help §7— this message\n" +
                            "§e/tplock status §7— show lock/party/autoaccept status\n" +
                            "§e/tplock unlock <code> §7— TOTP bypass (30s)\n" +
                            "§e/tplock party-refresh §7— re-query /party list\n" +
                            "§e/tplock autoaccept tpa on|off|toggle §7— auto-accept /tpa requests from party\n" +
                            "§e/tplock autoaccept tpahere on|off|toggle §7— auto-accept /tpahere requests from party\n" +
                            "§e/tplock autoaccept status §7— show autoaccept states\n" +
                            "§e/tplock settings reject-method set timeout|reject §7— on blocked TP: do nothing or auto-deny"
                        ));
                        return 1;
                    })
                )
                // /tplock unlock <code>
                .then(ClientCommandManager.literal("unlock")
                    .then(ClientCommandManager.argument("code", StringArgumentType.string())
                        .executes(context -> {
                            String code = StringArgumentType.getString(context, "code");
                            if (TpLockManager.unlock(code)) {
                                context.getSource().sendFeedback(Text.literal(
                                    "§a[TP-Lock] Unlocked! Bypass active for 30 seconds."
                                ));
                            } else {
                                context.getSource().sendError(Text.literal(
                                    "§c[TP-Lock] Invalid TOTP code or replay rejected!"
                                ));
                            }
                            return 1;
                        })
                    )
                )
                // /tplock party-refresh
                .then(ClientCommandManager.literal("party-refresh")
                    .executes(context -> {
                        TpLockManager.refreshParty();
                        context.getSource().sendFeedback(Text.literal(
                            "§a[TP-Lock] Party list refresh triggered (output hidden)."
                        ));
                        return 1;
                    })
                )
                // /tplock status
                .then(ClientCommandManager.literal("status")
                    .executes(context -> {
                        boolean bypass = TpLockManager.isBypassActive();
                        String partyName = TpLockManager.getActivePartyName();
                        String partyNameStr = partyName != null ? partyName : "NONE (allowing all TPs)";
                        Set<String> scraped = TpLockManager.getScrapedParty();
                        String members = scraped.isEmpty() ? "None" : String.join(", ", scraped);
                        String lockStatus = bypass
                            ? "§2UNLOCKED §7(" + TpLockManager.getBypassSecondsRemaining() + "s remaining)"
                            : (partyName == null ? "§2DISABLED (allowing all)" : "§4LOCKED");

                        context.getSource().sendFeedback(Text.literal(
                            "§a[TP-Lock] Status: " + lockStatus + "\n" +
                            "§7Party: §f" + partyNameStr + "\n" +
                            "§7Members: §f" + members + "\n" +
                            "§7Auto-Accept TPA: " + (TpLockManager.isAutoAcceptTpa() ? "§aON" : "§cOFF") + "\n" +
                            "§7Auto-Accept TPAHERE: " + (TpLockManager.isAutoAcceptTpaHere() ? "§aON" : "§cOFF") + "\n" +
                            "§7Reject Method: §f" + TpLockManager.getRejectMethod()
                        ));
                        return 1;
                    })
                )
                // /tplock autoaccept ...
                .then(ClientCommandManager.literal("autoaccept")
                    // /tplock autoaccept status
                    .then(ClientCommandManager.literal("status")
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-Accept Status:\n" +
                                "§7  TPA (they tp to you): " + (TpLockManager.isAutoAcceptTpa() ? "§aON" : "§cOFF") + "\n" +
                                "§7  TPAHERE (you tp to them): " + (TpLockManager.isAutoAcceptTpaHere() ? "§aON" : "§cOFF")
                            ));
                            return 1;
                        })
                    )
                    // /tplock autoaccept tpa on|off|toggle
                    .then(ClientCommandManager.literal("tpa")
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            TpLockManager.setAutoAcceptTpa(true);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-Accept TPA §2ON §7— /tpa requests from party auto-accepted."
                            ));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            TpLockManager.setAutoAcceptTpa(false);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-Accept TPA §cOFF§7."
                            ));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(context -> {
                            boolean now = TpLockManager.toggleAutoAcceptTpa();
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-Accept TPA toggled " + (now ? "§2ON" : "§cOFF") + "§a."
                            ));
                            return 1;
                        }))
                    )
                    // /tplock autoaccept tpahere on|off|toggle
                    .then(ClientCommandManager.literal("tpahere")
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            TpLockManager.setAutoAcceptTpaHere(true);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-Accept TPAHERE §2ON §7— /tpahere requests from party auto-accepted."
                            ));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            TpLockManager.setAutoAcceptTpaHere(false);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-Accept TPAHERE §cOFF§7."
                            ));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(context -> {
                            boolean now = TpLockManager.toggleAutoAcceptTpaHere();
                            context.getSource().sendFeedback(Text.literal(
                                "§a[TP-Lock] Auto-Accept TPAHERE toggled " + (now ? "§2ON" : "§cOFF") + "§a."
                            ));
                            return 1;
                        }))
                    )
                )
                // /tplock settings <option> set <value>
                .then(ClientCommandManager.literal("settings")
                    .then(ClientCommandManager.literal("reject-method")
                        .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.literal("timeout")
                                .executes(context -> {
                                    TpLockManager.setRejectMethod("timeout");
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[TP-Lock] reject-method = §ftimeout §7— blocked TPs will silently expire."
                                    ));
                                    return 1;
                                })
                            )
                            .then(ClientCommandManager.literal("reject")
                                .executes(context -> {
                                    TpLockManager.setRejectMethod("reject");
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[TP-Lock] reject-method = §freject §7— blocked TPs will be auto-denied."
                                    ));
                                    return 1;
                                })
                            )
                        )
                    )
                )
            );
        });
    }
}
