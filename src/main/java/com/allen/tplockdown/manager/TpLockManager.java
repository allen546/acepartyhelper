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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TpLockManager {
    // HARDCODED TOTP SECRET ON COMPILE (Base32 format, e.g. "I65VU7K5ZQL7S62D")
    public static final String TOTP_SECRET = "I65VU7K5ZQL7S62D";

    private static ModConfig config;
    private static final Set<String> scrapedParty = ConcurrentHashMap.newKeySet();
    private static volatile String latestRequester = null;
    private static volatile long bypassExpiry = 0L;
    private static volatile String lastValidatedCode = null;
    private static File logFile;

    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();

    private static Pattern partyChatPatternCompiled = null;
    private static final List<Pattern> incomingRequestPatternsCompiled = new java.util.ArrayList<>();

    public static void init() {
        config = ModConfig.load();
        logFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "tplockdown_chat.log");
        compilePatterns();
    }

    public static synchronized void reloadConfig() {
        config = ModConfig.load();
        compilePatterns();
    }

    public static synchronized void compilePatterns() {
        partyChatPatternCompiled = null;
        if (config != null && config.partyChatPattern != null && !config.partyChatPattern.isEmpty()) {
            try {
                partyChatPatternCompiled = Pattern.compile(config.partyChatPattern);
            } catch (Exception e) {
                ModConfig.LOGGER.error("Failed to compile party chat pattern: " + config.partyChatPattern, e);
            }
        }

        incomingRequestPatternsCompiled.clear();
        if (config != null && config.incomingRequestPatterns != null) {
            for (String patternStr : config.incomingRequestPatterns) {
                try {
                    Pattern p = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                    incomingRequestPatternsCompiled.add(p);
                } catch (Exception e) {
                    ModConfig.LOGGER.error("Failed to compile incoming request pattern: " + patternStr, e);
                }
            }
        }
    }

    public static void logIncomingMessage(String type, String content) {
        if (logFile == null) return;
        logExecutor.submit(() -> {
            try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                pw.println("[" + timestamp + "] [" + type + "] " + content);
            } catch (IOException e) {
                ModConfig.LOGGER.error("Failed to write to chat log file", e);
            }
        });
    }

    public static boolean isBypassActive() {
        return System.currentTimeMillis() < bypassExpiry;
    }

    public static long getBypassSecondsRemaining() {
        return Math.max(0, (bypassExpiry - System.currentTimeMillis()) / 1000);
    }

    public static synchronized boolean unlock(String code) {
        if (code == null) return false;
        if (code.equals(lastValidatedCode)) {
            return false; // Prevent Replay Attack
        }
        if (TotpUtils.verify(TOTP_SECRET, code)) {
            bypassExpiry = System.currentTimeMillis() + 30_000L;
            lastValidatedCode = code;
            return true;
        }
        return false;
    }

    public static synchronized boolean isPlayerAllowed(String player) {
        if (player == null || config == null) return false;
        String name = player.trim().toLowerCase();
        return isBypassActive() || 
               config.manualParty.stream().anyMatch(p -> p.equalsIgnoreCase(name)) || 
               scrapedParty.stream().anyMatch(p -> p.equalsIgnoreCase(name));
    }

    public static synchronized void addManualPartyMember(String player) {
        if (player == null) return;
        config.manualParty.add(player.trim());
        config.save();
    }

    public static synchronized void removeManualPartyMember(String player) {
        if (player == null) return;
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
        if (partyChatPatternCompiled != null) {
            try {
                Matcher m = partyChatPatternCompiled.matcher(text);
                if (m.find()) {
                    String player = null;
                    try {
                        player = m.group("player");
                    } catch (IllegalArgumentException e) {
                        if (m.groupCount() >= 1) {
                            player = m.group(1);
                        }
                    }
                    if (player != null && !player.trim().isEmpty()) {
                        scrapedParty.add(player.trim());
                    }
                }
            } catch (Exception e) {
                // Fallback for bad patterns
            }
        }

        // 2. Check for incoming TP requests
        for (Pattern p : incomingRequestPatternsCompiled) {
            try {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    String requester = null;
                    try {
                        requester = m.group("player");
                    } catch (IllegalArgumentException e) {
                        if (m.groupCount() >= 1) {
                            requester = m.group(1);
                        }
                    }
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
        if (rawCommand == null) return false;
        rawCommand = rawCommand.trim();
        if (rawCommand.startsWith("/")) {
            rawCommand = rawCommand.substring(1);
        }
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
