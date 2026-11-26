package eu.efti.tst.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reads the text resources of this package. */
final class Resources {

    private Resources() {
    }

    static String read(String name) {
        try (InputStream in = Objects.requireNonNull(Resources.class.getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
