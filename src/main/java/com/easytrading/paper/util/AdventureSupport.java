package com.easytrading.paper.util;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class AdventureSupport {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final JavaPlugin plugin;
    private BukkitAudiences audiences;

    public AdventureSupport(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        audiences = BukkitAudiences.create(plugin);
    }

    public void disable() {
        if (audiences != null) {
            audiences.close();
            audiences = null;
        }
    }

    public void sendMessage(CommandSender sender, Component component) {
        audiences.sender(sender).sendMessage(component);
    }

    public void sendActionBar(Player player, Component component) {
        audiences.player(player).sendActionBar(component);
    }

    public void showBossBar(Player player, BossBar bossBar) {
        audiences.player(player).showBossBar(bossBar);
    }

    public void hideBossBar(Player player, BossBar bossBar) {
        audiences.player(player).hideBossBar(bossBar);
    }

    public static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    public static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    public static void displayName(ItemMeta meta, Component component) {
        meta.setDisplayName(legacy(component));
    }

    public static void lore(ItemMeta meta, List<Component> lines) {
        meta.setLore(lines.stream().map(AdventureSupport::legacy).toList());
    }
}
