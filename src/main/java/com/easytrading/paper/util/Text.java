package com.easytrading.paper.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class Text {

    private Text() {
    }

    public static String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public static List<String> lore(String... lines) {
        List<String> lore = new ArrayList<>(lines.length);
        for (String line : lines) {
            lore.add(color(line));
        }
        return lore;
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(color(message));
    }

    public static void actionBar(Player player, String message) {
        BaseComponent[] components = TextComponent.fromLegacyText(color(message));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, components);
    }
}
