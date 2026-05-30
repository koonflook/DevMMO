package com.teenkung.devmmo.Commands.BossReminder;

import com.teenkung.devmmo.DevMMO;
import com.teenkung.devmmo.Modules.BossSpawner;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class BossReminderCommand implements CommandExecutor {

    private final DevMMO plugin;

    public BossReminderCommand(DevMMO plugin) {
        this.plugin = plugin;
        PluginCommand command = plugin.getCommand("bossreminder");
        if (command != null) {
            command.setExecutor(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        BossSpawner bs = plugin.getBossSpawner();
        if (bs == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>BossSpawner module is not enabled."));
            return true;
        }

        boolean optedOut = bs.toggleReminderOptOut(player.getUniqueId());
        if (optedOut) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<yellow>Boss reminder notifications <red>disabled</red>. You will still receive initial spawn announcements."));
        } else {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<yellow>Boss reminder notifications <green>enabled</green>."));
        }
        return true;
    }
}
