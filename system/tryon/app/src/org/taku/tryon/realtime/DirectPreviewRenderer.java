// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded return-frame ownership and generation gating, independent of Android for host tests.
 * A decode remains in flight until its UI callback finishes: slow UI cannot accumulate bitmaps.
 */
final class DirectPreviewRenderer<B> implements AutoCloseable {
    static final int MAX_JPEG_BYTES = 400 * 1024, MAX_EDGE = 1920;
    interface Decoder<B> { B decode(byte[] jpeg, int width, int height) throws Exception; }
    interface Display<B> { void show(B value); void clear(); void release(B value); }
    interface Dispatcher { boolean post(Runnable task); }

    private final Object lock = new Object();
    private final Decoder<B> decoder;
    private final Display<B> display;
    private final Dispatcher ui;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private long generation, epoch;
    private boolean active, closed, decoding;
    private Pending pending;
    // All changes to the displayed value happen on the UI dispatcher, under the same lease lock.
    private B displayed;

    DirectPreviewRenderer(Decoder<B> decoder, Display<B> display, Dispatcher ui) {
        this(decoder, display, ui, newWorker(), true);
    }

    /** Deterministic host tests supply queued executors and a UI dispatcher. */
    DirectPreviewRenderer(Decoder<B> decoder, Display<B> display, Dispatcher ui, Executor worker) {
        this(decoder, display, ui, worker, false);
    }

    private DirectPreviewRenderer(Decoder<B> decoder, Display<B> display, Dispatcher ui,
            Executor worker, boolean ownWorker) {
        if (decoder == null || display == null || ui == null || worker == null)
            throw new IllegalArgumentException("missing_direct_preview_dependency");
        this.decoder = decoder; this.display = display; this.ui = ui; this.worker = worker;
        ownedWorker = ownWorker ? (ExecutorService) worker : null;
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "shopping-return-preview"); thread.setDaemon(true); return thread;
        });
    }

    /** Run generations are process-wide monotonic IDs supplied by LocalShoppingFrameBridge. */
    boolean begin(long value) {
        synchronized (lock) {
            if (closed || value <= 0 || value <= generation) return false;
            generation = value; active = true; epoch++; pending = null;
            clearLocked(epoch);
            return true;
        }
    }

    boolean offer(byte[] jpeg, int width, int height, long value) {
        if (!valid(jpeg, width, height)) return false;
        synchronized (lock) {
            if (closed || !active || value != generation) return false;
            // Only the newest undecoded JPEG is retained. The producer continues owning its array.
            try { pending = new Pending(jpeg.clone(), width, height, generation, epoch); }
            catch (OutOfMemoryError unavailable) { return false; }
            scheduleLocked();
            return true;
        }
    }

    void end(long value) {
        synchronized (lock) {
            if (closed || value != generation || !active) return;
            active = false; epoch++; pending = null;
            clearLocked(epoch);
        }
    }

    /** Rotation invalidates old geometry without ending camera input or the live RTC run. */
    void invalidatePresentation() {
        synchronized (lock) {
            if (closed) return;
            epoch++; pending = null;
            clearLocked(epoch);
        }
    }

    private void scheduleLocked() {
        if (decoding || closed || !active || pending == null) return;
        decoding = true;
        try { worker.execute(this::decodeNext); }
        catch (RejectedExecutionException stopped) { decoding = false; pending = null; }
    }

    private void decodeNext() {
        Pending frame;
        synchronized (lock) {
            frame = pending; pending = null;
            if (!current(frame)) { finishLocked(); return; }
        }
        B bitmap = null;
        try { bitmap = decoder.decode(frame.jpeg, frame.width, frame.height); }
        catch (Exception | OutOfMemoryError invalid) { /* Invalid returns never alter camera/session state. */ }
        if (bitmap == null) {
            synchronized (lock) { finishLocked(); }
            return;
        }
        B completed = bitmap;
        try {
            if (ui.post(() -> present(frame, completed))) return;
        } catch (RuntimeException stopped) { /* The decoded value still belongs to this worker. */ }
        release(completed);
        synchronized (lock) { finishLocked(); }
    }

    private void present(Pending frame, B value) {
        synchronized (lock) {
            try {
                if (!current(frame)) { release(value); return; }
                B previous = displayed;
                try {
                    display.show(value);
                    displayed = value;
                } catch (RuntimeException | OutOfMemoryError unavailable) {
                    // Detach before reclaiming either possible ImageView owner.
                    try { display.clear(); } catch (RuntimeException ignored) { }
                    displayed = null; release(value);
                } finally { if (previous != null) release(previous); }
            } finally { finishLocked(); }
        }
    }

    private boolean current(Pending frame) {
        return frame != null && !closed && active && frame.generation == generation && frame.epoch == epoch;
    }

    private void finishLocked() {
        decoding = false;
        scheduleLocked();
    }

    /** Must be enqueued while holding lock, so a newer generation cannot present before its clear. */
    private void clearLocked(long ticket) {
        Runnable clear = () -> {
            synchronized (lock) {
                if (ticket != epoch) return;
                B previous = displayed; displayed = null;
                try { display.clear(); }
                catch (RuntimeException unavailable) { /* The old image lease still has to be reclaimed. */ }
                finally { if (previous != null) release(previous); }
            }
        };
        try { if (ui.post(clear)) return; }
        catch (RuntimeException stopped) { /* No live UI consumer can claim a new value. */ }
        // A stopped UI dispatcher cannot consume frames. Release its old lease without queueing.
        B previous = displayed; displayed = null;
        if (previous != null) release(previous);
    }

    private void release(B value) {
        try { display.release(value); } catch (RuntimeException ignored) { }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true; active = false; epoch++; pending = null;
            clearLocked(epoch);
        }
        // Do not remove UI callbacks or abandon a decoded bitmap already posted for presentation.
        if (ownedWorker != null) ownedWorker.shutdown();
    }

    private static boolean valid(byte[] jpeg, int width, int height) {
        return jpeg != null && jpeg.length >= 4 && jpeg.length <= MAX_JPEG_BYTES
                && width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE
                && (long) width * height <= (long) MAX_EDGE * MAX_EDGE
                && (jpeg[0] & 255) == 255 && (jpeg[1] & 255) == 216
                && (jpeg[jpeg.length - 2] & 255) == 255 && (jpeg[jpeg.length - 1] & 255) == 217;
    }

    private static final class Pending {
        final byte[] jpeg;
        final int width, height;
        final long generation, epoch;
        Pending(byte[] jpeg, int width, int height, long generation, long epoch) {
            this.jpeg = jpeg; this.width = width; this.height = height;
            this.generation = generation; this.epoch = epoch;
        }
    }
}
