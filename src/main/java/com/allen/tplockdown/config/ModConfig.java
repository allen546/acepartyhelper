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

    public static ModConfig load() {
        configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "tplockdown.json");
        if (!configFile.exists()) {
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
        try (FileReader reader = new FileReader(configFile)) {
            ModConfig config = GSON.fromJson(reader, ModConfig.class);
            if (config.incomingRequestPatterns == null) {
                config.incomingRequestPatterns = new ArrayList<>();
            }
            if (config.manualParty == null) {
                config.manualParty = new HashSet<>();
            }
            return config;
        } catch (Exception e) {
            e.printStackTrace();
            return new ModConfig();
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
