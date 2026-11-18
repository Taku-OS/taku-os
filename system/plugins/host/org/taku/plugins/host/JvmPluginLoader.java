package org.taku.plugins.host;

import java.net.URLClassLoader;
import java.nio.file.Path;
import org.taku.plugins.TakuPlugin;

public final class JvmPluginLoader implements PluginLoader {
    public ClassLoader create(Path code, Path optimizedDirectory) throws Exception {
        return new URLClassLoader(new java.net.URL[]{code.toUri().toURL()}, TakuPlugin.class.getClassLoader());
    }
}
