package com.teenkung.devmmo.Commands.MainCommand;

import com.teenkung.devmmo.DevMMO;
import com.teenkung.devmmo.Modules.AuraSkillIntegration.CustomTraits;
import com.teenkung.devmmo.Modules.BossSpawner;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.trait.TraitModifier;
import dev.aurelium.auraskills.api.util.AuraSkillsModifier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainCommand implements CommandExecutor, TabCompleter {

    private final DevMMO plugin;

    public MainCommand(DevMMO plugin) {
        this.plugin = plugin;
        PluginCommand command = plugin.getCommand("devmmo");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("DevMMO plugin by TeenKung");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("devmmo.reload")) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>You do not have permission to use this command."));
                return true;
            }
            long start = System.currentTimeMillis();
            plugin.reloadAll();
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>DevMMO reloaded in <gold>" + (System.currentTimeMillis() - start) + "ms<green>."));
            return true;
        }

        if (args[0].equalsIgnoreCase("bossspawner")) {
            if (!sender.hasPermission("devmmo.bossspawner")) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>You do not have permission to use this command."));
                return true;
            }
            if (args.length < 2 || !args[1].equalsIgnoreCase("spawn")) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>Usage: /devmmo bossspawner spawn [bossId]"));
                return true;
            }

            BossSpawner bs = plugin.getBossSpawner();
            if (bs == null) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>BossSpawner module is not enabled."));
                return true;
            }

            if (args.length >= 3) {
                bs.spawnBoss(args[2]);
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>Force-spawned boss: <white>" + args[2]));
            } else {
                bs.spawnBoss();
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>Force-spawned a random boss."));
            }
            return true;
        }

        return false;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload", "bossspawner").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bossspawner")) {
            return List.of("spawn").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bossspawner") && args[1].equalsIgnoreCase("spawn")) {
            BossSpawner bs = plugin.getBossSpawner();
            if (bs == null) return List.of();
            List<String> ids = new ArrayList<>(bs.getConfiguredBossIds());
            ids.removeIf(id -> !id.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)));
            return ids;
        }
        return List.of();
    }
}
