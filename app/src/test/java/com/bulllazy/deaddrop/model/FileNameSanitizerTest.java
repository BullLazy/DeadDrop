package com.bulllazy.deaddrop.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FileNameSanitizerTest {
    @Test
    public void removesPathSeparatorsAndControlCharacters() {
        assertEquals("_secret_.txt", FileNameSanitizer.sanitize("../secret\n.txt"));
    }

    @Test
    public void doesNotAllowDotOnlyNames() {
        assertEquals("unnamed-file", FileNameSanitizer.sanitize("..."));
    }
}
