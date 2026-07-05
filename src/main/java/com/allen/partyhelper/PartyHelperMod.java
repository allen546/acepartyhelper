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
    private static final ThreadLocal<Boolean> processingMessage = ThreadLocal.withInitial(() -> false);

    @Override
    public void onInitializeClient() {
        PartyHelperManager.init();

        // Auto-run /party list with delay on connection
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PartyHelperManager.onJoinWorld();
        });

        // Block outgoing commands
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (processingMessage.get()) return true;
            return !PartyHelperManager.handleOutgoingCommand(command);
        });

        // Force routing normal chat messages to party chat (/pc)
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (processingMessage.get()) return true;
            if (PartyHelperManager.getForcePartyChat()) {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client != null && client.getNetworkHandler() != null) {
                    processingMessage.set(true);
                    try {
                        client.getNetworkHandler().sendChatCommand("pc " + message);
                    } finally {
                        processingMessage.set(false);
                    }
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
                            "§e/ph help §7— show help menu\n" +
                            "§e/ph status §7— show lock/party/settings status\n" +
                            "§e/ph unlock <signature> §7— signature bypass unlock (30s)\n" +
                            "§e/ph party-refresh §7— manually query and refresh party\n" +
                            "§e/ph autoaccept tpa|tpahere on|off|toggle\n" +
                            "§e/ph settings <option> set <value> §7— configure options (see /ph status)"
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
                            "§7Log Auto-Accept: " + (PartyHelperManager.getLogAutoAccept() ? "§aYES" : "§cNO") + "\n" +
                            "§7Auto-Accept Requires Locked Party: " + (PartyHelperManager.getAutoAcceptRequiresLockedParty() ? "§aYES" : "§cNO")
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
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] reject-method = §f" + PartyHelperManager.getRejectMethod()
                            ));
                            return 1;
                        })
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
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] block-when-no-party = §f" + PartyHelperManager.getBlockWhenNoParty()
                            ));
                            return 1;
                        })
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
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] log-autoaccept = §f" + PartyHelperManager.getLogAutoAccept()
                            ));
                            return 1;
                        })
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
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] force-party-chat = §f" + PartyHelperManager.getForcePartyChat()
                            ));
                            return 1;
                        })
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
                    // auto-accept-requires-locked-party
                    .then(ClientCommandManager.literal("auto-accept-requires-locked-party")
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(
                                "§a[PartyHelper] auto-accept-requires-locked-party = §f" + PartyHelperManager.getAutoAcceptRequiresLockedParty()
                            ));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.literal("true")
                                .executes(context -> {
                                    PartyHelperManager.setAutoAcceptRequiresLockedParty(true);
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] auto-accept-requires-locked-party = §ftrue §7— auto-accept disabled when solo or unlocked."
                                    ));
                                    return 1;
                                })
                            )
                            .then(ClientCommandManager.literal("false")
                                .executes(context -> {
                                    PartyHelperManager.setAutoAcceptRequiresLockedParty(false);
                                    context.getSource().sendFeedback(Text.literal(
                                        "§a[PartyHelper] auto-accept-requires-locked-party = §ffalse §7— auto-accept triggers whenever allowed."
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

            // Register client-side /global command to avoid invalid red command indicator
            dispatcher.register(ClientCommandManager.literal("global")
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(
                        "§c[PartyHelper] Usage: /global <message> to send a server-wide chat message."
                    ));
                    return 1;
                })
                .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                    .executes(context -> {
                        if (PartyHelperManager.getForcePartyChat()) {
                            String msg = StringArgumentType.getString(context, "message");
                            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                            if (client != null && client.getNetworkHandler() != null) {
                                processingMessage.set(true);
                                try {
                                    client.getNetworkHandler().sendChatMessage(msg);
                                } finally {
                                    processingMessage.set(false);
                                }
                            }
                        } else {
                            // If force-party-chat is disabled, send normal command to server or fallback
                            String msg = StringArgumentType.getString(context, "message");
                            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                            if (client != null && client.getNetworkHandler() != null) {
                                client.getNetworkHandler().sendChatMessage(msg);
                            }
                        }
                        return 1;
                    })
                )
            );
        });
    }
}
