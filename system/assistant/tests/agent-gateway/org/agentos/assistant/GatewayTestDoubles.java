package org.agentos.assistant;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class TaskRequests {
    static volatile java.util.function.BiConsumer<File,String> beforeWrite;
    interface BeforePublish { void persist(String taskId) throws Exception; }
    static void write(File file, String value) throws java.io.IOException {
        java.util.function.BiConsumer<File,String> observer=beforeWrite;
        if(observer!=null)observer.accept(file,value);
        File temporary = new File(file.getPath() + ".tmp");
        Files.write(temporary.toPath(), value.getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
final class AssistantSession {
    static final File TASKS = tasks();
    private static File tasks(){try{return Files.createTempDirectory("taku-gateway-host-tasks-").toFile();}catch(java.io.IOException failure){throw new ExceptionInInitializerError(failure);}}
}
