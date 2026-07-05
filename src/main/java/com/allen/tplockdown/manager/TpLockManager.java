package com.allen.tplockdown.manager;

import com.allen.tplockdown.config.ModConfig;
import com.allen.tplockdown.util.TotpUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TpLockManager {
    public static final String TOTP_SECRET = "MZRWY3L2N5XG6Z3D";

    private static ModConfig config;
    private static final Set<String> scrapedParty = ConcurrentHashMap.newKeySet();
    private static volatile String latestRequester = null;
    private static volatile long bypassExpiry = 0L;
    private static volatile long lastValidatedTimeStep = 0L;
    private static File logFile;

    private static volatile Pattern partyChatPatternCompiled = null;
    private static volatile List<Pattern> incomingRequestPatternsCompiled = new ArrayList<>();

    private static volatile boolean latestRequestBlocked = false;
    private static volatile boolean autoPartyQueryActive = false;

    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TpLockdown-Log-Thread");
        t.setDaemon(true);
        return t;
    });

    private TpLockManager() {}

    public static void init() {
        config = ModConfig.load();
        logFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "tplockdown_chat.log");
        compilePatterns();
    }

    public static synchronized void compilePatterns() {
        if (config == null) return;
        try {
            String activeParty = config.activePartyName;
            if (activeParty == null || activeParty.trim().isEmpty()) {
                partyChatPatternCompiled = null;
            } else {
                String escapedParty = Pattern.quote(activeParty.trim());
                String regex = "^\\s*\\[" + escapedParty + "\\]\\s*(?:\\[[^\\]]+\\]\\s*)?(?<player>\\w+)";
                partyChatPatternCompiled = Pattern.compile(regex);
            }
        } catch (Exception e) {
            ModConfig.LOGGER.error("[TpLockdown] Failed to compile partyChatPattern", e);
        }

        List<Pattern> compiled = new ArrayList<>();
        if (config.incomingRequestPatterns != null) {
            for (String patternStr : config.incomingRequestPatterns) {
                try {
                    compiled.add(Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE));
                } catch (Exception e) {
                    ModConfig.LOGGER.error("[TpLockdown] Failed to compile incoming request pattern: " + patternStr, e);
                }
            }
        }
        incomingRequestPatternsCompiled = compiled;
    }

    public static void logIncomingMessage(String type, String content) {
        logExecutor.submit(() -> {
            if (logFile == null) return;
            try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                pw.println("[" + timestamp + "] [" + type + "] " + content);
            } catch (IOException e) {
                ModConfig.LOGGER.error("[TpLockdown] Failed to write to chat log file", e);
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
        long currentTimeStep = System.currentTimeMillis() / 1000L / 30L;
        if (currentTimeStep == lastValidatedTimeStep) {
            return false;
        }
        long matchedStep = TotpUtils.verifyAndGetStep(TOTP_SECRET, code);
        if (matchedStep != -1 && matchedStep > lastValidatedTimeStep) {
            bypassExpiry = System.currentTimeMillis() + 30_000L;
            lastValidatedTimeStep = matchedStep;
            return true;
        }
        return false;
    }

    public static synchronized boolean isPlayerAllowed(String player) {
        if (player == null || config == null) return false;
        // If not in a party, mod is effectively disabled (always allow)
        if (config.activePartyName == null || config.activePartyName.trim().isEmpty()) {
            return true;
        }
        if (isBypassActive()) return true;
        String name = player.trim().toLowerCase();
        return scrapedParty.stream().filter(p -> p != null).anyMatch(p -> p.equalsIgnoreCase(name));
    }

    public static synchronized boolean isPlayerInParty(String player) {
        if (player == null) return false;
        String name = player.trim().toLowerCase();
        return scrapedParty.stream().filter(p -> p != null).anyMatch(p -> p.equalsIgnoreCase(name));
    }

    public static synchronized String getActivePartyName() {
        return config != null ? config.activePartyName : null;
    }

    public static synchronized void setActivePartyName(String name) {
        if (config == null) return;
        config.activePartyName = name != null ? name.trim() : null;
        config.save();
        compilePatterns();
    }

    public static Set<String> getScrapedParty() {
        return scrapedParty;
    }

    public static synchronized void clearScrapedParty() {
        scrapedParty.clear();
    }

    public static void onJoinWorld() {
        scrapedParty.clear();
        latestRequestBlocked = false;
        setActivePartyName(null);

        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    client.execute(() -> {
                        triggerAutoQueryActive();
                        if (client.getNetworkHandler() != null && client.player != null) {
                            client.getNetworkHandler().sendChatCommand("party list");
                        }
                    });
                }
            } catch (InterruptedException e) {
                // Ignore
            }
        }, "TpLockdown-AutoPartyQuery");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void triggerAutoQueryActive() {
        autoPartyQueryActive = true;
        // Safety timeout to prevent locking chat filter indefinitely
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // Ignore
            }
            autoPartyQueryActive = false;
        }, "TpLockdown-AutoQueryTimeout");
        t.setDaemon(true);
        t.start();
    }

    public static void refreshParty() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getNetworkHandler() != null) {
            triggerAutoQueryActive();
            client.getNetworkHandler().sendChatCommand("party list");
        }
    }

    public static boolean handleIncomingMessage(Text message, boolean isChat) {
        if (message == null) return false;
        String text = message.getString();

        // 1. Parse server status updates strictly from non-chat game sources
        if (!isChat) {
            // Actual format: ===== 队伍 [party1] (17/50) =====
            if (text.contains("===== 队伍") || text.contains("===== 组队")) {
                Matcher m = Pattern.compile("=====\\s*(?:队伍|组队)\\s*\\[(?<partyName>[^\\]]+)\\]").matcher(text);
                if (m.find()) {
                    String partyName = m.group("partyName");
                    if (partyName != null && !partyName.trim().isEmpty()) {
                        setActivePartyName(partyName.trim());
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                            client.inGameHud.getChatHud().addMessage(
                                Text.literal("§a[TP-Lock] Detected active party: §e" + partyName.trim())
                            );
                        }
                    }
                }
            }

            // Scrape join status announcements
            if (text.contains("加入了队伍")) {
                Matcher m = Pattern.compile("(?<player>\\w+)\\s+加入了队伍").matcher(text);
                if (m.find()) {
                    String player = m.group("player");
                    if (player != null && !player.trim().isEmpty()) {
                        scrapedParty.add(player.trim());
                    }
                }
            }

            // Scrape leave/kick announcements
            if (text.contains("退出了队伍") || text.contains("离开了队伍") || text.contains("被移出队伍") || text.contains("被踢出队伍")) {
                Matcher m = Pattern.compile("(?<player>\\w+)\\s+(?:退出了队伍|离开了队伍|被移出了?队伍|被踢出了?队伍)[。！!]?").matcher(text);
                if (m.find()) {
                    String player = m.group("player");
                    if (player != null && !player.trim().isEmpty()) {
                        scrapedParty.removeIf(p -> p.equalsIgnoreCase(player.trim()));
                    }
                }
            }

            // Detect if player themselves leaves or party is disbanded/kicked
            boolean isSelfLeave = text.contains("你离开了队伍") || text.contains("你已离开了队伍") || text.contains("你被移出") || text.contains("你被踢出");
            boolean isDisband = text.contains("解散了队伍") || text.contains("队伍已解散") || text.contains("队伍解散");
            if (isSelfLeave || isDisband) {
                setActivePartyName(null);
                clearScrapedParty();
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[TP-Lock] Left party or party disbanded. TP Lock disabled.")
                    );
                }
            }

            // Scrape /party list output
            // Actual formats from server:
            //   ★队长 badpig1234 [在线]
            //   ★副队长 someone [离线]
            //   (indented with spaces)   David_Li [在线]
            // Also old-style fallback:
            //   队长：name  or  队员：name
            boolean isLeaderLine = text.trim().startsWith("★队长") || text.trim().startsWith("★副队长") || text.startsWith("队长：") || text.startsWith("队长:");
            boolean isMemberLine = !isLeaderLine && text.matches("^\\s{2,}(\\w+)\\s+\\[(?:在线|离线)\\]\\s*$")
                || text.startsWith("队员：") || text.startsWith("队员:");

            if (isLeaderLine) {
                Matcher m = Pattern.compile("(?:★队长|★副队长|队长[：:])\\s*(?<player>\\w+)").matcher(text);
                if (m.find()) {
                    String player = m.group("player");
                    if (player != null && !player.trim().isEmpty()) {
                        scrapedParty.add(player.trim());
                    }
                }
            } else if (isMemberLine) {
                // Indented member line: "  David_Li [在线]" or "队员：name1, name2"
                if (text.startsWith("队员：") || text.startsWith("队员:")) {
                    String membersStr = text.substring(3).trim();
                    for (String member : membersStr.split("[,，\\s]+")) {
                        if (!member.trim().isEmpty()) scrapedParty.add(member.trim());
                    }
                } else {
                    Matcher m = Pattern.compile("^\\s+((?<player>\\w+))\\s+\\[(?:在线|离线)\\]").matcher(text);
                    if (m.find()) {
                        String player = m.group("player");
                        if (player != null && !player.trim().isEmpty()) {
                            scrapedParty.add(player.trim());
                        }
                    }
                }
            }

            // Intercept and hide auto party query list output
            if (autoPartyQueryActive) {
                boolean isHeader = text.contains("===== 队伍") || text.contains("===== 组队");
                if (isHeader || isLeaderLine || isMemberLine) {
                    return true; // Drop line from chat hud
                }
                // Safety: also suppress lines that look like /party command help text
                if (text.contains("/party ") || text.contains("/pc ")) {
                    return true;
                }
            }
        }

        // 2. Scrape party chat for members strictly matching activePartyName prefix
        Pattern currentPartyChatPattern = partyChatPatternCompiled;
        if (currentPartyChatPattern != null) {
            try {
                Matcher m = currentPartyChatPattern.matcher(text);
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
                // Ignore pattern matching errors
            }
        }

        // 3. Check for incoming TP requests
        List<Pattern> currentIncomingRequestPatterns = incomingRequestPatternsCompiled;
        for (Pattern p : currentIncomingRequestPatterns) {
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
                            latestRequestBlocked = true;
                            MinecraftClient client = MinecraftClient.getInstance();
                            if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                                client.inGameHud.getChatHud().addMessage(
                                    Text.literal("§c[TP-Lock] §f" + requester + " §7tried to teleport to you.")
                                );
                            }
                            return true; // Hide request trigger line
                        } else {
                            latestRequestBlocked = false;
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore pattern matching errors
            }
        }

        // 4. Hide follow-up instruction lines for blocked requests
        if (latestRequestBlocked) {
            if (text.contains("剩余处理时间:") || text.contains("接受请求请输入") || text.contains("拒绝请求请输入")) {
                return true; // Drop details
            }
            if (text.contains("[接受]") && text.contains("[拒绝]") && text.contains("|")) {
                latestRequestBlocked = false; // Reset block sequence
                return true; // Drop options selector line
            }
        }

        return false;
    }

    public static boolean handleOutgoingCommand(String rawCommand) {
        if (rawCommand == null) return false;
        rawCommand = rawCommand.trim();
        while (rawCommand.startsWith("/")) {
            rawCommand = rawCommand.substring(1);
        }
        String normalized = rawCommand.trim().toLowerCase();
        String[] parts = normalized.split("\\s+");
        if (parts.length == 0) return false;

        String baseCmd = parts[0];
        int colonIndex = baseCmd.indexOf(':');
        if (colonIndex != -1) {
            baseCmd = baseCmd.substring(colonIndex + 1);
        }

        boolean isTpCmd = baseCmd.equals("tpa") || baseCmd.equals("tpahere");
        boolean isAcceptOrDenyCmd = baseCmd.equals("tpaccept") || baseCmd.equals("tpyes") || baseCmd.equals("tpdeny") || baseCmd.equals("tpno");
        boolean isAutoAcceptToggleCmd = baseCmd.equals("tpatoggle") || baseCmd.equals("tpauto");
        boolean isTpHereNowCmd = baseCmd.equals("tpaherenow");
        boolean isBlockOrIgnoreCmd = baseCmd.equals("tpablock") || baseCmd.equals("tpaignore");
        boolean isLeaveCmd = baseCmd.equals("party") && parts.length >= 2 && parts[1].equals("leave");

        if (isAutoAcceptToggleCmd) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                client.inGameHud.getChatHud().addMessage(
                    Text.literal("§c[TP-Lock] Auto-accept toggling is disabled to enforce TP lockdown!")
                );
            }
            return true;
        } else if (isTpHereNowCmd) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                client.inGameHud.getChatHud().addMessage(
                    Text.literal("§c[TP-Lock] Teleporting other players here immediately is disabled!")
                );
            }
            return true;
        } else if (isLeaveCmd) {
            if (!isBypassActive()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[TP-Lock] Leaving parties is not allowed unless unlocked!")
                    );
                }
                return true;
            }
        } else if (isBlockOrIgnoreCmd) {
            if (parts.length >= 2) {
                String targetPlayer = parts[1];
                if (isPlayerInParty(targetPlayer)) {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                        client.inGameHud.getChatHud().addMessage(
                            Text.literal("§c[TP-Lock] Blocking/ignoring party members is not allowed!")
                        );
                    }
                    return true;
                }
            }
        } else if (isTpCmd) {
            if (parts.length < 2) {
                return false;
            }
            String targetPlayer = parts[1];
            if (!isPlayerAllowed(targetPlayer)) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[TP-Lock] TPing other players is not allowed!")
                    );
                }
                return true;
            }
        } else if (isAcceptOrDenyCmd) {
            String targetPlayer = parts.length >= 2 ? parts[1] : latestRequester;
            if (targetPlayer == null || !isPlayerAllowed(targetPlayer)) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[TP-Lock] TPing other players is not allowed!")
                    );
                }
                return true;
            }
        }

        return false;
    }
}
