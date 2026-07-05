package com.allen.tplockdown.manager;

import com.allen.tplockdown.config.ModConfig;
import com.allen.tplockdown.util.TotpUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

public class TpLockManager {
    // HARDCODED TOTP SECRET ON COMPILE (Base32 format, e.g. "I65VU7K5ZQL7S62D")
    public static final String TOTP_SECRET = "I65VU7K5ZQL7S62D";

    private static ModConfig config;
    private static final Set<String> scrapedParty = new HashSet<>();
    private static String latestRequester = null;
    private static long bypassExpiry = 0L;
    private static long lastValidatedTimeStep = 0L;
    private static File logFile;

    public static void init() {
        config = ModConfig.load();
        logFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "tplockdown_chat.log");
    }

    public static synchronized void logIncomingMessage(String type, String content) {
        if (logFile == null) return;
        try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true);
             PrintWriter pw = new PrintWriter(fw)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            pw.println("[" + timestamp + "] [" + type + "] " + content);
        } catch (IOException e) {
            ModConfig.LOGGER.error("Failed to write to chat log file", e);
        }
    }

    public static boolean isBypassActive() {
        return System.currentTimeMillis() < bypassExpiry;
    }

    public static long getBypassSecondsRemaining() {
        return Math.max(0, (bypassExpiry - System.currentTimeMillis()) / 1000);
    }

    public static synchronized boolean unlock(String code) {
        long currentTimeStep = System.currentTimeMillis() / 1000L / 30L;
        if (currentTimeStep == lastValidatedTimeStep) {
            return false; // Prevent Replay Attack
        }
        if (TotpUtils.verify(TOTP_SECRET, code)) {
            bypassExpiry = System.currentTimeMillis() + 30_000L;
            lastValidatedTimeStep = currentTimeStep;
            return true;
        }
        return false;
    }

    public static boolean isPlayerAllowed(String player) {
        if (player == null) return false;
        String name = player.trim().toLowerCase();
        return isBypassActive() || 
               config.manualParty.stream().anyMatch(p -> p.equalsIgnoreCase(name)) || 
               scrapedParty.stream().anyMatch(p -> p.equalsIgnoreCase(name));
    }

    public static synchronized void addManualPartyMember(String player) {
        config.manualParty.add(player.trim());
        config.save();
    }

    public static synchronized void removeManualPartyMember(String player) {
        config.manualParty.removeIf(p -> p.equalsIgnoreCase(player.trim()));
        config.save();
    }

    public static synchronized void clearManualParty() {
        config.manualParty.clear();
        config.save();
    }

    public static Set<String> getManualParty() {
        return config.manualParty;
    }

    public static Set<String> getScrapedParty() {
        return scrapedParty;
    }

    public static synchronized void clearScrapedParty() {
        scrapedParty.clear();
    }

    public static boolean handleIncomingMessage(Text message) {
        String text = message.getString();

        // 1. Scrape party chat for members automatically
        if (config.partyChatPattern != null && !config.partyChatPattern.isEmpty()) {
            try {
                Pattern p = Pattern.compile(config.partyChatPattern);
                Matcher m = p.matcher(text);
                if (m.find()) {
                    String player = m.group("player");
                    if (player != null && !player.trim().isEmpty()) {
                        scrapedParty.add(player.trim());
                    }
                }
            } catch (Exception e) {
                // Fallback for bad patterns
            }
        }

        // 2. Check for incoming TP requests
        for (String patternStr : config.incomingRequestPatterns) {
            try {
                Pattern p = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(text);
                if (m.find()) {
                    String requester = m.group("player");
                    if (requester != null && !requester.trim().isEmpty()) {
                        latestRequester = requester.trim();
                        if (!isPlayerAllowed(requester)) {
                            // Log blocked attempt to player
                            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(
                                Text.literal("§c[TP-Lock] §f" + requester + " §7tried to teleport to you.")
                            );
                            return true; // Drop message from being displayed
                        }
                    }
                }
            } catch (Exception e) {
                // Fallback for bad patterns
            }
        }
        return false; // Show normally
    }

    public static boolean handleOutgoingCommand(String rawCommand) {
        String normalized = rawCommand.trim().toLowerCase();
        String[] parts = normalized.split("\\s+");
        if (parts.length == 0) return false;

        String baseCmd = parts[0];
        
        boolean isTpCmd = baseCmd.equals("tpa") || baseCmd.equals("tpahere");
        boolean isAcceptCmd = baseCmd.equals("tpaccept") || baseCmd.equals("tpyes");

        if (isTpCmd) {
            if (parts.length < 2) {
                return false; // Let server handle missing args
            }
            String targetPlayer = parts[1];
            if (!isPlayerAllowed(targetPlayer)) {
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(
                    Text.literal("§c[TP-Lock] TPing other players is not allowed!")
                );
                return true; // Block command
            }
        } else if (isAcceptCmd) {
            String targetPlayer = parts.length >= 2 ? parts[1] : latestRequester;
            if (targetPlayer == null || !isPlayerAllowed(targetPlayer)) {
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(
                    Text.literal("§c[TP-Lock] TPing other players is not allowed!")
                );
                return true; // Block command
            }
        }

        return false; // Allow command
    }
}
