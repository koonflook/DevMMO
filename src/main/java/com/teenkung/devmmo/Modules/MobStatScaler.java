package com.teenkung.devmmo.Modules;

import com.teenkung.devmmo.DevMMO;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MobStatScaler implements Listener {

    private static final String VAR_SCALED_DAMAGE = "scaled_damage";
    private static final String VAR_SCALED_HEALTH = "scaled_health";

    private final DevMMO plugin;
    private final Map<String, FormulaPair> specificFormulas = new ConcurrentHashMap<>();

    private String globalHealthFormula = "V * L";
    private String globalDamageFormula = "V * L";
    private boolean debugMode = false;

    public MobStatScaler(DevMMO plugin) {
        this.plugin = plugin;

        if (!plugin.getConfigLoader().isModuleEnabled("MobStatScaler")) {
            return;
        }

        plugin.getLogger().info("[MobStatScaler] Enabling module...");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        loadConfig();

        plugin.getLogger().info("[MobStatScaler] Module enabled.");
    }

    private void loadConfig() {
        Configuration cfg = plugin.getConfigLoader().getMobStatScalerConfig();
        if (cfg == null) {
            plugin.getLogger().warning("[MobStatScaler] Config is missing. Using defaults.");
            return;
        }

        ConfigurationSection root = cfg.getConfigurationSection("MobStatScaler");
        if (root == null) {
            plugin.getLogger().warning("[MobStatScaler] Missing 'MobStatScaler' section in MobStatScaler.yml.");
            return;
        }

        debugMode = root.getBoolean("DebugMode", false);

        ConfigurationSection global = root.getConfigurationSection("Global");
        if (global != null) {
            globalHealthFormula = global.getString("Health", globalHealthFormula);
            globalDamageFormula = global.getString("Damage", globalDamageFormula);
        }

        specificFormulas.clear();
        ConfigurationSection specific = root.getConfigurationSection("Specific");
        if (specific != null) {
            for (String mobId : specific.getKeys(false)) {
                ConfigurationSection mobSec = specific.getConfigurationSection(mobId);
                if (mobSec == null) {
                    continue;
                }

                String health = mobSec.getString("Health", globalHealthFormula);
                String damage = mobSec.getString("Damage", globalDamageFormula);
                specificFormulas.put(
                        mobId.toLowerCase(Locale.ROOT),
                        new FormulaPair(health, damage)
                );
            }
        }

        validateFormula("Global.Health", globalHealthFormula);
        validateFormula("Global.Damage", globalDamageFormula);
        specificFormulas.forEach((mobId, pair) -> {
            validateFormula("Specific." + mobId + ".Health", pair.healthFormula());
            validateFormula("Specific." + mobId + ".Damage", pair.damageFormula());
        });
    }

    @EventHandler
    public void onMythicMobSpawn(MythicMobSpawnEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }

        ActiveMob activeMob = event.getMob();
        double level = sanitizeLevel(event.getMobLevel());
        String mobId = activeMob.getType().getInternalName().toLowerCase(Locale.ROOT);

        FormulaPair formulas = specificFormulas.getOrDefault(
                mobId,
                new FormulaPair(globalHealthFormula, globalDamageFormula)
        );

        double baseHealth = event.getMobType().getHealth(activeMob);
        double baseDamage = event.getMobType().getDamage(activeMob);

        double scaledHealth = calculateExpression(formulas.healthFormula(), level, baseHealth);
        double scaledDamage = calculateExpression(formulas.damageFormula(), level, baseDamage);

        if (debugMode) {
            plugin.getLogger().info(String.format(
                    Locale.ROOT,
                    "[MobStatScaler] %s Lv%.2f | HP %.2f -> %.2f | DMG %.2f -> %.2f",
                    mobId, level, baseHealth, scaledHealth, baseDamage, scaledDamage
            ));
        }

        applyStat(entity, activeMob, mobId, scaledHealth, scaledDamage);
    }

    private void applyStat(LivingEntity entity, ActiveMob activeMob, String mobId, double scaledHealth, double scaledDamage) {
        // Delay by 2 ticks so we run after Mythic finishes its full spawn/load setup,
        // including on server restart when persisted mobs are reloaded.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!entity.isValid() || entity.isDead()) {
                return;
            }

            if (scaledHealth > 0) {
                activeMob.getVariables().putDouble(VAR_SCALED_HEALTH, scaledHealth);
                setMaxHealth(entity, scaledHealth);
            }

            if (scaledDamage > 0) {
                activeMob.getVariables().putDouble(VAR_SCALED_DAMAGE, scaledDamage);

                // Optional: keep Bukkit melee damage in sync too, in case the mob ever uses normal hits.
                setAttackDamage(entity, scaledDamage);
            }

            if (!debugMode) {
                return;
            }

            double storedHealth = activeMob.getVariables().getDouble(VAR_SCALED_HEALTH + "|0");
            double storedDamage = activeMob.getVariables().getDouble(VAR_SCALED_DAMAGE + "|0");

            double finalMaxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH) != null
                    ? entity.getAttribute(Attribute.MAX_HEALTH).getBaseValue()
                    : -1.0;

            double finalAttackDamageAttr = entity.getAttribute(Attribute.ATTACK_DAMAGE) != null
                    ? entity.getAttribute(Attribute.ATTACK_DAMAGE).getBaseValue()
                    : -1.0;

            plugin.getLogger().info(String.format(
                    Locale.ROOT,
                    "[MobStatScaler] Applied %s | var HP=%.2f, var DMG=%.2f, attr MAX_HEALTH=%.2f, attr ATTACK_DAMAGE=%.2f",
                    mobId, storedHealth, storedDamage, finalMaxHealthAttr, finalAttackDamageAttr
            ));
        }, 2L);
    }

    private void setAttackDamage(LivingEntity entity, double damage) {
        if (entity.getAttribute(Attribute.ATTACK_DAMAGE) == null) {
            return;
        }

        if (!Double.isFinite(damage) || damage <= 0) {
            return;
        }

        entity.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(damage);
    }

    private void setMaxHealth(LivingEntity entity, double health) {
        if (entity.getAttribute(Attribute.MAX_HEALTH) == null) {
            return;
        }

        if (!Double.isFinite(health) || health <= 0) {
            return;
        }

        entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);

        double maxHealth = entity.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (Double.isFinite(maxHealth) && maxHealth > 0) {
            entity.setHealth(Math.min(maxHealth, maxHealth));
        }
    }

    private void validateFormula(String key, String formula) {
        try {
            calculateExpression(formula, 1.0, 1.0);
        } catch (Exception ex) {
            plugin.getLogger().warning("[MobStatScaler] Invalid formula at " + key + ": " + ex.getMessage());
        }
    }

    private double sanitizeLevel(double level) {
        if (!Double.isFinite(level) || level < 0) {
            return 0;
        }
        return level;
    }

    private double calculateExpression(String formula, double level, double value) {
        String expressionString = normalizeFormula(formula);

        Expression expression = new ExpressionBuilder(expressionString)
                .variables("L", "V", "level", "value")
                .build()
                .setVariable("L", level)
                .setVariable("V", value)
                .setVariable("level", level)
                .setVariable("value", value);

        double result = expression.evaluate();
        if (!Double.isFinite(result) || result < 0) {
            if (debugMode) {
                plugin.getLogger().warning("[MobStatScaler] Formula produced invalid result (" + result + ").");
            }
            return 0;
        }

        return result;
    }

    private String normalizeFormula(String formula) {
        if (formula == null || formula.isBlank()) {
            return "V";
        }

        return formula
                .replace("{level}", "level")
                .replace("{Level}", "level")
                .replace("{LEVEL}", "level")
                .replace("{value}", "value")
                .replace("{Value}", "value")
                .replace("{VALUE}", "value");
    }

    private record FormulaPair(String healthFormula, String damageFormula) {}
}