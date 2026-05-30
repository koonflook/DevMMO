package com.teenkung.devmmo.Modules;

import com.teenkung.devmmo.DevMMO;
import com.teenkung.devmmo.Utils.PlayerDamage;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

public class EXPShareModule implements Listener {

    private final DevMMO plugin;
    private final MobXPModule mobXPModule;
    private final DamageTracker damageTracker;
    private boolean debugMode;

    /**
     * Tracks how much damage each player deals to a MythicMob.
     *
     * @param plugin The DevMMO plugin instance.
     */
    public EXPShareModule(DevMMO plugin) {
        this.plugin = plugin;
        this.mobXPModule = plugin.getMobXPModule();
        this.damageTracker = plugin.getDamageTracker();

        boolean enabled = plugin.getConfigLoader().isModuleEnabled("EXPShareModule");
        boolean mobXpEnabled = plugin.getConfigLoader().isModuleEnabled("MobXPModule");
        if (enabled) {
            if (!mobXpEnabled) {
                plugin.getLogger().warning("[EXPShareModule] MobXPModule must be enabled for EXPShareModule to work.");
                return;
            }
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            debugMode = plugin.getConfigLoader().getExpShareConfig().getBoolean("EXPShareModule.DebugMode", false);
        }
    }



    /**
     * Rewards experience to players who dealt damage to a MythicMob.
     *
     * @param event The MythicMobDeathEvent fired by MythicMobs.
     */
    @EventHandler
    public void onDeath(MythicMobDeathEvent event) {
        PlayerDamage damageRecord = damageTracker.getDamages().get(event.getMob().getUniqueId());
        if (damageRecord == null) return;
        if (mobXPModule.isMobBlacklisted(event.getMob().getType().getInternalName())) return;
        if (debugMode) {
            damageRecord.getDamagers().forEach(uuid -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    plugin.getLogger().info("[EXPShareModule] " + player.getName() + " dealt " + damageRecord.getDamage(uuid) + " damage ( " + (damageRecord.getDamage(uuid) / damageRecord.getTotalDamage())*100 + "% ) to " + event.getMob().getUniqueId());
                }
            });
        }
        for (UUID uuid : damageRecord.getDamagers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            if (!player.isOnline()) continue;
            double percentage = damageRecord.getDamage(uuid) / damageRecord.getTotalDamage();
            String world = event.getEntity().getWorld().getName();
            double xpGain = mobXPModule.getExperienceGain(Double.valueOf(event.getMob().getLevel()).intValue(), world) * percentage;
            mobXPModule.rewardExperience(Bukkit.getPlayer(uuid), xpGain);
            if (debugMode) {
                plugin.getLogger().info("[EXPShareModule] " + player.getName() + " gained " + xpGain + " EXP from " + event.getMob().getUniqueId());
            }
        }
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        plugin.getConfigLoader().getExpShareConfig().set("EXPShareModule.DebugMode", debugMode);
        plugin.getConfigLoader().getExpShareConfig();
    }
}
