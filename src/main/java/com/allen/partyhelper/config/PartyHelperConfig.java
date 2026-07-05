package com.allen.partyhelper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PartyHelperConfig {
    public static final Logger LOGGER = LoggerFactory.getLogger("PartyHelper");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;

    // Bump this any time initializeDefaultPatterns() changes
    private static final int CURRENT_CONFIG_VERSION = 3;

    public int configVersion = 0;
    public String activePartyName = null;
    /** Patterns for TPA requests: someone wants to teleport TO you. */
    public List<String> incomingTpaPatterns = new ArrayList<>();
    /** Patterns for TPAHERE requests: someone wants you to teleport TO them. */
    public List<String> incomingTpaHerePatterns = new ArrayList<>();
    /** What to do with blocked TP requests: "timeout" (do nothing) or "reject" (auto-send deny). */
    public String rejectMethod = "timeout";
    /** Block ALL TP requests when not in any party. Default true — disabling this is a security risk. */
    public boolean blockWhenNoParty = true;
    /** Log client-side warning/feedback chat message when autoaccepting. Default true. */
    public boolean logAutoAccept = true;
    /** Automatically prefix plain chat messages with /pc and support /global. Default true. */
    public boolean forcePartyChat = true;

    private static File getConfigFile() {
        if (configFile == null) {
            configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "partyhelper.json");
        }
        return configFile;
    }

    public static PartyHelperConfig load() {
        File file = getConfigFile();
        if (!file.exists()) {
            PartyHelperConfig config = new PartyHelperConfig();
            config.initializeDefaultPatterns();
            config.save();
            return config;
        }
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            PartyHelperConfig config = GSON.fromJson(reader, PartyHelperConfig.class);
            if (config == null) config = new PartyHelperConfig();
            boolean stale = config.configVersion < CURRENT_CONFIG_VERSION
                || config.incomingTpaPatterns == null || config.incomingTpaPatterns.isEmpty()
                || config.incomingTpaHerePatterns == null || config.incomingTpaHerePatterns.isEmpty();
            if (stale) {
                LOGGER.info("[PartyHelper] Config version {} outdated (current {}), re-initialising patterns",
                    config.configVersion, CURRENT_CONFIG_VERSION);
                config.initializeDefaultPatterns();
                config.save();
            }
            // Populate defaults for new fields if GSON deserialization left them as default primitive values but they were not present
            // Java field initializers run before GSON deserialization, but GSON can overwrite them or if they're not in JSON they remain.
            // Let's guarantee valid values:
            if (config.rejectMethod == null) config.rejectMethod = "timeout";
            return config;
        } catch (Exception e) {
            LOGGER.error("[PartyHelper] Failed to load config, renaming corrupted file", e);
            try {
                File brokenFile = new File(file.getParentFile(), "partyhelper.json.broken");
                if (brokenFile.exists()) brokenFile.delete();
                if (!file.renameTo(brokenFile)) file.delete();
            } catch (Exception re) {
                LOGGER.error("[PartyHelper] Failed to rename or delete corrupted config file", re);
            }
            PartyHelperConfig defaultConfig = new PartyHelperConfig();
            defaultConfig.initializeDefaultPatterns();
            defaultConfig.save();
            return defaultConfig;
        }
    }

    private void initializeDefaultPatterns() {
        if (incomingTpaPatterns == null) incomingTpaPatterns = new ArrayList<>();
        incomingTpaPatterns.clear();
        // TPA: they teleport TO you
        incomingTpaPatterns.add("^\\s*(?<player>\\w+)\\s+wants to teleport to you");
        incomingTpaPatterns.add("^\\s*(?<player>\\w+)\\s+has requested to teleport to you");
        incomingTpaPatterns.add("^\\s*(?<player>\\w+)\\s+wants to tp to you");
        incomingTpaPatterns.add("^\\s*\\[传送\\]\\s*(?<player>\\w+)\\s*想传送到你这里。");

        if (incomingTpaHerePatterns == null) incomingTpaHerePatterns = new ArrayList<>();
        incomingTpaHerePatterns.clear();
        // TPAHERE: they want you to teleport TO them
        incomingTpaHerePatterns.add("^\\s*(?<player>\\w+)\\s+wants you to teleport to them");
        incomingTpaHerePatterns.add("^\\s*(?<player>\\w+)\\s+has requested that you teleport to them");
        incomingTpaHerePatterns.add("^\\s*\\[传送\\]\\s*(?<player>\\w+)\\s*邀请你传送到他那里。");

        if (rejectMethod == null) rejectMethod = "timeout";
        configVersion = CURRENT_CONFIG_VERSION;
    }

    public void save() {
        new Thread(() -> {
            File file = getConfigFile();
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            } catch (Exception e) {
                LOGGER.error("[PartyHelper] Failed to save config", e);
            }
        }, "PartyHelper-ConfigSave").start();
    }
}
