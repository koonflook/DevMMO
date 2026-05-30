package com.teenkung.devmmo.Developcraft.Utils;

import com.teenkung.devmmo.DevMMO;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

public class MythicReflectionTest implements Listener {

    private final DevMMO plugin;

    public MythicReflectionTest(DevMMO plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onSpawn(MythicMobSpawnEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        if (!event.getMob().getType().getInternalName().equalsIgnoreCase("dungeon_slime_common")) return;

        ActiveMob activeMob = event.getMob();
        MythicMob mobType = event.getMobType();
        double level = event.getMobLevel();

        double scaledDamage = 434.0; // test value

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Object oldValue = replaceMobTypeAttackDamageStat(mobType, scaledDamage);

                plugin.getLogger().info("[ReflectTest] Replaced MobType attack stat with " + scaledDamage);

                // Re-apply Mythic options to this spawned mob
                mobType.applyMobOptions(activeMob, level);
                mobType.applySpawnModifiers(activeMob);

                double mythicDamage = mobType.getDamage(activeMob);
                double bukkitDamage = living.getAttribute(Attribute.ATTACK_DAMAGE) != null
                        ? living.getAttribute(Attribute.ATTACK_DAMAGE).getBaseValue()
                        : -1;

                plugin.getLogger().info("[ReflectTest] Mythic getDamage(am) = " + mythicDamage);
                plugin.getLogger().info("[ReflectTest] Bukkit ATTACK_DAMAGE = " + bukkitDamage);

                // Optional restore test:
                // restoreMobTypeAttackDamageStat(mobType, oldValue);

            } catch (Throwable t) {
                plugin.getLogger().warning("[ReflectTest] Failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    private Object replaceMobTypeAttackDamageStat(MythicMob mobType, double newDamage) throws Exception {
        Field statsField = findField(mobType.getClass(), "stats");
        statsField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<Object, Object> stats = (Map<Object, Object>) statsField.get(mobType);

        if (stats == null) {
            throw new IllegalStateException("MobType.stats is null");
        }

        Object damageKey = null;
        Object oldValue = null;

        for (Map.Entry<Object, Object> entry : stats.entrySet()) {
            Object key = entry.getKey();
            if (key == null) continue;

            String keyClass = key.getClass().getName().toLowerCase(Locale.ROOT);
            String keySimple = key.getClass().getSimpleName().toLowerCase(Locale.ROOT);

            if (keyClass.contains("attackdamage") || keySimple.contains("attackdamage")) {
                damageKey = key;
                oldValue = entry.getValue();
                break;
            }
        }

        if (damageKey == null) {
            throw new IllegalStateException("Could not find VanillaAttackDamageStat key in MobType.stats");
        }

        stats.put(damageKey, newDamage);
        return oldValue;
    }

    private void restoreMobTypeAttackDamageStat(MythicMob mobType, Object oldValue) throws Exception {
        Field statsField = findField(mobType.getClass(), "stats");
        statsField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<Object, Object> stats = (Map<Object, Object>) statsField.get(mobType);

        for (Map.Entry<Object, Object> entry : stats.entrySet()) {
            Object key = entry.getKey();
            if (key == null) continue;

            String keyClass = key.getClass().getName().toLowerCase(Locale.ROOT);
            String keySimple = key.getClass().getSimpleName().toLowerCase(Locale.ROOT);

            if (keyClass.contains("attackdamage") || keySimple.contains("attackdamage")) {
                stats.put(key, oldValue);
                return;
            }
        }

        throw new IllegalStateException("Could not find VanillaAttackDamageStat key to restore");
    }

    private Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cls = type;
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}