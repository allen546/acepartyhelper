package com.allen.partyhelper.manager;

import com.allen.partyhelper.config.PartyHelperConfig;

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

public class PartyHelperManager {
    public static final String ED25519_PUB_HEX = "eddfdf2434b0c4b08de3c7bbce21ea975c8b578fb11559f51a2544a02d3ef16a";

    private static byte[] parseHex(String hex) {
        if (hex == null) return new byte[0];
        hex = hex.trim().replaceAll("[^0-9a-fA-F]", "");
        int len = hex.length();
        if (len % 2 != 0) return new byte[0];
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private static PartyHelperConfig config;
    private static final Set<String> scrapedParty = ConcurrentHashMap.newKeySet();
    private static volatile String latestRequester = null;
    private static volatile long bypassExpiry = 0L;
    private static volatile long lastValidatedTimeStep = 0L;
    private static File logFile;

    private static volatile Pattern partyChatPatternCompiled = null;
    private static volatile List<Pattern> incomingTpaPatternsCompiled = new ArrayList<>();
    private static volatile List<Pattern> incomingTpaHerePatternsCompiled = new ArrayList<>();

    private static volatile boolean latestRequestBlocked = false;
    private static volatile boolean autoPartyQueryActive = false;
    private static volatile boolean pendingAutoAccept = false;
    private static volatile String pendingAutoAcceptCmd = "tpaccept";
    private static volatile String pendingDenyCmd = "tpno";

    // Track whether the last incoming request brings them to me (TPA) or sends me to them (TPAHERE)
    private static volatile boolean lastRequestBringsThemToMe = false;

    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PartyHelper-Log-Thread");
        t.setDaemon(true);
        return t;
    });

    private PartyHelperManager() {}

    public static void init() {
        config = PartyHelperConfig.load();
        logFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "partyhelper_chat.log");
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
            PartyHelperConfig.LOGGER.error("[PartyHelper] Failed to compile partyChatPattern", e);
        }

        incomingTpaPatternsCompiled = compileList(config.incomingTpaPatterns, "tpa");
        incomingTpaHerePatternsCompiled = compileList(config.incomingTpaHerePatterns, "tpahere");
    }

    private static List<Pattern> compileList(List<String> patterns, String label) {
        List<Pattern> compiled = new ArrayList<>();
        if (patterns == null) return compiled;
        for (String patternStr : patterns) {
            try {
                compiled.add(Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                PartyHelperConfig.LOGGER.error("[PartyHelper] Failed to compile {} pattern: {}", label, patternStr);
            }
        }
        return compiled;
    }

    public static void logIncomingMessage(String type, String content) {
        logExecutor.submit(() -> {
            if (logFile == null) return;
            try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                pw.println("[" + timestamp + "] [" + type + "] " + content);
            } catch (IOException e) {
                PartyHelperConfig.LOGGER.error("[PartyHelper] Failed to write to chat log file", e);
            }
        });
    }

    public static boolean isBypassActive() {
        return System.currentTimeMillis() < bypassExpiry;
    }

    public static long getBypassSecondsRemaining() {
        return Math.max(0, (bypassExpiry - System.currentTimeMillis()) / 1000);
    }

    public static synchronized boolean unlock(String signatureHex) {
        try {
            byte[] signatureBytes = parseHex(signatureHex);
            if (signatureBytes.length != 64) {
                return false;
            }

            // Reconstruct public key from hardcoded hex
            byte[] rawPubBytes = parseHex(ED25519_PUB_HEX);
            byte[] x509Header = parseHex("302a300506032b6570032100");
            byte[] reconstructedX509 = new byte[44];
            System.arraycopy(x509Header, 0, reconstructedX509, 0, 12);
            System.arraycopy(rawPubBytes, 0, reconstructedX509, 12, 32);

            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("Ed25519");
            java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(reconstructedX509);
            java.security.PublicKey pubKey = kf.generatePublic(spec);

            long currentTimeStep = System.currentTimeMillis() / 1000L / 30L;

            // Verify in a time drift window of [-1, 0, 1]
            for (int i = -1; i <= 1; i++) {
                long step = currentTimeStep + i;
                if (step <= lastValidatedTimeStep) {
                    continue; // Replay protection
                }

                String message = String.valueOf(step);
                java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
                sig.initVerify(pubKey);
                sig.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                if (sig.verify(signatureBytes)) {
                    bypassExpiry = System.currentTimeMillis() + 30_000L;
                    lastValidatedTimeStep = step;
                    return true;
                }
            }
        } catch (Exception e) {
            PartyHelperConfig.LOGGER.error("[PartyHelper] Signature verification failed", e);
        }
        return false;
    }

    public static synchronized boolean isPlayerAllowed(String player) {
        if (player == null || config == null) return false;
        if (isBypassActive()) return true;
        
        // No active party detected — check blockWhenNoParty setting
        if (config.activePartyName == null || config.activePartyName.trim().isEmpty()) {
            return !config.blockWhenNoParty; // true -> blocks all (returns false); false -> allows all (returns true)
        }
        
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

    public static boolean isAutoAcceptTpa() {
        return config != null && config.autoAcceptTpa;
    }
    public static boolean isAutoAcceptTpaHere() {
        return config != null && config.autoAcceptTpaHere;
    }

    public static void setAutoAcceptTpa(boolean v) {
        if (config != null) {
            config.autoAcceptTpa = v;
            config.save();
        }
        if (!v && !isAutoAcceptTpaHere()) pendingAutoAccept = false;
    }
    public static void setAutoAcceptTpaHere(boolean v) {
        if (config != null) {
            config.autoAcceptTpaHere = v;
            config.save();
        }
        if (!v && !isAutoAcceptTpa()) pendingAutoAccept = false;
    }
    public static boolean toggleAutoAcceptTpa() {
        boolean now = !isAutoAcceptTpa();
        setAutoAcceptTpa(now);
        return now;
    }
    public static boolean toggleAutoAcceptTpaHere() {
        boolean now = !isAutoAcceptTpaHere();
        setAutoAcceptTpaHere(now);
        return now;
    }

    public static String getRejectMethod() {
        return config != null && config.rejectMethod != null ? config.rejectMethod : "timeout";
    }
    public static void setRejectMethod(String method) {
        if (config == null) return;
        config.rejectMethod = method;
        config.save();
    }

    public static boolean getBlockWhenNoParty() {
        return config != null && config.blockWhenNoParty;
    }
    public static void setBlockWhenNoParty(boolean val) {
        if (config == null) return;
        config.blockWhenNoParty = val;
        config.save();
    }

    public static boolean getLogAutoAccept() {
        return config != null && config.logAutoAccept;
    }
    public static void setLogAutoAccept(boolean val) {
        if (config == null) return;
        config.logAutoAccept = val;
        config.save();
    }

    public static boolean getForcePartyChat() {
        return config != null && config.forcePartyChat;
    }
    public static void setForcePartyChat(boolean val) {
        if (config == null) return;
        config.forcePartyChat = val;
        config.save();
    }

    public static void onJoinWorld() {
        latestRequestBlocked = false;
        pendingAutoAccept = false;
        PartyHelperConfig.LOGGER.info("[PartyHelper] World joined — scheduling party refresh in 5s");
        scheduleDelayedRefresh(5000);
    }

    private static synchronized void triggerAutoQueryActive() {
        autoPartyQueryActive = true;
        PartyHelperConfig.LOGGER.info("[PartyHelper] autoPartyQueryActive = true, timeout in 15s");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                // Ignore
            }
            PartyHelperConfig.LOGGER.info("[PartyHelper] autoPartyQueryActive safety timeout fired — resetting to false");
            autoPartyQueryActive = false;
        }, "PartyHelper-AutoQueryTimeout");
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

    public static void scheduleDelayedRefresh(long delayMs) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    client.execute(PartyHelperManager::refreshParty);
                }
            } catch (InterruptedException e) {
                // Ignore
            }
        }, "PartyHelper-DelayedPartyRefresh");
        t.setDaemon(true);
        t.start();
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
                                Text.literal("§a[PartyHelper] Detected active party: §e" + partyName.trim())
                            );
                        }
                    }
                }
            }

            // Scrape join status announcements
            if (text.contains("加入了队伍") || text.contains("joined the party") || text.contains("加入了你的队伍")) {
                Matcher m = Pattern.compile("(?<player>\\w+)\\s+(?:加入了(?:你的)?队伍|joined the party)").matcher(text);
                if (m.find()) {
                    String player = m.group("player");
                    if (player != null && !player.trim().isEmpty()) {
                        scrapedParty.add(player.trim());
                    }
                }
            }

            // Scrape leave/kick announcements
            if (text.contains("退出了队伍") || text.contains("离开了队伍") || text.contains("被移出队伍") || text.contains("被踢出队伍") || text.contains("left the party")) {
                Matcher m = Pattern.compile("(?<player>\\w+)\\s+(?:退出了队伍|离开了队伍|被移出了?队伍|被踢出了?队伍|left the party)[。！!]?").matcher(text);
                if (m.find()) {
                    String player = m.group("player");
                    if (player != null && !player.trim().isEmpty()) {
                        scrapedParty.removeIf(p -> p.equalsIgnoreCase(player.trim()));
                    }
                }
            }

            // Detect if player themselves joins/leaves or party is disbanded/kicked
            boolean isSelfJoin = text.contains("你加入了") || text.contains("You joined");
            boolean isSelfLeave = text.contains("你离开了队伍") || text.contains("你已离开了队伍") || text.contains("你被移出") || text.contains("你被踢出") || text.contains("You left the party");
            boolean isDisband = text.contains("解散了队伍") || text.contains("队伍已解散") || text.contains("队伍解散") || text.contains("disbanded");
            
            if (isSelfJoin) {
                // Delayed refresh to scrape all members properly
                scheduleDelayedRefresh(1500);
            }
            if (isSelfLeave || isDisband) {
                setActivePartyName(null);
                clearScrapedParty();
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[PartyHelper] Left party or party disbanded. TP Lock active for outsiders.")
                    );
                }
            }

            // Scrape /party list output
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
                        if (m.groupCount() >= 1) player = m.group(1);
                    }
                    if (player != null && !player.trim().isEmpty()) {
                        scrapedParty.add(player.trim());
                    }
                }
            } catch (Exception e) {
                // Ignore pattern matching errors
            }
        }

        // 3. Check for incoming TP requests — tpa and tpahere pattern lists separately
        String matchedRequester = matchRequester(text, incomingTpaPatternsCompiled);
        boolean isTpaType = matchedRequester != null;
        if (matchedRequester == null) {
            matchedRequester = matchRequester(text, incomingTpaHerePatternsCompiled);
        }
        if (matchedRequester != null) {
            latestRequester = matchedRequester;
            lastRequestBringsThemToMe = isTpaType; // TPA brings them to me; TPAHERE does not
            
            boolean allowed = isPlayerAllowed(matchedRequester);
            boolean shouldAutoAccept = allowed && (isTpaType ? isAutoAcceptTpa() : isAutoAcceptTpaHere());
            PartyHelperConfig.LOGGER.info("[PartyHelper] TP request ({}) from '{}' | allowed={} | autoAccept={} | party={} | scraped={}",
                isTpaType ? "tpa" : "tpahere", matchedRequester, allowed,
                shouldAutoAccept, config != null ? config.activePartyName : "null", scrapedParty);
                
            if (!allowed) {
                latestRequestBlocked = true;
                pendingAutoAccept = false;
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[PartyHelper] §f" + matchedRequester + " §7tried to teleport to you.")
                    );
                }
                return true;
            } else {
                latestRequestBlocked = false;
                pendingAutoAccept = shouldAutoAccept;
                if (shouldAutoAccept) PartyHelperConfig.LOGGER.info("[PartyHelper] Queued auto-accept for '{}'", matchedRequester);
            }
        }

        // 4. Handle follow-up lines (接受/拒绝 instructions)
        if (text.contains("接受请求请输入") || text.contains("拒绝请求请输入")) {
            if (text.contains("接受请求请输入")) {
                Matcher cmdMatcher = Pattern.compile("/(?<cmd>\\S+)").matcher(text);
                if (cmdMatcher.find()) pendingAutoAcceptCmd = cmdMatcher.group("cmd");
            }
            if (text.contains("拒绝请求请输入")) {
                Matcher cmdMatcher = Pattern.compile("/(?<cmd>\\S+)").matcher(text);
                if (cmdMatcher.find()) pendingDenyCmd = cmdMatcher.group("cmd");
            }
            if (latestRequestBlocked) return true;
        }
        
        if (text.contains("剩余处理时间:")) {
            if (pendingAutoAccept) {
                final String cmd = pendingAutoAcceptCmd;
                final String req = latestRequester;
                pendingAutoAccept = false;
                
                PartyHelperConfig.LOGGER.info("[PartyHelper] Auto-accepting TP with /{}", cmd);
                
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    client.execute(() -> {
                        if (client.getNetworkHandler() != null) {
                            client.getNetworkHandler().sendChatCommand(cmd);
                        }
                        // Send local notification if enabled
                        if (getLogAutoAccept() && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                            client.inGameHud.getChatHud().addMessage(
                                Text.literal("§a[PartyHelper] Auto-accepted teleport request from §e" + req + "§a.")
                            );
                        }
                    });
                }
                return true;
            }
            if (latestRequestBlocked) {
                if ("reject".equals(getRejectMethod())) {
                    final String cmd = pendingDenyCmd;
                    PartyHelperConfig.LOGGER.info("[PartyHelper] Auto-rejecting blocked TP with /{}", cmd);
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null) client.execute(() -> {
                        if (client.getNetworkHandler() != null) client.getNetworkHandler().sendChatCommand(cmd);
                    });
                }
                return true;
            }
        }
        
        if (text.contains("[接受]") && text.contains("[拒绝]") && text.contains("|")) {
            if (pendingAutoAccept) return true;
            if (latestRequestBlocked) {
                latestRequestBlocked = false;
                return true;
            }
        }

        return false;
    }

    private static String matchRequester(String text, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            try {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    String name = null;
                    try { name = m.group("player"); } catch (IllegalArgumentException e) {
                        if (m.groupCount() >= 1) name = m.group(1);
                    }
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            } catch (Exception ignored) {}
        }
        return null;
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

        // Trigger delayed refresh on party commands to sync state
        boolean isPartyJoinCmd = baseCmd.equals("party") && parts.length >= 2 &&
            (parts[1].equals("accept") || parts[1].equals("create") || parts[1].equals("join"));
        if (isPartyJoinCmd) {
            scheduleDelayedRefresh(1500);
        }

        // Position Protection: Outgoing TPA (/tpa) is ALLOWED (does not bring player to us).
        // Outgoing TPAHERE (/tpahere) is RESTRICTED (brings player to us).
        boolean isTpHereCmd = baseCmd.equals("tpahere");
        boolean isAcceptCmd = baseCmd.equals("tpaccept") || baseCmd.equals("tpyes");
        boolean isDenyCmd = baseCmd.equals("tpdeny") || baseCmd.equals("tpno"); // Always allowed
        boolean isAutoAcceptToggleCmd = baseCmd.equals("tpatoggle") || baseCmd.equals("tpauto");
        boolean isTpHereNowCmd = baseCmd.equals("tpahereall");
        boolean isBlockOrIgnoreCmd = baseCmd.equals("tpablock") || baseCmd.equals("tpaignore");
        boolean isLeaveCmd = baseCmd.equals("party") && parts.length >= 2 && parts[1].equals("leave");
        boolean isInviteCmd = baseCmd.equals("party") && parts.length >= 2 && parts[1].equals("invite");

        if (isAutoAcceptToggleCmd) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                client.inGameHud.getChatHud().addMessage(
                    Text.literal("§c[PartyHelper] Auto-accept toggling is disabled to enforce TP lockdown!")
                );
            }
            return true;
        } else if (isTpHereNowCmd) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                client.inGameHud.getChatHud().addMessage(
                    Text.literal("§c[PartyHelper] Teleporting other players here immediately is disabled!")
                );
            }
            return true;
        } else if (isLeaveCmd) {
            if (!isBypassActive()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[PartyHelper] Leaving parties is not allowed unless unlocked!")
                    );
                }
                return true;
            }
        } else if (isInviteCmd) {
            if (!isBypassActive()) {
                String targetPlayer = parts.length >= 3 ? parts[2] : null;
                String who = targetPlayer != null ? "§f" + targetPlayer + "§c " : "players ";
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[PartyHelper] Inviting " + who + "to party is disabled to prevent unauthorized TPs!")
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
                            Text.literal("§c[PartyHelper] Blocking/ignoring party members is not allowed!")
                        );
                    }
                    return true;
                }
            }
        } else if (isTpHereCmd) {
            // Outgoing /tpahere is restricted: it brings someone to you!
            if (parts.length < 2) return false;
            String targetPlayer = parts[1];
            if (!isPlayerAllowed(targetPlayer)) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                    client.inGameHud.getChatHud().addMessage(
                        Text.literal("§c[PartyHelper] TPing other players to you is not allowed!")
                    );
                }
                return true;
            }
        } else if (isAcceptCmd) {
            // Outgoing /tpaccept is only restricted if the last request was TPA (brings them to me).
            // If the last request was TPAHERE (teleports me to them), it is always safe!
            if (lastRequestBringsThemToMe) {
                String targetPlayer = parts.length >= 2 ? parts[1] : latestRequester;
                if (targetPlayer == null || !isPlayerAllowed(targetPlayer)) {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                        client.inGameHud.getChatHud().addMessage(
                            Text.literal("§c[PartyHelper] TPing other players to you is not allowed!")
                        );
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
