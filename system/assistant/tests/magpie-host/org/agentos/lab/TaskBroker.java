package org.agentos.lab;
import java.io.File;
import java.nio.file.Files;
final class TaskBroker { static String read(File f)throws Exception{return Files.readString(f.toPath());} static void command(String...args){} }
