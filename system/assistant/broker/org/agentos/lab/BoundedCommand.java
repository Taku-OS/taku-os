package org.agentos.lab;

import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/** File redirection avoids a pipe reader that could outlive its timed command. */
final class BoundedCommand {
    private static final Semaphore slots = new Semaphore(SessionRemovals.WORKERS);
    private static final Set<java.lang.Process> stopping = ConcurrentHashMap.newKeySet();
    static String run(File directory, long timeoutMs, String... arguments) throws Exception {
        for (java.lang.Process previous : stopping)
            if (!previous.isAlive() && stopping.remove(previous)) slots.release();
        if (!slots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) throw new TimeoutException("COMMAND_EXIT_UNCONFIRMED");
        File output = null;
        java.lang.Process process = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("COMMAND_OUTPUT_UNAVAILABLE");
            output = File.createTempFile("cleanup-", ".out", directory);
            process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output).start();
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS))
                throw new TimeoutException("SESSION_CLEANUP_COMMAND_TIMEOUT");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = new FileInputStream(output)) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (bytes.size() + count > 8 * 1024 * 1024) throw new IOException("COMMAND_OUTPUT_TOO_LARGE");
                    bytes.write(buffer, 0, count);
                }
            }
            String result = bytes.toString("UTF-8");
            if (process.exitValue() != 0) throw new IOException("command exit=" + process.exitValue() + ": " + result);
            return result;
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    boolean interrupted = Thread.interrupted();
                    try {
                        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("COMMAND_EXIT_UNCONFIRMED");
                    } finally { if (interrupted) Thread.currentThread().interrupt(); }
                }
            } finally {
                // Even an unconfirmed kernel-level exit cannot cause unbounded replacement children.
                if (process != null && process.isAlive()) stopping.add(process); else slots.release();
                if (output != null && !output.delete() && output.exists()) throw new IOException("COMMAND_OUTPUT_CLEANUP_FAILED");
            }
        }
    }
}
