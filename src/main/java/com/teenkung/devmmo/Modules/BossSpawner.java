package com.teenkung.devmmo.Modules;

import com.teenkung.devmmo.DevMMO;
import com.teenkung.devmmo.Utils.ColorTranslator;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class BossSpawner {

    private final DevMMO plugin;
    private final List<Integer> scheduledTaskIds = new ArrayList<>();

    private record BossEntry(String id, int level) {}
    private record ChunkRef(UUID worldId, int x, int z) {}
    /** A SpecificTimes entry that may optionally pin a specific boss ID. */
    private record SpecificTimeEntry(LocalTime time, String bossId) {}

    /* ---------- runtime state ---------- */

    private UUID activeBossUUID = null;

    // Set when a spawn has been announced but the location isn't valid yet
    private BossEntry pendingEntry       = null;
    private Location  pendingLocation    = null;
    private String    pendingDisplayName = null;
    private String    pendingLocationStr = null;
    private ChunkRef  pendingReservedChunk = null;

    // Reminder task ID (-1 = not running)
    private int reminderTaskId = -1;
    private int activeChunkFollowTaskId = -1;

    // Current 3x3 forced chunk window around active boss
    private final Set<ChunkRef> activeChunkTickets = new HashSet<>();
    private ChunkRef activeCenterChunk = null;

    // Players who have opted out of reminder messages (persists across boss cycles)
    private final Set<UUID> reminderOptOuts = new HashSet<>();

    /* ---------- config ---------- */

    private List<BossEntry> bosses         = new ArrayList<>();
    private List<Location>  locations      = new ArrayList<>();
    private List<String>    spawnBroadcast = new ArrayList<>();
    private List<String>    spawnCommands  = new ArrayList<>();
    private boolean         linkBossDamageList = false;
    private long            intervalTicks  = 0;
    private List<SpecificTimeEntry> specificTimeEntries = new ArrayList<>();
    private double          nearbyPlayerRadius  = 64.0;
    private long            reminderIntervalTicks = 0;
    private long            reminderTimeoutTicks  = 0;

    // Refresh every 5 seconds; immediate recenter still happens when boss crosses chunk.
    private static final long ACTIVE_CHUNK_REFRESH_TICKS = 100L;

    /* ----------------------------------- */

    public BossSpawner(DevMMO plugin) {
        this.plugin = plugin;
        if (!plugin.getConfigLoader().isModuleEnabled("BossSpawner")) return;

        plugin.getLogger().info("[BossSpawner] Enabling module...");
        loadConfig();
        scheduleSpawns();
        plugin.getLogger().info("[BossSpawner] Module enabled.");
    }

    /* ---------------------------------------------------------------------- */
    /*  Config                                                                  */
    /* ---------------------------------------------------------------------- */

    private void loadConfig() {
        var cfg = plugin.getConfigLoader().getBossSpawnerConfig();
        if (cfg == null) {
            plugin.getLogger().warning("[BossSpawner] Config is null.");
            return;
        }

        ConfigurationSection sec = cfg.getConfigurationSection("BossSpawner");
        if (sec == null) {
            plugin.getLogger().warning("[BossSpawner] Missing 'BossSpawner' config section.");
            return;
        }

        bosses = new ArrayList<>();
        ConfigurationSection bossesSec = sec.getConfigurationSection("Bosses");
        if (bossesSec != null) {
            for (String bossId : bossesSec.getKeys(false)) {
                int level = bossesSec.getInt(bossId + ".Level", 1);
                bosses.add(new BossEntry(bossId, level));
            }
        }

        locations = new ArrayList<>();
        for (String locStr : sec.getStringList("Locations")) {
            Location loc = parseLocation(locStr);
            if (loc != null) {
                locations.add(loc);
            } else {
                plugin.getLogger().warning("[BossSpawner] Invalid location: " + locStr + " (expected x:y:z:world)");
            }
        }

        spawnBroadcast     = sec.getStringList("SpawnBroadcast");
        spawnCommands      = sec.getStringList("SpawnCommands");
        linkBossDamageList = sec.getBoolean("LinkBossDamageList", false);
        nearbyPlayerRadius    = sec.getDouble("NearbyPlayerRadius", 64.0);
        long reminderMinutes  = sec.getLong("ReminderInterval", 0);
        reminderIntervalTicks = reminderMinutes * 60L * 20L;
        long reminderTimeoutMinutes = sec.getLong("ReminderTimeout", 0);
        reminderTimeoutTicks = reminderTimeoutMinutes * 60L * 20L;

        specificTimeEntries = new ArrayList<>();
        List<?> timesList = sec.getList("SpecificTimes");
        if (timesList != null) {
            for (Object obj : timesList) {
                if (obj instanceof String timeStr) {
                    // Legacy format: "HH:mm"
                    try {
                        specificTimeEntries.add(new SpecificTimeEntry(LocalTime.parse(timeStr.trim()), null));
                    } catch (Exception e) {
                        plugin.getLogger().warning("[BossSpawner] Invalid time format: " + timeStr + " (expected HH:mm)");
                    }
                } else if (obj instanceof java.util.Map<?, ?> map) {
                    // New format: {time: "HH:mm", boss: "boss_id"}
                    Object timeObj = map.get("time");
                    Object bossObj = map.get("boss");
                    if (timeObj == null) {
                        plugin.getLogger().warning("[BossSpawner] SpecificTimes entry missing 'time' field.");
                        continue;
                    }
                    try {
                        LocalTime t = LocalTime.parse(timeObj.toString().trim());
                        String pinnedBoss = (bossObj != null) ? bossObj.toString() : null;
                        specificTimeEntries.add(new SpecificTimeEntry(t, pinnedBoss));
                    } catch (Exception e) {
                        plugin.getLogger().warning("[BossSpawner] Invalid time format: " + timeObj + " (expected HH:mm)");
                    }
                }
            }
        }

        long intervalSeconds = sec.getLong("Interval", 0);
        intervalTicks = intervalSeconds * 20L;
    }

    /* ---------------------------------------------------------------------- */
    /*  Scheduling                                                              */
    /* ---------------------------------------------------------------------- */

    private void scheduleSpawns() {
        // Pending-spawn check every 5 seconds
        int checkId = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::checkPendingSpawn, 100L, 100L)
                .getTaskId();
        scheduledTaskIds.add(checkId);

        startActiveChunkFollowTask();

        if (!specificTimeEntries.isEmpty()) {
            for (SpecificTimeEntry entry : specificTimeEntries) scheduleAtTime(entry);
        } else if (intervalTicks > 0) {
            BukkitTask task = Bukkit.getScheduler()
                    .runTaskTimer(plugin, () -> this.spawnBoss(null, false), intervalTicks, intervalTicks);
            scheduledTaskIds.add(task.getTaskId());
        } else {
            plugin.getLogger().warning("[BossSpawner] No Interval or SpecificTimes configured — bosses will not spawn automatically.");
        }
    }

    private void scheduleAtTime(SpecificTimeEntry entry) {
        LocalTime now = LocalTime.now();
        long secondsUntil = now.until(entry.time(), ChronoUnit.SECONDS);
        if (secondsUntil <= 0) secondsUntil += 86400L;
        long ticksUntil = secondsUntil * 20L;

        int[] taskRef = {-1};
        taskRef[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduledTaskIds.remove((Integer) taskRef[0]);
            spawnBoss(entry.bossId(), false);
            scheduleAtTime(entry);
        }, ticksUntil).getTaskId();
        scheduledTaskIds.add(taskRef[0]);
    }

    /* ---------------------------------------------------------------------- */
    /*  Public API                                                              */
    /* ---------------------------------------------------------------------- */

    /** Force-announces and spawns a random boss, bypassing the active-boss check. */
    public void spawnBoss() {
        spawnBoss(null, true);
    }

    /** Force-announces and spawns a specific boss by ID, bypassing the active-boss check. */
    public void spawnBoss(String bossId) {
        spawnBoss(bossId, true);
    }

    public List<String> getConfiguredBossIds() {
        return bosses.stream().map(BossEntry::id).toList();
    }

    /* ---------------------------------------------------------------------- */
    /*  Internal spawn pipeline                                                 */
    /* ---------------------------------------------------------------------- */

    private void spawnBoss(String bossId, boolean force) {
        // Scheduled cycles will replace an existing active boss, but only if no players are fighting it.
        if (!force) {
            Entity existing = getActiveBossEntity();
            if (existing != null) {
                // Check if there are players nearby (actively fighting)
                if (!existing.getLocation().getWorld().getNearbyPlayers(existing.getLocation(), nearbyPlayerRadius).isEmpty()) {
                    plugin.getLogger().info("[BossSpawner] Active boss still has players fighting it. Deferring new spawn until next schedule.");
                    return;
                }
                existing.remove();
                plugin.getLogger().info("[BossSpawner] Previous active boss removed for next scheduled spawn.");
            }
            clearActiveBoss();
        }

        if (bosses.isEmpty()) {
            plugin.getLogger().warning("[BossSpawner] No bosses configured.");
            return;
        }
        if (locations.isEmpty()) {
            plugin.getLogger().warning("[BossSpawner] No locations configured.");
            return;
        }

        // Don't stack pending spawns
        if (pendingEntry != null) {
            plugin.getLogger().info("[BossSpawner] Spawn already announced and pending — skipping duplicate trigger.");
            return;
        }

        BossEntry entry = resolveEntry(bossId);
        if (entry == null) return;

        MythicMob mmob = MythicBukkit.inst().getMobManager().getMythicMob(entry.id()).orElse(null);
        if (mmob == null) {
            plugin.getLogger().warning("[BossSpawner] MythicMob not found: " + entry.id());
            return;
        }

        // Pick the target location at trigger time so the announcement is accurate
        Location targetLoc = locations.get(ThreadLocalRandom.current().nextInt(locations.size()));
        String displayName = resolveDisplayName(mmob, entry.id());
        String locationStr = formatLocation(targetLoc);

        // Announce immediately regardless of whether the chunk is loaded
        runAnnouncement(displayName, locationStr);

        if (isLocationValid(targetLoc)) {
            doSpawn(entry, mmob, targetLoc, displayName, locationStr);
        } else {
            pendingEntry       = entry;
            pendingLocation    = targetLoc;
            pendingDisplayName = displayName;
            pendingLocationStr = locationStr;
            if (!force) {
                reservePendingSpawnChunk(targetLoc);
            }
            startReminderTask();
            plugin.getLogger().info("[BossSpawner] " + entry.id() +
                    " announced — waiting for a player near " + locationStr);
        }
    }

    private void startReminderTask() {
        if (reminderIntervalTicks <= 0) return;
        cancelReminder();
        final long[] elapsedTicks = {0};
        reminderTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            elapsedTicks[0] += reminderIntervalTicks;
            if (pendingEntry == null) { cancelReminder(); return; }
            if (reminderTimeoutTicks > 0 && elapsedTicks[0] > reminderTimeoutTicks) {
                plugin.getLogger().info("[BossSpawner] Reminder timed out after " + (elapsedTicks[0] / 20) + "s.");
                cancelReminder();
                return;
            }
            runReminderAnnouncement(pendingDisplayName, pendingLocationStr);
        }, reminderIntervalTicks, reminderIntervalTicks).getTaskId();
    }

    private void cancelReminder() {
        if (reminderTaskId != -1) {
            Bukkit.getScheduler().cancelTask(reminderTaskId);
            reminderTaskId = -1;
        }
    }

    private void clearPending() {
        cancelReminder();
        releasePendingSpawnChunk();
        pendingEntry       = null;
        pendingLocation    = null;
        pendingDisplayName = null;
        pendingLocationStr = null;
    }

    /** Runs every 5 s; silently spawns the pending boss once its location is valid. */
    private void checkPendingSpawn() {
        if (pendingEntry == null || pendingLocation == null) return;
        if (!isLocationValid(pendingLocation)) return;

        BossEntry entry    = pendingEntry;
        Location  location = pendingLocation;
        String    dispName = pendingDisplayName;
        String    locStr   = pendingLocationStr;
        clearPending();

        MythicMob mmob = MythicBukkit.inst().getMobManager().getMythicMob(entry.id()).orElse(null);
        if (mmob == null) {
            plugin.getLogger().warning("[BossSpawner] MythicMob not found for pending spawn: " + entry.id());
            return;
        }

        doSpawn(entry, mmob, location, dispName, locStr);
    }

    /** True when the chunk at loc is loaded AND at least one player is within range. */
    private boolean isLocationValid(Location loc) {
        if (loc.getWorld() == null) return false;
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return false;
        return !loc.getWorld().getNearbyPlayers(loc, nearbyPlayerRadius).isEmpty();
    }

    /** Runs the SpawnBroadcast messages and SpawnCommands (called at trigger time). Always sends to all players. */
    private void runAnnouncement(String displayName, String locationStr) {
        Component nameComponent = MiniMessage.miniMessage()
                .deserialize(ColorTranslator.toMiniMessageFormat(displayName));

        for (String template : spawnBroadcast) {
            Component msg = MiniMessage.miniMessage().deserialize(
                    template,
                    Placeholder.component("name", nameComponent),
                    Placeholder.unparsed("location", locationStr)
            );
            Bukkit.broadcast(msg);
        }

        for (String cmd : spawnCommands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("<name>", displayName).replace("<location>", locationStr));
        }
    }

    /** Like runAnnouncement but skips players who have opted out of reminders. */
    private void runReminderAnnouncement(String displayName, String locationStr) {
        Component nameComponent = MiniMessage.miniMessage()
                .deserialize(ColorTranslator.toMiniMessageFormat(displayName));

        for (String template : spawnBroadcast) {
            Component msg = MiniMessage.miniMessage().deserialize(
                    template,
                    Placeholder.component("name", nameComponent),
                    Placeholder.unparsed("location", locationStr)
            );
            Bukkit.getOnlinePlayers().forEach(p -> {
                if (!reminderOptOuts.contains(p.getUniqueId())) {
                    p.sendMessage(msg);
                }
            });
        }

        for (String cmd : spawnCommands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("<name>", displayName).replace("<location>", locationStr));
        }
    }

    /**
     * Toggles the reminder opt-out for a player.
     * @return true if the player is now opted out, false if they are now opted in.
     */
    public boolean toggleReminderOptOut(UUID uuid) {
        if (reminderOptOuts.remove(uuid)) {
            return false; // was opted out, now opted in
        }
        reminderOptOuts.add(uuid);
        return true; // now opted out
    }

    /** Performs the actual MythicMobs entity spawn (no broadcast). */
    private void doSpawn(BossEntry entry, MythicMob mmob, Location spawnLoc,
                         String displayName, String locationStr) {
        ActiveMob activeMob = mmob.spawn(BukkitAdapter.adapt(spawnLoc), entry.level());
        if (activeMob == null) {
            plugin.getLogger().warning("[BossSpawner] Failed to spawn boss: " + entry.id());
            return;
        }

        clearActiveBoss();
        activeBossUUID = activeMob.getEntity().getBukkitEntity().getUniqueId();
        updateActiveChunkWindow(activeMob.getEntity().getBukkitEntity().getLocation());

        if (linkBossDamageList && plugin.getBossDamageList() != null) {
            plugin.getBossDamageList().forceTrack(activeBossUUID);
        }

        plugin.getLogger().info("[BossSpawner] Spawned " + entry.id() +
                " (Lv" + entry.level() + ") at " + locationStr);
    }

    /* ---------------------------------------------------------------------- */
    /*  Chunk reservation + tracking                                           */
    /* ---------------------------------------------------------------------- */

    private void startActiveChunkFollowTask() {
        if (activeChunkFollowTaskId != -1) return;

        final long[] elapsedTicks = {0L};
        activeChunkFollowTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Entity boss = getActiveBossEntity();
            if (boss == null) {
                if (activeBossUUID != null || !activeChunkTickets.isEmpty()) {
                    clearActiveBoss();
                }
                return;
            }

            Location loc = boss.getLocation();
            ChunkRef currentCenter = toChunkRef(loc);
            boolean movedOffCenter = activeCenterChunk == null || !activeCenterChunk.equals(currentCenter);

            elapsedTicks[0] += 20L;
            if (movedOffCenter || elapsedTicks[0] >= ACTIVE_CHUNK_REFRESH_TICKS || activeChunkTickets.isEmpty()) {
                updateActiveChunkWindow(loc);
                elapsedTicks[0] = 0L;
            }
        }, 20L, 20L).getTaskId();
        scheduledTaskIds.add(activeChunkFollowTaskId);
    }

    private Entity getActiveBossEntity() {
        if (activeBossUUID == null) return null;
        Entity entity = Bukkit.getEntity(activeBossUUID);
        if (entity == null || !entity.isValid() || entity.isDead()) return null;
        return entity;
    }

    private void updateActiveChunkWindow(Location centerLoc) {
        if (centerLoc == null || centerLoc.getWorld() == null) {
            releaseActiveChunkTickets();
            activeCenterChunk = null;
            return;
        }

        World world = centerLoc.getWorld();
        int centerX = centerLoc.getBlockX() >> 4;
        int centerZ = centerLoc.getBlockZ() >> 4;

        Set<ChunkRef> desired = new HashSet<>();
        UUID worldId = world.getUID();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                desired.add(new ChunkRef(worldId, centerX + dx, centerZ + dz));
            }
        }

        for (ChunkRef existing : new HashSet<>(activeChunkTickets)) {
            if (!desired.contains(existing)) {
                World oldWorld = Bukkit.getWorld(existing.worldId());
                if (oldWorld != null) {
                    oldWorld.removePluginChunkTicket(existing.x(), existing.z(), plugin);
                }
                activeChunkTickets.remove(existing);
            }
        }

        for (ChunkRef chunk : desired) {
            if (!activeChunkTickets.contains(chunk)) {
                world.addPluginChunkTicket(chunk.x(), chunk.z(), plugin);
                activeChunkTickets.add(chunk);
            }
        }

        activeCenterChunk = new ChunkRef(worldId, centerX, centerZ);
    }

    private void releaseActiveChunkTickets() {
        for (ChunkRef chunk : activeChunkTickets) {
            World world = Bukkit.getWorld(chunk.worldId());
            if (world != null) {
                world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
            }
        }
        activeChunkTickets.clear();
    }

    private void clearActiveBoss() {
        activeBossUUID = null;
        activeCenterChunk = null;
        releaseActiveChunkTickets();
    }

    private void reservePendingSpawnChunk(Location location) {
        if (location == null || location.getWorld() == null) return;
        ChunkRef next = toChunkRef(location);
        if (next.equals(pendingReservedChunk)) return;

        releasePendingSpawnChunk();
        location.getWorld().addPluginChunkTicket(next.x(), next.z(), plugin);
        pendingReservedChunk = next;
    }

    private void releasePendingSpawnChunk() {
        if (pendingReservedChunk == null) return;
        World world = Bukkit.getWorld(pendingReservedChunk.worldId());
        if (world != null) {
            world.removePluginChunkTicket(pendingReservedChunk.x(), pendingReservedChunk.z(), plugin);
        }
        pendingReservedChunk = null;
    }

    private ChunkRef toChunkRef(Location location) {
        World world = location.getWorld();
        return new ChunkRef(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /* ---------------------------------------------------------------------- */
    /*  Utilities                                                               */
    /* ---------------------------------------------------------------------- */

    private BossEntry resolveEntry(String bossId) {
        if (bossId != null) {
            BossEntry entry = bosses.stream()
                    .filter(b -> b.id().equalsIgnoreCase(bossId))
                    .findFirst().orElse(null);
            if (entry == null)
                plugin.getLogger().warning("[BossSpawner] Boss ID not found in config: " + bossId);
            return entry;
        }
        return bosses.get(ThreadLocalRandom.current().nextInt(bosses.size()));
    }

    /** Tries to get the MythicMob display name without an active mob instance. */
    private String resolveDisplayName(MythicMob mmob, String fallback) {
        if (mmob.getDisplayName() == null) return fallback;
        try {
            String name = mmob.getDisplayName().get((io.lumine.mythic.api.adapters.AbstractEntity) null);
            return (name != null && !name.isBlank()) ? name : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String formatLocation(Location loc) {
        return String.format("%.0f, %.0f, %.0f in %s",
                loc.getX(), loc.getY(), loc.getZ(), loc.getWorld().getName());
    }

    private Location parseLocation(String locStr) {
        String[] parts = locStr.split(":");
        if (parts.length != 4) return null;
        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            World world = Bukkit.getWorld(parts[3]);
            if (world == null) {
                plugin.getLogger().warning("[BossSpawner] World not found: " + parts[3]);
                return null;
            }
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void shutdown() {
        for (int taskId : scheduledTaskIds) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        scheduledTaskIds.clear();
        clearActiveBoss();
        clearPending();
    }
}
