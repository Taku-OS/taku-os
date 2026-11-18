package org.taku.plugins.host;

public final class PluginException extends Exception {
    public final String code;
    public PluginException(String code, String message) { super(message); this.code = code; }
    public PluginException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
}
