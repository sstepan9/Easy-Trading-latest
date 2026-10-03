package com.easytrading.paper.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Small crash-safe helper for the plugin's JSON data files. */
final class DataFileUtil {
    private DataFileUtil() {
    }

    /**
     * Writes beside the destination and swaps it into place only after the
     * complete payload has reached the filesystem.  A server crash during a
     * normal save therefore leaves either the old valid file or the new one,
     * never an empty/truncated JSON document.
     */
    static void writeStringAtomic(Path destination, String content) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporary = destination.resolveSibling(destination.getFileName()
                + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
