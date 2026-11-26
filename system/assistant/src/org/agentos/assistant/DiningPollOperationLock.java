package org.agentos.assistant;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/** Keeps the Broker's cancellation check fenced while a native callback can still send input. */
final class DiningPollOperationLock implements AutoCloseable {
    private static final Map<Path, DiningPollOperationLock> ACTIVE = new HashMap<>();
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;

    private DiningPollOperationLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    static synchronized DiningPollOperationLock acquire(Path path) throws IOException {
        // Keep only one process-local channel per inode path so another callback cannot release
        // an active process-level lock by closing a second channel on the same operation file.
        if (ACTIVE.containsKey(path)) throw new IOException("POLL_OPERATION_BUSY");
        FileChannel channel =
                FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock;
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("POLL_OPERATION_BUSY");
        } catch (OverlappingFileLockException busy) {
            channel.close();
            throw new IOException("POLL_OPERATION_BUSY", busy);
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
        // Retain the channel until the process exits if UiAutomation never confirms shutdown.
        DiningPollOperationLock lease = new DiningPollOperationLock(path, channel, lock);
        ACTIVE.put(path, lease);
        return lease;
    }

    @Override
    public void close() throws IOException {
        synchronized (DiningPollOperationLock.class) {
            if (ACTIVE.get(path) != this) return;
            try (channel) {
                lock.release();
            } finally {
                ACTIVE.remove(path);
            }
        }
    }
}
