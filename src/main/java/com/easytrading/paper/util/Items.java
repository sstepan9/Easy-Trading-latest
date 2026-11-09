package com.easytrading.paper.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class Items {

    /** Hard limits for data read from persisted JSON, not player packets. */
    private static final int MAX_SERIALIZED_BYTES = 1_048_576;
    private static final int MAX_METADATA_NODES = 4_096;
    private static final int MAX_METADATA_TEXT = 262_144;

    private Items() {
    }

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    public static ItemStack empty() {
        return new ItemStack(Material.AIR);
    }

    public static byte[] serialize(ItemStack stack) {
        validate(stack);
        byte[] modernBytes = trySerializeModern(stack);
        if (modernBytes != null) {
            return modernBytes;
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream data = new BukkitObjectOutputStream(output)) {
                data.writeObject(stack);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize ItemStack", e);
        }
    }

    public static ItemStack deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("Serialized ItemStack payload has an invalid size");
        }
        try {
            ItemStack stack = deserializeObjectStream(bytes);
            validate(stack);
            return stack;
        } catch (IOException | ClassNotFoundException objectStreamError) {
            try {
                ItemStack stack = deserializeModern(bytes);
                validate(stack);
                return stack;
            } catch (ReflectiveOperationException | IOException modernBytesError) {
                IllegalStateException failure = new IllegalStateException("Could not deserialize ItemStack", modernBytesError);
                failure.addSuppressed(objectStreamError);
                throw failure;
            }
        }
    }

    private static ItemStack deserializeObjectStream(byte[] bytes) throws IOException, ClassNotFoundException {
        try (InputStream input = openDeserializationStream(bytes);
             BukkitObjectInputStream data = new BukkitObjectInputStream(input)) {
            return (ItemStack) data.readObject();
        }
    }

    private static ItemStack deserializeModern(byte[] bytes) throws ReflectiveOperationException, IOException {
        byte[] payload = isGzip(bytes) ? gunzip(bytes) : bytes;
        Method method = ItemStack.class.getMethod("deserializeBytes", byte[].class);
        return (ItemStack) method.invoke(null, payload);
    }

    private static byte[] trySerializeModern(ItemStack stack) {
        try {
            Method method = ItemStack.class.getMethod("serializeAsBytes");
            Object result = method.invoke(stack);
            return result instanceof byte[] bytes ? bytes : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    private static InputStream openDeserializationStream(byte[] bytes) throws IOException {
        if (isGzip(bytes)) {
            return new ByteArrayInputStream(gunzip(bytes));
        }
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                if (output.size() > MAX_SERIALIZED_BYTES - read) {
                    throw new IOException("Decompressed ItemStack payload exceeds the size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /**
     * Reject invalid or excessively complex stacks before they enter market
     * persistence. This limits malformed NBT/component payloads that can
     * otherwise crash clients or exhaust the server while being displayed.
     */
    private static void validate(ItemStack stack) {
        if (stack == null || stack.getType() == null || stack.getType().isAir()
                || stack.getAmount() <= 0 || stack.getAmount() > stack.getMaxStackSize()) {
            throw new IllegalArgumentException("ItemStack has an invalid material or amount");
        }
        if (stack.hasItemMeta()) {
            try {
                MetadataBudget budget = new MetadataBudget();
                inspectMetadata(stack.getItemMeta().serialize(), budget, 0);
            } catch (RuntimeException metadataFailure) {
                throw new IllegalArgumentException("ItemStack metadata is invalid", metadataFailure);
            }
        }
    }

    private static void inspectMetadata(Object value, MetadataBudget budget, int depth) {
        if (value == null) return;
        if (depth > 16 || ++budget.nodes > MAX_METADATA_NODES) {
            throw new IllegalArgumentException("ItemStack metadata is too deeply nested or complex");
        }
        if (value instanceof CharSequence text) {
            budget.text += text.length();
            if (budget.text > MAX_METADATA_TEXT) {
                throw new IllegalArgumentException("ItemStack metadata text is too large");
            }
            return;
        }
        if (value instanceof byte[] bytes) {
            budget.text += bytes.length;
            if (budget.text > MAX_METADATA_TEXT) {
                throw new IllegalArgumentException("ItemStack metadata binary data is too large");
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                inspectMetadata(entry.getKey(), budget, depth + 1);
                inspectMetadata(entry.getValue(), budget, depth + 1);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) inspectMetadata(element, budget, depth + 1);
        }
    }

    private static final class MetadataBudget {
        private int nodes;
        private int text;
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes != null
                && bytes.length >= 2
                && (bytes[0] & 0xFF) == 0x1F
                && (bytes[1] & 0xFF) == 0x8B;
    }
}
