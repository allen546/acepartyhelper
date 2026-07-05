package com.allen.tplockdown.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;

    public Set<String> manualParty = new HashSet<>();
    public List<String> incomingRequestPatterns = new ArrayList<>();
    public String partyChatPattern = "^\\[队内\\]\\s*(?<player>\\w+)";

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
            // Standard default teleport patterns
            config.incomingRequestPatterns.add("(?<player>\\w+)\\s+wants to teleport to you");
            config.incomingRequestPatterns.add("(?<player>\\w+)\\s+has requested to teleport to you");
            config.incomingRequestPatterns.add("(?<player>\\w+)\\s+wants you to teleport to them");
            config.incomingRequestPatterns.add("(?<player>\\w+)\\s+has requested that you teleport to them");
            config.incomingRequestPatterns.add("(?<player>\\w+)\\s+wants to tp to you");
            config.save();
            return config;
        }
        try (FileReader reader = new FileReader(file)) {
            ModConfig config = GSON.fromJson(reader, ModConfig.class);
            if (config == null) {
                config = new ModConfig();
            }
            if (config.incomingRequestPatterns == null) {
                config.incomingRequestPatterns = new ArrayList<>();
            }
            if (config.manualParty == null) {
                config.manualParty = new HashSet<>();
            }
            return config;
        } catch (Exception e) {
            System.err.println("[TpLockdown] Failed to load config, renaming corrupted file: " + e.getMessage());
            try {
                File brokenFile = new File(file.getParentFile(), "tplockdown.json.broken");
                if (brokenFile.exists()) {
                    brokenFile.delete();
                }
                file.renameTo(brokenFile);
            } catch (Exception re) {
                System.err.println("[TpLockdown] Failed to rename corrupted config file: " + re.getMessage());
            }
            return load();
        }
    }

    public void save() {
        File file = getConfigFile();
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            System.err.println("[TpLockdown] Failed to save config: " + e.getMessage());
        }
    }
}
