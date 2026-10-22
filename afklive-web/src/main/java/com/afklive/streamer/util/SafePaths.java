package com.afklive.streamer.util;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Path helpers that keep user-supplied names (upload filenames, zip entries, storage keys)
 * from escaping the directory they belong in.
 */
public final class SafePaths {

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^A-Za-z0-9._ -]");

    private SafePaths() {
    }

    /**
     * Resolves {@code name} under {@code root} and rejects anything that would land outside it
     * (for example "../../etc/passwd" or an absolute path).
     */
    public static Path resolveInside(Path root, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("File name is empty.");
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new SecurityException("Path escapes its directory: " + name);
        }
        return resolved;
    }

    /**
     * Reduces a user-supplied file name to a safe single path segment: drops any directory part
     * (both / and \ separators), replaces unusual characters, and caps the length.
     */
    public static String sanitizeFilename(String originalName) {
        if (originalName == null) {
            return "file";
        }
        String name = originalName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = UNSAFE_CHARS.matcher(name).replaceAll("_").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            return "file";
        }
        return name.length() > 150 ? name.substring(name.length() - 150) : name;
    }
}
