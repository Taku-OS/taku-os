package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Stable slot address; a generation identifies one live owner of this address. */
final class DisplaySlot {
    static final String ROOT = "/data/local/agentos/virtual-display";
    static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    static final Pattern GENERATION = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    final String key, socket, name;
    final File directory;

    private DisplaySlot(String key) {
        this.key = key;
        boolean legacy = key.equals("default");
        directory = new File(legacy ? ROOT : ROOT + "/screens/" + key);
        socket = legacy ? "taku-mirror" : "taku-mirror-" + key;
        name = legacy ? "Taku-Independent-Screen" : "Taku-Independent-Screen-" + key;
    }

    static DisplaySlot defaultSlot() { return new DisplaySlot("default"); }
    static DisplaySlot named(String key) {
        if (key == null || !KEY.matcher(key).matches())
            throw new IllegalArgumentException("Invalid screen key: expected [a-z][a-z0-9-]{0,31}");
        return new DisplaySlot(key);
    }
    static DisplaySlot hostArguments(String[] args) {
        if (args.length > 1) throw new IllegalArgumentException("Expected at most one screen key");
        return args.length == 0 ? defaultSlot() : named(args[0]);
    }
    static void requireGeneration(String value) {
        if (value == null || !GENERATION.matcher(value).matches())
            throw new IllegalArgumentException("Invalid display generation");
    }
    static void requireSameGeneration(String expected, String actual) throws IOException {
        requireGeneration(expected); requireGeneration(actual);
        if (!expected.equals(actual))
            throw new IOException("Screen generation changed; refusing stale operation");
    }
    boolean permitsAbsentHostStop(String command, String expectedGeneration) {
        return key.equals("default") && command.equals("stop") && expectedGeneration == null;
    }
    void checkDirectory() throws IOException {
        if (!directory.getCanonicalPath().equals(directory.getAbsolutePath()))
            throw new IOException("Screen state directory must not contain symlinks");
    }
    void validateIdentity(JSONObject value, int peerPid, int peerUid) throws Exception {
        if (!key.equals(value.getString("screenKey"))
                || !socket.equals(value.getString("mirrorSocket"))
                || !name.equals(value.getString("name"))
                || !directory.getPath().equals(value.getString("stateDirectory"))
                || peerPid <= 0 || value.getInt("pid") != peerPid
                || value.getInt("uid") != peerUid
                || (peerUid != 0 && peerUid != 1000 && peerUid != 2000)
                || value.getInt("displayId") <= 0)
            throw new IOException("Live display identity does not match requested slot");
        requireGeneration(value.getString("generation"));
        checkDirectory();
    }
}
