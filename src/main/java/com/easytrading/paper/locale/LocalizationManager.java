package com.easytrading.paper.locale;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class LocalizationManager {

    private static final String DEFAULT_LOCALE = "en_us";

    private final ConcurrentMap<String, Properties> bundles = new ConcurrentHashMap<>();
    private volatile String activeLocale = DEFAULT_LOCALE;

    public void loadInternalBundle(JavaPlugin plugin, String locale, String resourcePath) {
        try (InputStream stream = plugin.getResource(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing internal localization bundle: " + resourcePath);
            }
            registerBundle(locale, stream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load localization bundle " + resourcePath, e);
        }
    }

    public void registerBundle(String locale, InputStream stream) throws IOException {
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        registerBundle(locale, properties);
    }

    public void registerBundle(String locale, Properties properties) {
        String normalizedLocale = normalizeLocale(locale);
        Properties merged = new Properties();
        Properties existing = bundles.get(normalizedLocale);
        if (existing != null) {
            merged.putAll(existing);
        }
        merged.putAll(properties);
        bundles.put(normalizedLocale, merged);
    }

    public boolean setActiveLocale(String locale) {
        String normalizedLocale = normalizeLocale(locale);
        if (!bundles.containsKey(normalizedLocale)) {
            return false;
        }
        activeLocale = normalizedLocale;
        return true;
    }

    public String getActiveLocale() {
        return activeLocale;
    }

    public String tr(String key, Object... args) {
        String pattern = raw(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (Exception ignored) {
            return pattern;
        }
    }

    public String raw(String key) {
        String value = find(activeLocale, key);
        if (value != null) {
            return value;
        }
        value = find(DEFAULT_LOCALE, key);
        return value != null ? value : key;
    }

    public String itemName(ItemStack stack) {
        if (stack == null) {
            return raw("common.unknown");
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return ChatColor.stripColor(meta.getDisplayName());
        }

        String translated = resolveTranslation(resolveTranslationKeys(stack));
        return translated != null ? translated : fallbackMaterialName(stack.getType());
    }

    public String itemName(Material material) {
        if (material == null) {
            return raw("common.unknown");
        }
        String translated = resolveTranslation(resolveTranslationKeys(material));
        return translated != null ? translated : fallbackMaterialName(material);
    }

    private String resolveTranslation(List<String> keys) {
        for (String key : keys) {
            String translated = find(activeLocale, key);
            if (translated != null) {
                return translated;
            }
        }
        for (String key : keys) {
            String translated = find(DEFAULT_LOCALE, key);
            if (translated != null) {
                return translated;
            }
        }
        return null;
    }

    private List<String> resolveTranslationKeys(ItemStack stack) {
        List<String> keys = new ArrayList<>();
        String reflected = invokeTranslationKey(stack, "translationKey");
        if (reflected == null) {
            reflected = invokeTranslationKey(stack, "getTranslationKey");
        }
        if (reflected != null && !reflected.isBlank()) {
            keys.add(reflected);
        }
        keys.addAll(resolveTranslationKeys(stack.getType()));
        return keys;
    }

    private List<String> resolveTranslationKeys(Material material) {
        List<String> keys = new ArrayList<>();
        String reflected = invokeTranslationKey(material, "translationKey");
        if (reflected == null) {
            reflected = invokeTranslationKey(material, "getTranslationKey");
        }
        if (reflected != null && !reflected.isBlank()) {
            keys.add(reflected);
        }

        String namespaced = material.getKey().getNamespace() + "." + material.getKey().getKey();
        keys.add("block." + namespaced);
        keys.add("item." + namespaced);
        return keys;
    }

    private String invokeTranslationKey(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value instanceof String string ? string : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private String fallbackMaterialName(Material material) {
        String value = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String[] parts = value.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private String find(String locale, String key) {
        Properties properties = bundles.get(normalizeLocale(locale));
        return properties == null ? null : properties.getProperty(key);
    }

    private String normalizeLocale(String locale) {
        return Objects.requireNonNullElse(locale, DEFAULT_LOCALE).trim().toLowerCase(Locale.ROOT);
    }
}
