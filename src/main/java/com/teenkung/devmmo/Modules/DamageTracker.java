package com.teenkung.devmmo.Modules;

import com.teenkung.devmmo.DevMMO;
import com.teenkung.devmmo.Developcraft.Utils.MythicReflectionTest;
import com.teenkung.devmmo.Utils.PlayerDamage;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.bukkit.events.MythicMobDespawnEvent;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DamageTracker implements Listener {

    private final DevMMO plugin;
    private final ConcurrentHashMap<UUID, PlayerDamage> damages = new ConcurrentHashMap<>();

    /**
     * Tracks how much damage each player deals to a MythicMob.
     *
     * @param plugin The DevMMO plugin instance.
     */
    public DamageTracker(DevMMO plugin) {
        this.plugin = plugin;
        if (plugin.getConfigLoader().isModuleEnabled("EXPShareModule")) {
            plugin.getLogger().info("[DamageTracker] EXPShareModule is enabled. Enabling DamageTracker Module.");
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
    }

    /**
     * Tracks how much damage each player deals to a MythicMob.
     *
     * @param event The EntityDamageByEntityEvent fired by Bukkit.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {
        // Check if the damager is a player
        if (event.getDamager() instanceof Player player) {
            // Check if the entity being damaged is a MythicMob
            if (MythicBukkit.inst().getMobManager().isMythicMob(event.getEntity())) {
                // Get or create a PlayerDamage record for this mob
                PlayerDamage damageRecord = damages.getOrDefault(event.getEntity().getUniqueId(), new PlayerDamage());
                double damage = event.getFinalDamage();
                LivingEntity entity = (LivingEntity) event.getEntity();
                if (damage > entity.getHealth()) {
                    damage = entity.getHealth();
                }
                // Accumulate damage
                double oldDmg = damageRecord.getDamage(player.getUniqueId());
                damageRecord.setDamage(player.getUniqueId(), oldDmg + damage);
                damages.put(event.getEntity().getUniqueId(), damageRecord);
            }
        }
        if (event.getDamager() instanceof Arrow arrow) {
            if (!(arrow.getShooter() instanceof Player player)) return;
            if (MythicBukkit.inst().getMobManager().isMythicMob(event.getEntity())) {
                PlayerDamage damageRecord = damages.getOrDefault(event.getEntity().getUniqueId(), new PlayerDamage());
                double damage = event.getFinalDamage();
                LivingEntity entity = (LivingEntity) event.getEntity();
                if (damage > entity.getHealth()) {
                    damage = entity.getHealth();
                }
                double oldDmg = damageRecord.getDamage(player.getUniqueId());
                damageRecord.setDamage(player.getUniqueId(), oldDmg + damage);
                damages.put(event.getEntity().getUniqueId(), damageRecord);
            }
        }
    }

    /**
     * Clears the damage record if a MythicMob despawns before dying.
     *
     * @param event MythicMobDespawnEvent from MythicMobs.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDespawn(MythicMobDespawnEvent event) {
        damages.remove(event.getEntity().getUniqueId());
    }

    /**
     * Clears the damage record for a MythicMob when it dies.
     *
     * @param event The MythicMobDeathEvent fired by MythicMobs.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(MythicMobDeathEvent event) {
        if (!plugin.getConfigLoader().isModuleEnabled("DamageTracker")) return;
        damages.remove(event.getMob().getUniqueId());
    }

    /**
     * Gets the damage records for all MythicMobs.
     *
     * @return A map of MythicMob UUIDs to their damage records.
     */
    public Map<UUID, PlayerDamage> getDamages() {
        return damages;
    }
}
