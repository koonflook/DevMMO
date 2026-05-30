package com.teenkung.devmmo.Utils;

import com.teenkung.devmmo.DevMMO;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ConfigLoader {

    private final DevMMO plugin;

    // FileConfigurations for each module
    private FileConfiguration modulesConfig;
    private FileConfiguration healthConfig;
    private FileConfiguration staminaConfig;
    private FileConfiguration fireworkBlockerConfig;
    private FileConfiguration mobXPConfig;
    private FileConfiguration expShareConfig;
    private FileConfiguration regionLevelConfig;
    private FileConfiguration bossDamageListConfig;
    private FileConfiguration auraSkillIntegrationConfig;
    private FileConfiguration mobStatScalerConfig;
    private FileConfiguration bossSpawnerConfig;

    public ConfigLoader(DevMMO plugin) {
        this.plugin = plugin;
        loadAllConfigs();
    }

    /**
     * Loads all configuration files.
     */
    private void loadAllConfigs() {
        // modules.yml
        modulesConfig = loadConfig("modules.yml");

        // Each module’s own config
        healthConfig = loadConfig("Modules/HealthModule.yml");
        staminaConfig = loadConfig("Modules/StaminaModule.yml");
        fireworkBlockerConfig = loadConfig("Modules/FireworkBlocker.yml");
        mobXPConfig = loadConfig("Modules/MobXPModule.yml");
        expShareConfig = loadConfig("Modules/EXPShareModule.yml");
        regionLevelConfig = loadConfig("Modules/RegionLevelModule.yml");
        bossDamageListConfig = loadConfig("Modules/BossDamageList.yml");
        auraSkillIntegrationConfig = loadConfig("Modules/AuraSkillIntegration.yml");
        mobStatScalerConfig = loadConfig("Modules/MobStatScaler.yml");
        bossSpawnerConfig = loadConfig("Modules/BossSpawner.yml");
    }

    /**
     * Loads a configuration file from the plugin's data folder.
     * @param filename The name of the file to load.
     * @return The loaded FileConfiguration, or null if an error occurred.
     */
    private FileConfiguration loadConfig(String filename) {
        File file = new File(plugin.getDataFolder(), filename);

        try {
            // Ensure parent directories exist
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    plugin.getLogger().severe("Failed to create directories for " + parentDir.getPath());
                    return null;
                }
            }

            // If the file doesn't exist, save the resource
            if (!file.exists()) {
                // Check if the resource exists within the JAR
                InputStream resourceStream = plugin.getResource(filename);
                if (resourceStream == null) {
                    plugin.getLogger().severe("Resource " + filename + " not found in JAR.");
                    return null;
                }

                // Save the resource to the file
                Files.copy(resourceStream, file.toPath());
                resourceStream.close();
            }

            FileConfiguration config = YamlConfiguration.loadConfiguration(file);

            try (InputStream defStream = plugin.getResource(filename)) {
                if (defStream != null) {
                    FileConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defStream, StandardCharsets.UTF_8));
                    boolean changed = mergeDefaults(config, defConfig);
                    if (changed) {
                        config.save(file);
                    }
                }
            }

            return config;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not load configuration file " + filename);
            return null;
        }
    }

    private boolean mergeDefaults(FileConfiguration config, FileConfiguration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (!config.contains(key)) {
                config.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Check if a module is enabled in the modules.yml configuration.
     * @param moduleName The name of the module to check.
     * @return True if the module is enabled, false otherwise.
     */
    public boolean isModuleEnabled(String moduleName) {
        return modulesConfig.getBoolean(moduleName + ".Enabled", false);
    }

    /**
     * Gets the FileConfiguration for HealthModule.
     * @return The HealthModule configuration.
     */
    public Configuration getHealthConfig() {
        return healthConfig;
    }

    /**
     * Gets the FileConfiguration for StaminaModule.
     * @return The StaminaModule configuration.
     */
    public Configuration getStaminaConfig() {
        return staminaConfig;
    }

    /**
     * Gets the FileConfiguration for FireworkBlocker.
     * @return The FireworkBlocker configuration.
     */
    public Configuration getFireworkBlockerConfig() {
        return fireworkBlockerConfig;
    }

    /**
     * Gets the FileConfiguration for MobXPModule.
     * @return The MobXPModule configuration.
     */
    public Configuration getMobXPConfig() {
        return mobXPConfig;
    }

    /**
     * Gets the FileConfiguration for EXPShareModule.
     * @return The EXPShareModule configuration.
     */
    public Configuration getExpShareConfig() {
        return expShareConfig;
    }

    /**
     * Gets the FileConfiguration for RegionLevelModule.
     * @return The RegionLevelModule configuration.
     */
    public Configuration getRegionLevelConfig() {
        return regionLevelConfig;
    }

    /**
     * Gets the FileConfiguration for BossDamageListModule.
     * @return The BossDamageListModule configuration.
     */
    public Configuration getBossDamageListConfig() {
        return bossDamageListConfig;
    }

    /**
     * Gets the FileConfiguration for AuraSkillIntegration.
     * @return The AuraSkillIntegration configuration.
     */
    public Configuration getAuraSkillIntegrationConfig() {
        return auraSkillIntegrationConfig;
    }

    /**
     * Gets the FileConfiguration for MobStatScaler.
     * @return The MobStatScaler configuration.
     */
    public Configuration getMobStatScalerConfig() {
        return mobStatScalerConfig;
    }

    /**
     * Gets the FileConfiguration for BossSpawner.
     * @return The BossSpawner configuration.
     */
    public Configuration getBossSpawnerConfig() {
        return bossSpawnerConfig;
    }
}
