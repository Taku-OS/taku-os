package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class TaskBroker {
    static final File ROOT = new File(requiredRoot());
    private static String requiredRoot() {
        String root=System.getProperty("main.reply.test.root");
        if(root==null||root.isBlank())throw new AssertionError("An isolated fixture root is required");
        return root;
    }
    static String read(File file) throws IOException {return Files.readString(file.toPath(),StandardCharsets.UTF_8);}
    static void write(File file,String text) throws IOException {
        Files.createDirectories(file.toPath().getParent());
        Files.writeString(file.toPath(),text,StandardCharsets.UTF_8);
    }
}
