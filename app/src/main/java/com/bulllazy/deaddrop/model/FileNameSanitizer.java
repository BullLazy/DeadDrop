package com.bulllazy.deaddrop.model;

/** Keeps a document name safe for display and for use as a suggested output name. */
public final class FileNameSanitizer {
    private static final int MAX_LENGTH = 120;

    private FileNameSanitizer() { }

    public static String sanitize(String name) {
        if (name == null) {
            return "unnamed-file";
        }
        StringBuilder safe = new StringBuilder(Math.min(name.length(), MAX_LENGTH));
        for (int i = 0; i < name.length() && safe.length() < MAX_LENGTH; i++) {
            char character = name.charAt(i);
            if (character < 0x20 || character == 0x7f || character == '/' || character == '\\') {
                safe.append('_');
            } else {
                safe.append(character);
            }
        }
        String result = safe.toString().trim();
        while (result.startsWith(".")) {
            result = result.substring(1).trim();
        }
        if (result.isEmpty() || ".".equals(result) || "..".equals(result)) {
            return "unnamed-file";
        }
        return result;
    }
}
