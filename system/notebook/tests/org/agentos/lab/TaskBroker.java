package org.agentos.lab;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host-only file reader for the video resolver's optional existing proxy file. */
final class TaskBroker {
    static String read(File file) throws Exception {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }
}
