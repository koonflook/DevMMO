package com.teenkung.devmmo.Modules;

import com.Teenkung.devDamageHandler.API.DamageData;
import com.Teenkung.devDamageHandler.API.Events.PlayerDamageCalculatedEvent;
import com.teenkung.devmmo.DevMMO;
import com.teenkung.devmmo.Utils.ColorTranslator;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.bukkit.events.MythicMobDespawnEvent;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class BossDamageList implements Listener {

    private final DevMMO plugin;
    private final Set<String> bossIds = new HashSet<>();

    // bossEntityUUID -> (playerUUID -> damage)
    private final Map<UUID, Map<UUID, Double>> damageMap = new ConcurrentHashMap<>();

    // Entity UUIDs force-tracked by BossSpawner (bypasses bossIds check)
    private final Set<UUID> forceTrackedBosses = ConcurrentHashMap.newKeySet();

    // bossId -> (place -> commands)
    private final Map<String, Map<Integer, List<String>>> rewards = new HashMap<>();

    // bossId -> commands
    private final Map<String, List<String>> defaultRewards = new HashMap<>();

    // bossId -> minimum damage
    private final Map<String, Double> damageThreshold = new HashMap<>();

    // bossId -> random reward entries
    private final Map<String, List<RandomRewardEntry>> randomRewards = new HashMap<>();

    // bossId -> whether players below threshold still receive random rewards
    private final Map<String, Boolean> randomRewardsBelowThreshold = new HashMap<>();

    private String killMessage;
    private String leaderBoardMessage;

    private int cleanupTaskId = -1;

    /**
     * Represents a single random reward entry with a chance and list of commands.
     */
    private record RandomRewardEntry(double chance, List<String> commands) {}

    public BossDamageList(DevMMO plugin) {
        this.plugin = plugin;

        if (!plugin.getConfigLoader().isModuleEnabled("BossDamageList")) return;

        plugin.getLogger().info("[BossDamageList] Enabling module. . .");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        loadConfigSafe();

        // Cleanup stale entries periodically (fixes "boss vanished without despawn/death" cases)
        startCleanupTask();

        plugin.getLogger().info("[BossDamageList] Enabled module.");
    }

    private void loadConfigSafe() {
        Configuration config = plugin.getConfigLoader().getBossDamageListConfig();

        this.killMessage = config.getString("BossDamageList.KillMessage", "<gray><name> was defeated!");
        this.leaderBoardMessage = config.getString(
                "BossDamageList.Leaderboard",
                "<gray>#<place> <yellow><name><gray>: <red><damage>"
        );

        ConfigurationSection bossesSec = config.getConfigurationSection("BossDamageList.Bosses");
        if (bossesSec == null) {
            plugin.getLogger().warning("[BossDamageList] Missing config section: BossDamageList.Bosses");
            return;
        }

        for (String bossId : bossesSec.getKeys(false)) {
            ConfigurationSection bossCfg = bossesSec.getConfigurationSection(bossId);
            if (bossCfg == null) continue;

            bossIds.add(bossId);

            // Rewards
            Map<Integer, List<String>> rewardMap = new HashMap<>();
            ConfigurationSection rewardsSec = bossCfg.getConfigurationSection("Rewards");
            if (rewardsSec != null) {
                for (String placeKey : rewardsSec.getKeys(false)) {
                    try {
                        int place = Integer.parseInt(placeKey);
                        List<String> cmds = rewardsSec.getStringList(placeKey);
                        rewardMap.put(place, cmds != null ? cmds : List.of());
                    } catch (NumberFormatException ignored) {
                        plugin.getLogger().warning("[BossDamageList] Invalid place '" + placeKey + "' in boss " + bossId);
                    }
                }
            }
            rewards.put(bossId, rewardMap);

            // Default rewards + threshold
            List<String> def = bossCfg.getStringList("DefaultRewards");
            defaultRewards.put(bossId, def != null ? def : List.of());

            damageThreshold.put(bossId, bossCfg.getDouble("MinimumDamageThreshold", 0.0));

            // Random rewards
            List<RandomRewardEntry> randomEntries = new ArrayList<>();
            List<?> randomList = bossCfg.getList("RandomRewards");
            if (randomList != null) {
                for (Object obj : randomList) {
                    if (!(obj instanceof Map<?, ?> entryMap)) continue;

                    Object chanceObj = entryMap.get("chance");
                    Object cmdsObj  = entryMap.get("commands");

                    if (chanceObj == null || cmdsObj == null) {
                        plugin.getLogger().warning("[BossDamageList] RandomRewards entry missing 'chance' or 'commands' in boss " + bossId);
                        continue;
                    }

                    double chance;
                    try {
                        chance = Double.parseDouble(chanceObj.toString());
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("[BossDamageList] Invalid chance value '" + chanceObj + "' in boss " + bossId);
                        continue;
                    }

                    if (chance <= 0 || chance > 100) {
                        plugin.getLogger().warning("[BossDamageList] Chance must be between 0 and 100, got " + chance + " in boss " + bossId);
                        continue;
                    }

                    List<String> cmds = new ArrayList<>();
                    if (cmdsObj instanceof List<?> cmdList) {
                        for (Object c : cmdList) {
                            if (c != null) cmds.add(c.toString());
                        }
                    }

                    randomEntries.add(new RandomRewardEntry(chance, cmds));
                }
            }
            randomRewards.put(bossId, randomEntries);

            // Whether players below threshold still get random rewards
            randomRewardsBelowThreshold.put(bossId, bossCfg.getBoolean("RandomRewardsBelowThreshold", false));
        }
    }

    private void startCleanupTask() {
        // every 60s (20 ticks * 60)
        cleanupTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Remove entries whose entity no longer exists / no longer mythic mob
            Iterator<UUID> it = damageMap.keySet().iterator();
            while (it.hasNext()) {
                UUID entityId = it.next();
                Entity e = Bukkit.getEntity(entityId);
                if (e == null) {
                    it.remove();
                    continue;
                }
                if (!MythicBukkit.inst().getMobManager().isMythicMob(e)) {
                    it.remove();
                }
            }
        }, 20L * 60, 20L * 60).getTaskId();
    }

    /**
     * Force-track a specific entity UUID so BossDamageList accumulates damage for it
     * even if the mob ID is not listed in BossDamageList.Bosses config.
     * Called by BossSpawner when LinkBossDamageList is true.
     */
    public void forceTrack(UUID entityUUID) {
        forceTrackedBosses.add(entityUUID);
        damageMap.putIfAbsent(entityUUID, new ConcurrentHashMap<>());
    }

    /** Call this from your plugin onDisable if you want it extra safe. */
    public void shutdown() {
        if (cleanupTaskId != -1) Bukkit.getScheduler().cancelTask(cleanupTaskId);
        damageMap.clear();
        forceTrackedBosses.clear();
    }

    @EventHandler
    public void onSpawn(MythicMobSpawnEvent event) {
        String mobId = event.getMob().getType().getInternalName();
        if (!bossIds.contains(mobId)) return;

        // Pre-create map (not required anymore, but fine)
        damageMap.putIfAbsent(event.getEntity().getUniqueId(), new ConcurrentHashMap<>());
    }

    @EventHandler
    public void onDamage(PlayerDamageCalculatedEvent event) {
        // If this event can fire async, hop to main thread before touching Bukkit + maps
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> onDamage(event));
            return;
        }

        Entity target = event.getVictim();

        if (!MythicBukkit.inst().getMobManager().isMythicMob(target)) return;

        ActiveMob activeMob = MythicBukkit.inst().getMobManager().getActiveMob(target.getUniqueId()).orElse(null);
        if (activeMob == null) return;

        String bossId = activeMob.getType().getInternalName();
        UUID bossEntityId = target.getUniqueId();
        if (!bossIds.contains(bossId) && !forceTrackedBosses.contains(bossEntityId)) return;

        Player attacker = event.getAttacker();

        DamageData damageData = event.getDamageData();

        double damage = damageData.getMetaDamage();
        if (damage <= 0) return;

        UUID playerId = attacker.getUniqueId();

        // Lazy init (fixes "boss existed before plugin enabled / spawn missed")
        Map<UUID, Double> bossDamages = damageMap.computeIfAbsent(bossEntityId, k -> new ConcurrentHashMap<>());

        // accumulate
        bossDamages.merge(playerId, damage, Double::sum);
    }

    @EventHandler
    public void onDeath(MythicMobDeathEvent event) {
        UUID bossEntityId = event.getEntity().getUniqueId();
        forceTrackedBosses.remove(bossEntityId);
        Map<UUID, Double> bossDamages = damageMap.remove(bossEntityId);
        if (bossDamages == null || bossDamages.isEmpty()) return;

        String bossId = event.getMob().getType().getInternalName();

        // Sort by descending damage
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(bossDamages.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // Broadcast to nearby players (intended)
        Collection<Player> nearby = event.getEntity().getWorld().getNearbyPlayers(event.getEntity().getLocation(), 100);

        Component killmsg = MiniMessage.miniMessage().deserialize(
                killMessage,
                Placeholder.component(
                        "name",
                        MiniMessage.miniMessage().deserialize(ColorTranslator.toMiniMessageFormat(event.getEntity().getName()))
                )
        );

        List<Component> boards = new ArrayList<>();

        double threshold = damageThreshold.getOrDefault(bossId, 0.0);
        Map<Integer, List<String>> bossRewardMap = rewards.getOrDefault(bossId, Map.of());
        List<String> bossDefaultRewards = defaultRewards.getOrDefault(bossId, List.of());
        List<RandomRewardEntry> bossRandomRewards = randomRewards.getOrDefault(bossId, List.of());
        boolean allowBelowThreshold = randomRewardsBelowThreshold.getOrDefault(bossId, false);

        for (int i = 1; i <= sorted.size(); i++) {
            UUID playerId = sorted.get(i - 1).getKey();
            double dmg = sorted.get(i - 1).getValue();

            // Name for leaderboard (prefer online name, fallback to offline name or UUID)
            Player online = Bukkit.getPlayer(playerId);
            String name = (online != null) ? online.getName() : Optional.ofNullable(Bukkit.getOfflinePlayer(playerId).getName()).orElse(playerId.toString());

            boards.add(MiniMessage.miniMessage().deserialize(
                    leaderBoardMessage,
                    Placeholder.unparsed("name", ColorTranslator.toMiniMessageFormat(name)),
                    Placeholder.unparsed("damage", String.format(Locale.US, "%.2f", dmg)),
                    Placeholder.unparsed("place", String.valueOf(i))
            ));

            boolean aboveThreshold = dmg >= threshold;

            // Place rewards + Default rewards (threshold required)
            if (aboveThreshold) {
                // Place rewards
                List<String> placeCmds = bossRewardMap.get(i);
                if (placeCmds != null) {
                    for (String cmd : placeCmds) {
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd.replace("<player>", name));
                    }
                }

                // Default rewards
                for (String cmd : bossDefaultRewards) {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd.replace("<player>", name));
                }
            }

            // Random rewards — give if above threshold, or if below threshold is allowed
            if (aboveThreshold || allowBelowThreshold) {
                giveRandomRewards(bossRandomRewards, name);
            }
        }

        for (Player p : nearby) {
            p.sendMessage(killmsg);
            for (Component board : boards) {
                p.sendMessage(board);
            }
        }
    }

    /**
     * Rolls each RandomRewardEntry independently and dispatches commands if the roll succeeds.
     *
     * @param entries List of random reward entries for this boss.
     * @param playerName The player's name to substitute into commands.
     */
    private void giveRandomRewards(List<RandomRewardEntry> entries, String playerName) {
        if (entries.isEmpty()) return;
        Random random = ThreadLocalRandom.current();
        for (RandomRewardEntry entry : entries) {
            double roll = random.nextDouble() * 100.0;
            if (roll < entry.chance()) {
                for (String cmd : entry.commands()) {
                    plugin.getServer().dispatchCommand(
                            plugin.getServer().getConsoleSender(),
                            cmd.replace("<player>", playerName)
                    );
                }
            }
        }
    }

    @EventHandler
    public void onMythicMobDespawn(MythicMobDespawnEvent event) {
        UUID id = event.getEntity().getUniqueId();
        damageMap.remove(id);
        forceTrackedBosses.remove(id);
    }
}
