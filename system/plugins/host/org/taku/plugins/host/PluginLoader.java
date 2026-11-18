package org.taku.plugins.host;

import java.nio.file.Path;

public interface PluginLoader {
    ClassLoader create(Path code, Path optimizedDirectory) throws Exception;
    default void close(ClassLoader loader) throws Exception {
        if (loader instanceof java.io.Closeable) ((java.io.Closeable)loader).close();
    }
}
