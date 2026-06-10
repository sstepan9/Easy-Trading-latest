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
import java.util.zip.GZIPInputStream;

public final class Items {

    private Items() {
    }

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    public static ItemStack empty() {
        return new ItemStack(Material.AIR);
    }

    public static byte[] serialize(ItemStack stack) {
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
        try {
            return deserializeObjectStream(bytes);
        } catch (IOException | ClassNotFoundException objectStreamError) {
            try {
                return deserializeModern(bytes);
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
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        if (isGzip(bytes)) {
            return new GZIPInputStream(input);
        }
        return input;
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzip.transferTo(output);
            return output.toByteArray();
        }
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes != null
                && bytes.length >= 2
                && (bytes[0] & 0xFF) == 0x1F
                && (bytes[1] & 0xFF) == 0x8B;
    }
}
