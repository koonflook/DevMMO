package com.teenkung.devmmo.Modules;

import com.teenkung.devmmo.DevMMO;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.Indyuce.mmocore.api.event.PlayerExperienceGainEvent;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.experience.EXPSource;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Awards experience to a player when they kill a MythicMob.
 * Uses MobXPModule.yml for configuration.
 */
public class MobXPModule implements Listener {

    private final DevMMO plugin;
    private final DecimalFormat df = new DecimalFormat("#.##");

    /* -------------- runtime cache -------------- */
    private final Map<Player, Double> experienceBuffer = new ConcurrentHashMap<>();

    /* -------------- config values -------------- */
    private long fadeIn;
    private long stay;
    private long fadeOut;
    private String titleMessage;
    private String soundName;
    private float volume;
    private float pitch;
    private String baseFormula;
    private final Map<String, String> formulas = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Double>> worldCache = new ConcurrentHashMap<>();
    private final java.util.Set<String> blacklist = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> mobBlacklist = ConcurrentHashMap.newKeySet();
    private boolean debugMode;

    /* ------------------------------------------- */

    public MobXPModule(DevMMO plugin) {
        this.plugin = plugin;

        var cfg = plugin.getConfigLoader().getMobXPConfig();
        fadeIn       = cfg.getLong("MobXPModule.TitleFadeIn", 150);
        stay         = cfg.getLong("MobXPModule.TitleStay", 500);
        fadeOut      = cfg.getLong("MobXPModule.TitleFadeOut", 150);
        titleMessage = cfg.getString("MobXPModule.TitleMessage", "<yellow>+{exp}<green> EXP!");
        soundName    = cfg.getString("MobXPModule.AwardSound", "BLOCK_NOTE_BLOCK_BELL");
        volume       = (float) cfg.getDouble("MobXPModule.SoundVolume", 1.0);
        pitch        = (float) cfg.getDouble("MobXPModule.SoundPitch", 1.2);

        var formulasSec = cfg.getConfigurationSection("MobXPModule.Formulas");
        if (formulasSec != null) {
            for (String key : formulasSec.getKeys(false)) {
                String formula = formulasSec.getString(key);
                if (formula != null) {
                    formulas.put(key.toLowerCase(), formula);
                }
            }
        } else {
            String legacy = cfg.getString("MobXPModule.BaseFormula", "30 + (0.4 * (level ^ 2.25))");
            formulas.put("default", legacy);
        }
        baseFormula  = formulas.getOrDefault("default", "30 + (0.4 * (level ^ 2.25))");

        cfg.getStringList("MobXPModule.Blacklist").forEach(w -> blacklist.add(normalizeKey(w)));
        cfg.getStringList("MobXPModule.MobBlacklist").forEach(id -> mobBlacklist.add(normalizeKey(id)));

        if (plugin.getConfigLoader().isModuleEnabled("MobXPModule"))
            plugin.getServer().getPluginManager().registerEvents(this, plugin);

        if (plugin.getConfigLoader().isModuleEnabled("EXPShareModule"))
            plugin.getLogger().info("[MobXPModule] EXPShareModule is enabled. Default kill-XP is disabled to avoid double rewards.");

        /* Verify the configured formula once at startup. */
        try {
            new ExpressionBuilder(baseFormula.replace("{level}", "level"))
                    .variables("level")
                    .build()
                    .setVariable("level", 1)
                    .evaluate();
        } catch (Exception ex) {
            plugin.getLogger().severe("[MobXPModule] Invalid BaseFormula in MobXPModule.yml: " + ex.getMessage());
            plugin.getServer().getPluginManager().disablePlugin(plugin);
        }
    }

    /* ------------------------------------------------------------------------ */
    /*  Main event: Mythic mob death                                            */
    /* ------------------------------------------------------------------------ */

    @EventHandler
    public void onMythicMobDeath(MythicMobDeathEvent event) {
        if (!plugin.getConfigLoader().isModuleEnabled("MobXPModule")) return;
        if (plugin.getConfigLoader().isModuleEnabled("EXPShareModule")) return;
        ActiveMob mob = event.getMob();

        if (isMobBlacklisted(mob.getType().getInternalName())) return;

        double rawLevel = mob.getLevel();

        /* Guard against missing / NaN / negative levels. */
        if (!Double.isFinite(rawLevel) || rawLevel < 0)
            rawLevel = 0;

        int level = (int) Math.round(rawLevel);

        if (event.getKiller() instanceof Player player) {
            String world = player.getWorld().getName();
            double xp = getExperienceGain(level, world);
            rewardExperience(player, xp);
        }
    }

    /* ------------------------------------------------------------------------ */
    /*  Buffer & title display for *all* XP gains (MMOCore event)               */
    /* ------------------------------------------------------------------------ */

    @EventHandler
    public void onExperienceGain(PlayerExperienceGainEvent event) {
        if (event.getProfession() != null) return; // ignore profession XP

        Player player = event.getPlayer();
        double exp    = event.getExperience();

        experienceBuffer.merge(player, exp, Double::sum);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Double totalObj = experienceBuffer.remove(player); // may be null
            if (totalObj == null || totalObj <= 0) return;

            double total = totalObj;   // now safely unboxed
            String formatted = df.format(total);

            player.showTitle(Title.title(
                    Component.empty(),
                    MiniMessage.miniMessage().deserialize(titleMessage.replace("{exp}", formatted)),
                    Title.Times.times(Duration.ofMillis(fadeIn), Duration.ofMillis(stay), Duration.ofMillis(fadeOut))
            ));
            Sound sound = Registry.SOUND_EVENT.get(Key.key(soundName));
            if (sound != null) player.playSound(player.getLocation(), sound, volume, pitch);
        }, 3L);
    }

    /* ------------------------------------------------------------------------ */
    /*  Helpers                                                                 */
    /* ------------------------------------------------------------------------ */

    /** Grant XP safely; never allow NaN or negative values to reach MMOCore. */
    public void rewardExperience(Player player, double xp) {
        if (!Double.isFinite(xp) || xp <= 0) {
            if (debugMode)
                plugin.getLogger().warning("[MobXPModule] Skipped invalid XP value (" + xp + ") for " + player.getName());
            return;
        }
        PlayerData.get(player).giveExperience(xp, EXPSource.VANILLA);
    }

    /** Public for other modules / admin commands. */
    public double getExperienceGain(int level) {
        return getExperienceGain(level, "default");
    }

    /** XP gain using world specific formula */
    public double getExperienceGain(int level, String world) {
        if (isWorldBlacklisted(world)) return 0;

        String formula = formulas.getOrDefault(normalizeKey(world), baseFormula);
        return calculateExpression(formula, world, level);
    }

    public boolean isWorldBlacklisted(String world) {
        return blacklist.contains(normalizeKey(world));
    }

    public boolean isMobBlacklisted(String mobId) {
        return mobBlacklist.contains(normalizeKey(mobId));
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private double calculateExpression(String formula, String world, int level) {
        Map<Integer, Double> worldMap = worldCache.computeIfAbsent(world.toLowerCase(), k -> new ConcurrentHashMap<>());
        Double cached = worldMap.get(level);
        if (cached != null) return cached;

        String exprString = formula.replace("{level}", "level");

        double result;
        try {
            Expression expr = new ExpressionBuilder(exprString)
                    .variables("level")
                    .build()
                    .setVariable("level", level);
            result = expr.evaluate();
        } catch (Exception ex) {
            plugin.getLogger().severe("[MobXPModule] Error evaluating formula \"" +
                    baseFormula + "\" with level " + level + ": " + ex.getMessage());
            result = 0;
        }

        /* Final guard – never return NaN, ∞, or negative. */
        if (!Double.isFinite(result) || result < 0) {
            if (debugMode)
                plugin.getLogger().warning("[MobXPModule] Formula produced invalid value (" +
                        result + ") for level " + level + ". Using 0.");
            result = 0;
        }

        worldMap.put(level, result);
        return result;
    }

    /* ------------------------------------------------------------------------ */
    /*  Fluent setters for runtime tweaking / admin commands                    */
    /* ------------------------------------------------------------------------ */

    public boolean isDebugMode()                  { return debugMode; }
    public void setDebugMode(boolean debugMode)   { this.debugMode = debugMode; }

    public void setBaseFormula(String baseFormula) {
        this.baseFormula = baseFormula;
        worldCache.clear();
    }

    public void setTitleMessage(String titleMessage) { this.titleMessage = titleMessage; }
    public void setSoundName(String soundName)       { this.soundName = soundName; }
    public void setVolume(float volume)             { this.volume = volume; }
    public void setPitch(float pitch)               { this.pitch = pitch; }

    public void setFadeIn(long fadeIn)   { this.fadeIn = fadeIn; }
    public void setStay(long stay)       { this.stay   = stay;   }
    public void setFadeOut(long fadeOut) { this.fadeOut = fadeOut; }
}
