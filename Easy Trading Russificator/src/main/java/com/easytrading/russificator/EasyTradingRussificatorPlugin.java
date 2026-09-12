package com.easytrading.russificator;

import com.easytrading.paper.EasyTradingPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;

public final class EasyTradingRussificatorPlugin extends JavaPlugin {

    private static final String LOCALE = "ru_ru";

    @Override
    public void onEnable() {
        if (!(Bukkit.getPluginManager().getPlugin("EasyTrading") instanceof EasyTradingPlugin easyTrading)) {
            getLogger().severe("EasyTrading was not found. The Russificator cannot start.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            registerBundle(easyTrading, "lang/ru_ru.properties");
            registerBundle(easyTrading, "lang/minecraft_ru.properties");
        } catch (IOException e) {
            getLogger().severe("Failed to load Russian language bundle: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!easyTrading.getLocalization().setActiveLocale(LOCALE)) {
            getLogger().severe("Russian locale was registered but could not be activated.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        easyTrading.refreshAllMarketGuis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            easyTrading.syncBalance(player);
        }

        getLogger().info("EasyTrading Russificator enabled. Active locale: ru_ru");
    }

    @Override
    public void onDisable() {
        if (Bukkit.getPluginManager().getPlugin("EasyTrading") instanceof EasyTradingPlugin easyTrading) {
            easyTrading.getLocalization().setActiveLocale("en_us");
            easyTrading.refreshAllMarketGuis();
        }
    }

    private void registerBundle(EasyTradingPlugin easyTrading, String path) throws IOException {
        try (InputStream stream = getResource(path)) {
            if (stream == null) {
                throw new IOException("Missing resource: " + path);
            }
            easyTrading.getLocalization().registerBundle(LOCALE, stream);
        }
    }
}
