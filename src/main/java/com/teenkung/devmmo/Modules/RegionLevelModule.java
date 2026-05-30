package com.teenkung.devmmo.Modules;

import com.teenkung.devmmo.DevMMO;
import com.teenkung.devmmo.Utils.RegionLevelUtils.LevelWeight;
import com.teenkung.devmmo.Utils.RegionLevelUtils.RegionLevelRecord;
import com.teenkung.devmmo.Utils.WorldGuardUtils;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Silverfish;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class RegionLevelModule implements Listener {

    private final DevMMO plugin;
    private final Map<String, RegionLevelRecord> regionLevelRecords = new HashMap<>();
    private final Map<String, String> mobNameFormats = new HashMap<>();
    private final Set<String> blacklistedWorlds = new HashSet<>();

    // True only during synchronous processing of a /smm command on the main thread
    private boolean isSmmSpawning = false;

    public RegionLevelModule(DevMMO plugin) {
        this.plugin = plugin;
        if (plugin.getConfigLoader().isModuleEnabled("RegionLevel")) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            loadRegionLevelConfig();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().trim().toLowerCase();
        if (msg.startsWith("/smm ") || msg.equals("/smm")) {
            isSmmSpawning = true;
            Bukkit.getScheduler().runTask(plugin, () -> isSmmSpawning = false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        String cmd = event.getCommand().trim().toLowerCase();
        if (cmd.startsWith("smm ") || cmd.equals("smm")) {
            isSmmSpawning = true;
            Bukkit.getScheduler().runTask(plugin, () -> isSmmSpawning = false);
        }
    }

    @EventHandler
    public void onSpawn(MythicMobSpawnEvent event) {
        if (isSmmSpawning) return;

        Location location = event.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        String worldName = location.getWorld().getName();
        if (worldName != null && blacklistedWorlds.contains(worldName.toLowerCase(Locale.ROOT))) {
            return;
        }

        // Grab the mobType from the event
        String mobType = getMythicMobType(event.getEntity());
        if (mobType == null) {
            mobType = event.getMob().getMobType();
        }

        for (Map.Entry<String, RegionLevelRecord> entry : regionLevelRecords.entrySet()) {
            String region = entry.getKey();
            RegionLevelRecord regionRecord = entry.getValue();

            // Check if the location is inside this region
            if (WorldGuardUtils.isInRegion(location, region)) {
                // Check for a custom record for this specific mobType
                RegionLevelRecord customRecord = regionRecord.getCustomRecords().get(mobType);

                // Use custom record if available, otherwise fallback to region record
                RegionLevelRecord finalRecord = (customRecord != null) ? customRecord : regionRecord;

                // Set the mob’s level based on the weighted random from the final record
                event.setMobLevel(getWeightedLevel(finalRecord));
                event.getMob().getEntity().setNoDamageTicks(1);
                if (event.getMob().getDisplayName() != null && !event.getMob().getDisplayName().isEmpty() && !event.getMob().getDisplayName().isBlank() && !event.getMob().getDisplayName().equalsIgnoreCase("silverfish")) {
                    if (event.getMob().getEntity().getBukkitEntity() instanceof Silverfish) {
                        break;
                    }
                    int levelInt = (int) event.getMobLevel();
                    event.getMob().setLevel(levelInt);
                    String rawName;

                    if (event.getMob().getType().getDisplayName() != null) {
                        rawName = event.getMob().getType().getDisplayName().get(event.getMob());
                    } else {
                        rawName = event.getMob().getType().getInternalName();
                    }

                    if (rawName == null || rawName.isBlank()) {
                        rawName = event.getMob().getEntity().getBukkitEntity().getName();
                    }

                    String template = mobNameFormats.getOrDefault(
                            mobType.toLowerCase(),
                            mobNameFormats.getOrDefault("default", "&aLv.&e<level> &r&f<name>")
                    );

                    String finalName = template
                            .replace("<level>", String.valueOf(levelInt))
                            .replace("<name>", rawName);

                    event.getMob().setDisplayName(finalName);
                }
                break;
            }
        }
    }

    /**
     * This method selects a random level based on the weighted chances defined in the record.
     *
     * @param record The record of the region (or custom override).
     * @return The level of the mob.
     */
    public int getWeightedLevel(RegionLevelRecord record) {
        if (record.getTotalWeight() == 0.0) {
            return record.getBaseLevel();
        }

        double rand = ThreadLocalRandom.current().nextDouble() * record.getTotalWeight();
        return record.getCumulativeMap().ceilingEntry(rand).getValue();
    }

    /**
     * Load the RegionLevel config.
     */
    public void loadRegionLevelConfig() {
        ConfigurationSection config = plugin.getConfigLoader().getRegionLevelConfig().getConfigurationSection("RegionLevel");
        if (config == null) {
            plugin.getLogger().warning("RegionsLevel config not found!");
            return;
        }

        boolean debugMode = config.getBoolean("DebugMode", false);
        if (debugMode) {
            plugin.getLogger().info("Debug Mode enabled for RegionLevelModule!");
        }

        regionLevelRecords.clear();

        mobNameFormats.clear();
        Object nameObj = config.get("MobName");
        if (nameObj instanceof ConfigurationSection section) {
            for (String key : section.getKeys(false)) {
                String fmt = section.getString(key);
                if (fmt != null) {
                    mobNameFormats.put(key.toLowerCase(), fmt);
                }
            }
        } else {
            String def = config.getString("MobName", "&aLv.&e<level> &r&f<name>");
            mobNameFormats.put("default", def);
        }

        blacklistedWorlds.clear();
        for (String world : config.getStringList("WorldBlacklist")) {
            if (world != null) {
                String normalized = world.trim();
                if (!normalized.isEmpty()) {
                    blacklistedWorlds.add(normalized.toLowerCase(Locale.ROOT));
                }
            }
        }
        ConfigurationSection worldSection = config.getConfigurationSection("Worlds");
        if (worldSection != null) {
            for (String name : worldSection.getStringList("Blacklist")) {
                if (name != null) {
                    String normalized = name.trim();
                    if (!normalized.isEmpty()) {
                        blacklistedWorlds.add(normalized.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        ConfigurationSection regions = config.getConfigurationSection("Regions");
        if (regions == null) {
            plugin.getLogger().warning("RegionsLevel Regions config not found!");
            return;
        }

        for (String regionKey : regions.getKeys(false)) {
            plugin.getLogger().info("Loading Region: " + regionKey);

            // Load BaseLevel
            int baseLevel = regions.getInt(regionKey + ".BaseLevel", 1);

            // Load Weight Section
            ConfigurationSection weightSection = regions.getConfigurationSection(regionKey + ".Weight");
            if (weightSection == null) {
                plugin.getLogger().warning("Weight config not found for region: " + regionKey);
                continue;
            }

            // Parse the primary weight definitions
            List<LevelWeight> regionWeights = parseWeights(weightSection, regionKey);

            // Build the main region record
            RegionLevelRecord regionRecord = new RegionLevelRecord(regionKey, baseLevel, regionWeights);

            // Check if there is a Custom section for this region
            ConfigurationSection customSection = regions.getConfigurationSection(regionKey + ".Custom");
            if (customSection != null) {
                for (String mobType : customSection.getKeys(false)) {
                    plugin.getLogger().info("  Found custom mob config: " + mobType);

                    int customBaseLevel = customSection.getInt(mobType + ".BaseLevel", baseLevel);
                    ConfigurationSection customWeightSection = customSection.getConfigurationSection(mobType + ".Weight");

                    if (customWeightSection != null) {
                        List<LevelWeight> customWeights = parseWeights(customWeightSection, mobType);
                        RegionLevelRecord customRecord = new RegionLevelRecord(mobType, customBaseLevel, customWeights);
                        regionRecord.getCustomRecords().put(mobType, customRecord);
                    } else {
                        plugin.getLogger().warning("  No 'Weight' found for custom mob: " + mobType + " in region: " + regionKey);
                    }
                }
            }

            // Store the region record
            regionLevelRecords.put(regionKey, regionRecord);
        }

        plugin.getLogger().info("Region Level Config loaded!");
    }

    /**
     * Utility method to parse weight entries (either single levels or ranges).
     */
    private List<LevelWeight> parseWeights(ConfigurationSection weightSection, String parentKey) {
        List<LevelWeight> parsedWeights = new ArrayList<>();
        for (String levelOrRange : weightSection.getKeys(false)) {
            if (levelOrRange.contains("-")) {
                // If the config key is a range like "3-10"
                String[] split = levelOrRange.split("-");
                if (split.length != 2) {
                    plugin.getLogger().warning("Invalid range format for: " + levelOrRange + " in: " + parentKey);
                    continue;
                }
                try {
                    int min = Integer.parseInt(split[0]);
                    int max = Integer.parseInt(split[1]);
                    if (min > max) {
                        plugin.getLogger().warning("Min level greater than max level in range: " + levelOrRange + " in: " + parentKey);
                        continue;
                    }
                    double rangeWeight = weightSection.getDouble(levelOrRange);
                    if (rangeWeight < 0) {
                        plugin.getLogger().warning("Negative weight for range: " + levelOrRange + " in: " + parentKey);
                        continue;
                    }
                    // Split the weight evenly across all levels in the range
                    int rangeSize = max - min + 1;
                    double perLevelWeight = rangeWeight / rangeSize;

                    for (int i = min; i <= max; i++) {
                        parsedWeights.add(new LevelWeight(i, perLevelWeight));
                    }
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid number format in range: " + levelOrRange + " in: " + parentKey);
                }
            } else {
                // If the config key is a single level like "2"
                try {
                    int singleLevel = Integer.parseInt(levelOrRange);
                    double levelWeight = weightSection.getDouble(levelOrRange);
                    if (levelWeight < 0) {
                        plugin.getLogger().warning("Negative weight for level: " + levelOrRange + " in: " + parentKey);
                        continue;
                    }
                    parsedWeights.add(new LevelWeight(singleLevel, levelWeight));
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid level number: " + levelOrRange + " in: " + parentKey);
                }
            }
        }
        return parsedWeights;
    }

    /**
     * Returns the MythicMobs type for the entity if relevant.
     */
    private String getMythicMobType(org.bukkit.entity.Entity entity) {
        try {
            if (io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().isMythicMob(entity)) {
                return io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager()
                        .getMythicMobInstance(entity)
                        .getMobType();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
