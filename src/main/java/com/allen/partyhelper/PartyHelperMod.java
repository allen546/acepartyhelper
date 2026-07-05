package com.allen.partyhelper;

import com.allen.partyhelper.manager.PartyHelperManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.text.Text;

import java.util.Set;

public class PartyHelperMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PartyHelperManager.init();

        // Auto-run /party list with delay on connection
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PartyHelperManager.onJoinWorld();
        });

        // Block outgoing commands and handle /global chat override
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (PartyHelperManager.getForcePartyChat()) {
                String cmdTrim = command.trim();
                if (cmdTrim.startsWith("global ") || cmdTrim.equals("global")) {
                    net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                    if (client != null && client.getNetworkHandler() != null) {
                        String message = cmdTrim.length() > 7 ? cmdTrim.substring(7).trim() : "";
                        if (!message.isEmpty()) {
                            client.getNetworkHandler().sendChatMessage(message);
                        } else {
                            if (client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                                client.inGameHud.getChatHud().addMessage(
                                    Text.literal("§c[PartyHelper] Usage: /global <message> to send a server-wide chat message.")
                                );
                            }
                        }
                    }
                    return false; // Suppress command execution
                }
            }
            return !PartyHelperManager.handleOutgoingCommand(command);
        });

        // Force routing normal chat messages to party chat (/pc)
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (PartyHelperManager.getForcePartyChat()) {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client != null && client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatMessage("/pc " + message);
                }
                return false; // Suppress original chat message
            }
            return true;
        });

        // Log and filter incoming game (system) messages
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (message != null) {
                PartyHelperManager.logIncomingMessage("GAME", message.getString());
                return !PartyHelperManager.handleIncomingMessage(message, false);
            }
            return true;
        });

        // Log and filter incoming player chat messages
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (message != null) {
                PartyHelperManager.logIncomingMessage("CHAT", message.getString());
                return !PartyHelperManager.handleIncomingMessage(message, true);
            }
            return true;
        });

        // Register /ph and /partyhelper client commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var phCommand = ClientCommandManager.literal("ph")
                // /ph help
                .then(ClientCommandManager.literal("help")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(
                            "§6§l[PartyHelper] Commands:\n" +
                            "§e/ph help §7— this message\n" +
                            "§e/ph status §7— show lock/party/autoaccept status\n" +
                            "§e/ph unlock <code> §7— TOTP bypass (30s)\n" +
                            "§e/ph party-refresh §7— re-query /party list\n" +
                            "§e/ph autoaccept tpa on|off|toggle §7— auto-accept /tpa requests from party\n" +
                            "§e/ph autoaccept tpahere on|off|toggle §7— auto-accept /tpahere requests from party\n" +
                            "§e/ph autoaccept status §7— show autoaccept states\n" +
                            "§e/ph settings reject-method set timeout|reject §7— on blocked TP: do nothing or auto-deny\n" +
                            "§e/ph settings block-when-no-party set true|false §7— block all TPs when not in a party\n" +
                            "§e/ph settings log-autoaccept set true|false §7— notify in chat on auto-accept"
                        ));
                        return 1;
                    })
                )
                // /ph unlock <code>
                .then(ClientCommandManager.literal("unlock")
                    .then(ClientCommandManager.argument("code", StringArgumentType.string())
                        .executes(context -> {
                            String code = StringArgumentType.getString(context, "code");
                            if (PartyHelperManager.unlock(code)) {
                                context.getSource().sendFeedback(Text.literal(
                                    "§a[PartyHelper] Unlocked! Bypass active for 30 seconds."
                                ));
                            } else {
                                context.getSource().sendError(Text.literal(
                                    "§c[PartyHelper] Invalid TOTP code or replay rejected!"
                                ));
                            }
                            return 1;
                        })
                    )
                )
                // /ph party-refresh
                .then(ClientCommandManager.literal("party-refresh")
                    .executes(context -> {
                        PartyHelperManager.refreshParty();
                        context.getSource().sendFeedback(Text.literal(
                            "§a[PartyHelper] Party list refresh triggered (output hidden)."
                        ));
                        return 1;
                    })
                )
                // /ph status
                .then(ClientCommandManager.literal("status")
                    .executes(context -> {
                        boolean bypass = PartyHelperManager.isBypassActive();
                        String partyName = PartyHelperManager.getActivePartyName();
                        String partyNameStr = partyName != null ? partyName : "NONE" + (PartyHelperManager.getBlockWhenNoParty() ? " (Blocking all TPs)" : " (Allowing all TPs)");
                        Set<String> scraped = PartyHelperManager.getScrapedParty();
                        String members = scraped.isEmpty() ? "None" : String.join(", ", scraped);
                        String lockStatus = bypass
                            ? "§2UNLOCKED §7(" + PartyHelperManager.getBypassSecondsRemaining() + "s remaining)"
                            : (partyName == null ? (PartyHelperManager.getBlockWhenNoParty() ? "§4LOCKED (No Party)" : "§2DISABLED (allowing all)") : "§4LOCKED");

                        context.getSource().sendFeedback(Text.literal(
                            "§a[PartyHelper] Status: " + lockStatus + "\n" +
                            "§7Party: §f" + partyNameStr + "\n" +
                            "§7Members: §f" + members + "\n" +
                            "§7Auto-Accept TPA: " + (PartyHelperManager.isAutoAcceptTpa() ? "§aON" : "§cOFF") + "\n" +
                            "§7Auto-Accept TPAHERE: " + (PartyHelperManager.isAutoAcceptTpaHere() ? "§aON" : "§cOFF") + "\n" +
                            "§7Reject Method: §f" + PartyHelperManager.getRejectMethod() + "\n" +
                            "§7Block When Solo: " + (PartyHelperManager.getBlockWhenNoParty() ? "§aYES" : "§cNO") + "\n" +
                            "§7Log Auto-Accept: " + (PartyHelperManager.getLogAutoAccept() ? "§aYES" : "§cNO")
                        ));
                        return 1;
                    })
                )
                // /ph autoaccept ...
                .then(ClientCommandManager.literal("autoaccept")
                    // /ph autoaccept status
                    .then(ClientCommandManager.literal("status")
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] Auto-Accept Status:\n" +
                                "§7  TPA (they tp to you): " + (PartyHelperManager.isAutoAcceptTpa() ? "§aON" : "§cOFF") + "\n" +
                                "§7  TPAHERE (you tp to them): " + (PartyHelperManager.isAutoAcceptTpaHere() ? "§aON" : "§cOFF")
                            ));
                            return 1;
                        })
                    )
                    // /ph autoaccept tpa on|off|toggle
                    .then(ClientCommandManager.literal("tpa")
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            PartyHelperManager.setAutoAcceptTpa(true);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] Auto-Accept TPA §2ON §7— /tpa requests from party auto-accepted."
                            ));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            PartyHelperManager.setAutoAcceptTpa(false);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] Auto-Accept TPA §cOFF§7."
                            ));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(context -> {
                            boolean now = PartyHelperManager.toggleAutoAcceptTpa();
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] Auto-Accept TPA toggled " + (now ? "§2ON" : "§cOFF") + "§a."
                            ));
                            return 1;
                        }))
                    )
                    // /ph autoaccept tpahere on|off|toggle
                    .then(ClientCommandManager.literal("tpahere")
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            PartyHelperManager.setAutoAcceptTpaHere(true);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] Auto-Accept TPAHERE §2ON §7— /tpahere requests from party auto-accepted."
                            ));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            PartyHelperManager.setAutoAcceptTpaHere(false);
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] Auto-Accept TPAHERE §cOFF§7."
                            ));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(context -> {
                            boolean now = PartyHelperManager.toggleAutoAcceptTpaHere();
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] Auto-Accept TPAHERE toggled " + (now ? "§2ON" : "§cOFF") + "§a."
                            ));
                            return 1;
                        }))
                    )
                )
                // /ph settings <option> set <value>
                .then(ClientCommandManager.literal("settings")
                    // reject-method
                    .then(ClientCommandManager.literal("reject-method")
                        .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.literal("timeout")
                                .executes(context -> {
                                    PartyHelperManager.setRejectMethod("timeout");
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] reject-method = §ftimeout §7— blocked TPs will silently expire."
                                    ));
                                    return 1;
                                })
                            )
                            .then(ClientCommandManager.literal("reject")
                                .executes(context -> {
                                    PartyHelperManager.setRejectMethod("reject");
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] reject-method = §freject §7— blocked TPs will be auto-denied."
                                    ));
                                    return 1;
                                })
                            )
                        )
                    )
                    // block-when-no-party
                    .then(ClientCommandManager.literal("block-when-no-party")
                        .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.literal("true")
                                .executes(context -> {
                                    PartyHelperManager.setBlockWhenNoParty(true);
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] block-when-no-party = §ftrue §7— all TPs blocked when not in a party."
                                    ));
                                    return 1;
                                })
                            )
                            .then(ClientCommandManager.literal("false")
                                .executes(context -> {
                                    PartyHelperManager.setBlockWhenNoParty(false);
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] block-when-no-party = §ffalse §7— all TPs allowed when not in a party."
                                    ));
                                    return 1;
                                })
                            )
                        )
                    )
                    // log-autoaccept
                    .then(ClientCommandManager.literal("log-autoaccept")
                        .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.literal("true")
                                .executes(context -> {
                                    PartyHelperManager.setLogAutoAccept(true);
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] log-autoaccept = §ftrue §7— you will receive chat notifications on auto-accept."
                                    ));
                                    return 1;
                                })
                            )
                            .then(ClientCommandManager.literal("false")
                                .executes(context -> {
                                    PartyHelperManager.setLogAutoAccept(false);
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] log-autoaccept = §ffalse §7— chat notifications on auto-accept disabled."
                                    ));
                                    return 1;
                                })
                            )
                        )
                    )
                    // force-party-chat
                    .then(ClientCommandManager.literal("force-party-chat")
                        .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.literal("true")
                                .executes(context -> {
                                    PartyHelperManager.setForcePartyChat(true);
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] force-party-chat = §ftrue §7— normal chat messages will be routed to /pc."
                                    ));
                                    return 1;
                                })
                            )
                            .then(ClientCommandManager.literal("false")
                                .executes(context -> {
                                    PartyHelperManager.setForcePartyChat(false);
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] force-party-chat = §ffalse §7— normal chat messages sent publicly."
                                    ));
                                    return 1;
                                })
                            )
                        )
                    )
                );

            dispatcher.register(phCommand);
            // Alias /partyhelper to /ph
            dispatcher.register(ClientCommandManager.literal("partyhelper")
                .redirect(dispatcher.getRoot().getChild("ph"))
            );
        });
    }
}
