package com.teenkung.devmmo;

import com.teenkung.devmmo.Commands.BossReminder.BossReminderCommand;
import com.teenkung.devmmo.Commands.MainCommand.MainCommand;
import com.teenkung.devmmo.Commands.SpawnMythicMobs.SpawnMythicMobs;
import com.teenkung.devmmo.Commands.SpawnMythicMobs.SpawnMythicMobsTab;
import com.teenkung.devmmo.Developcraft.Developcraft;
import com.teenkung.devmmo.Modules.*;
import com.teenkung.devmmo.Modules.AuraSkillIntegration.AuraSkillIntegration;
import com.teenkung.devmmo.Utils.ConfigLoader;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;


public class DevMMO extends JavaPlugin {

    private ConfigLoader configLoader;
    private Developcraft developcraft;

    // Module references
    private HealthModule healthModule;
    private StaminaModule staminaModule;
    private FireworkBlocker fireworkBlocker;
    private DamageTracker damageTracker;
    private MobXPModule mobXPModule;
    private EXPShareModule expShareModule;
    private RegionLevelModule regionLevelModule;
    private BossDamageList bossDamageList;
    private MobStatScaler mobStatScaler;
    private BossSpawner bossSpawner;
    //private AuraSkillIntegration auraSkillIntegration;

    @Override
    public void onEnable() {
        this.developcraft = new Developcraft(this);

        loadAll();
        Objects.requireNonNull(getCommand("spawn-mythicmobs")).setExecutor(new SpawnMythicMobs());
        Objects.requireNonNull(getCommand("spawn-mythicmobs")).setTabCompleter(new SpawnMythicMobsTab());
        new MainCommand(this);
        new BossReminderCommand(this);
    }

    @Override
    public void onDisable() {
        // If you have any cleanup, do it here
        unloadAll();
        developcraft.unloadAll();
    }

    /**
     * Loads (or reloads) all configurations and modules.
     */
    public void loadAll() {
        this.configLoader = new ConfigLoader(this);

        this.healthModule = new HealthModule(this);
        this.staminaModule = new StaminaModule(this);
        this.fireworkBlocker = new FireworkBlocker(this);
        this.damageTracker = new DamageTracker(this);
        this.mobXPModule = new MobXPModule(this);
        this.expShareModule = new EXPShareModule(this);
        this.regionLevelModule = new RegionLevelModule(this);
        this.bossDamageList = new BossDamageList(this);
        this.mobStatScaler = new MobStatScaler(this);
        this.bossSpawner = new BossSpawner(this);
        //this.auraSkillIntegration = new AuraSkillIntegration(this);

        developcraft.loadAll();

        getServer().getOnlinePlayers().forEach(player -> staminaModule.addSprintTaskPlayer(player));
    }

    /**
     * Optional: a cleanup method for manual unregistration/cancellation
     * if you need to re-load modules without fully unloading the plugin.
     */
    public void unloadAll() {
        developcraft.unloadAll();
        staminaModule.shutdown();
        //auraSkillIntegration.shutdown();
        bossDamageList.shutdown();
        HandlerList.unregisterAll(healthModule);
        HandlerList.unregisterAll(staminaModule);
        HandlerList.unregisterAll(fireworkBlocker);
        HandlerList.unregisterAll(damageTracker);
        HandlerList.unregisterAll(mobXPModule);
        HandlerList.unregisterAll(expShareModule);
        HandlerList.unregisterAll(regionLevelModule);
        HandlerList.unregisterAll(bossDamageList);
        HandlerList.unregisterAll(mobStatScaler);
        bossSpawner.shutdown();
        //HandlerList.unregisterAll(auraSkillIntegration);
        HandlerList.unregisterAll(this);
    }

    /**
     * Public method to "reload" everything
     * - Unregister events, cancel tasks, etc.
     * - Reload config
     * - Re-instantiate modules
     */
    public void reloadAll() {
        unloadAll();

        loadAll();

        getLogger().info("DevMMO has been reloaded!");
    }

    public ConfigLoader getConfigLoader() {
        return configLoader;
    }

    public HealthModule getHealthModule() {
        return healthModule;
    }

    public StaminaModule getStaminaModule() {
        return staminaModule;
    }

    public FireworkBlocker getFireworkBlocker() {
        return fireworkBlocker;
    }

    public DamageTracker getDamageTracker() {
        return damageTracker;
    }

    public MobXPModule getMobXPModule() {
        return mobXPModule;
    }

    public EXPShareModule getExpShareModule() {
        return expShareModule;
    }

    public RegionLevelModule getRegionLevelModule() {
        return regionLevelModule;
    }

    public BossDamageList getBossDamageList() { return bossDamageList; }

    public MobStatScaler getMobStatScaler() { return mobStatScaler; }

    public BossSpawner getBossSpawner() { return bossSpawner; }

    //public AuraSkillIntegration getAuraSkillIntegration() { return auraSkillIntegration; }

}
