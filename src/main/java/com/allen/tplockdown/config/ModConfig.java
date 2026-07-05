package com.allen.tplockdown.config;

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

public class ModConfig {
    public static final Logger LOGGER = LoggerFactory.getLogger("TpLockdown");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;

    public String activePartyName = null;
    public List<String> incomingRequestPatterns = new ArrayList<>();

    private static File getConfigFile() {
        if (configFile == null) {
            configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "tplockdown.json");
        }
        return configFile;
    }

    public static ModConfig load() {
        File file = getConfigFile();
        if (!file.exists()) {
            ModConfig config = new ModConfig();
            config.initializeDefaultPatterns();
            config.save();
            return config;
        }
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            ModConfig config = GSON.fromJson(reader, ModConfig.class);
            if (config == null) {
                config = new ModConfig();
            }
            if (config.incomingRequestPatterns == null || config.incomingRequestPatterns.isEmpty()) {
                config.initializeDefaultPatterns();
            }
            return config;
        } catch (Exception e) {
            LOGGER.error("[TpLockdown] Failed to load config, renaming corrupted file", e);
            try {
                File brokenFile = new File(file.getParentFile(), "tplockdown.json.broken");
                if (brokenFile.exists()) {
                    brokenFile.delete();
                }
                if (!file.renameTo(brokenFile)) {
                    file.delete();
                }
            } catch (Exception re) {
                LOGGER.error("[TpLockdown] Failed to rename or delete corrupted config file", re);
            }
            ModConfig defaultConfig = new ModConfig();
            defaultConfig.initializeDefaultPatterns();
            defaultConfig.save();
            return defaultConfig;
        }
    }

    private void initializeDefaultPatterns() {
        if (incomingRequestPatterns == null) {
            incomingRequestPatterns = new ArrayList<>();
        }
        incomingRequestPatterns.clear();
        // English default patterns
        incomingRequestPatterns.add("(?<player>\\w+)\\s+wants to teleport to you");
        incomingRequestPatterns.add("(?<player>\\w+)\\s+has requested to teleport to you");
        incomingRequestPatterns.add("(?<player>\\w+)\\s+wants you to teleport to them");
        incomingRequestPatterns.add("(?<player>\\w+)\\s+has requested that you teleport to them");
        incomingRequestPatterns.add("(?<player>\\w+)\\s+wants to tp to you");
        // Chinese default patterns
        incomingRequestPatterns.add("^\\s*\\[传送\\]\\s*(?<player>\\w+)\\s*想传送到你这里。");
        incomingRequestPatterns.add("^\\s*\\[传送\\]\\s*(?<player>\\w+)\\s*邀请你传送到他那里。");
    }

    public void save() {
        File file = getConfigFile();
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            LOGGER.error("[TpLockdown] Failed to save config", e);
        }
    }
}
