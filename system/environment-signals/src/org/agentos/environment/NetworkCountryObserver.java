package org.agentos.environment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Main-thread cellular-country snapshots; the caller owns the hosting lifecycle. */
public final class NetworkCountryObserver implements AutoCloseable {
    public static final long STABLE_MILLIS = 60_000;
    public static final long RESAMPLE_MILLIS = 15 * 60_000;
    @FunctionalInterface public interface Source { String readCountryIso(); }
    @FunctionalInterface public interface Listener { void onCountryObservation(CountryObservation observation); }
    public interface Subscription extends AutoCloseable { @Override void close(); }
    private final Context context;
    private final Source source;
    private final LongSupplier elapsedClock, wallClock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Entry> subscriptions = new ArrayList<>();
    private boolean closed, registered;
    private String continuousCountry = "";
    private long continuousSince = -1, sampleVersion;
    private CountryObservation latest;
    private final Runnable check = () -> { if (!closed && !subscriptions.isEmpty()) refresh(); };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed || subscriptions.isEmpty()) return;
            String action = intent.getAction();
            // Broadcasts only wake sampling; untrusted extras never assert a country.
            if (TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) refresh();
        }
    };

    public NetworkCountryObserver(Context context) {
        this(context, SystemClock::elapsedRealtime, System::currentTimeMillis, null);
    }

    /** Dependency injection for controlled checks. The production constructor always uses TelephonyManager. */
    public NetworkCountryObserver(Context context, Source source, LongSupplier elapsedClock, LongSupplier wallClock) {
        this(context, elapsedClock, wallClock, Objects.requireNonNull(source));
    }

    private NetworkCountryObserver(Context context, LongSupplier elapsedClock, LongSupplier wallClock, Source source) {
        requireMainThread();
        this.context = normalizeContext(context);
        this.source = source == null ? () -> {
            TelephonyManager telephony = this.context.getSystemService(TelephonyManager.class);
            if (telephony == null) throw new UnsupportedOperationException("No telephony service");
            return telephony.getNetworkCountryIso();
        } : source;
        this.elapsedClock = Objects.requireNonNull(elapsedClock);
        this.wallClock = Objects.requireNonNull(wallClock);
        latest = new CountryObservation("", CountryObservation.Reason.NOT_OBSERVED,
                wallClock.getAsLong(), elapsedClock.getAsLong(), -1, false);
    }

    /** Subscribes and samples immediately. Each live subscription receives the refreshed snapshot. */
    public Subscription subscribe(Listener listener) {
        requireMainThread();
        requireOpen();
        Entry entry = new Entry(Objects.requireNonNull(listener));
        if (subscriptions.isEmpty()) resetContinuity();
        subscriptions.add(entry);
        try {
            if (!registered) {
                IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                registered = true;
            }
            refresh();
            return entry;
        } catch (RuntimeException error) {
            entry.close();
            throw error;
        }
    }

    /** Returns the last snapshot without reading a source or implying that it is current. */
    public CountryObservation latest() { requireMainThread(); return latest; }

    /** Samples now, including with no subscribers; only subscribed periods have timer/broadcast monitoring. */
    public CountryObservation refresh() {
        requireMainThread();
        requireOpen();
        handler.removeCallbacks(check);
        String country = "";
        CountryObservation.Reason reason = CountryObservation.Reason.NONE;
        try {
            String raw = source.readCountryIso();
            country = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (country.isEmpty()) reason = CountryObservation.Reason.EMPTY_COUNTRY;
            else if (!country.matches("[A-Z]{2}")) {
                country = "";
                reason = CountryObservation.Reason.INVALID_COUNTRY;
            }
        } catch (SecurityException error) { reason = CountryObservation.Reason.SECURITY_DENIED; }
        catch (UnsupportedOperationException error) { reason = CountryObservation.Reason.UNSUPPORTED; }
        catch (RuntimeException error) { reason = CountryObservation.Reason.READ_FAILED; }
        long elapsed = elapsedClock.getAsLong();
        if (country.isEmpty()) {
            resetContinuity();
        } else if (!country.equals(continuousCountry) || elapsed < continuousSince) {
            continuousCountry = country;
            continuousSince = elapsed;
        }
        latest = new CountryObservation(country, reason, wallClock.getAsLong(), elapsed, continuousSince,
                !country.isEmpty() && elapsed - continuousSince >= STABLE_MILLIS);
        CountryObservation sampled = latest;
        long version = ++sampleVersion;
        schedule(sampled);
        for (Entry entry : new ArrayList<>(subscriptions)) {
            if (!closed && entry.active && version == sampleVersion) entry.listener.onCountryObservation(sampled);
        }
        return sampled;
    }

    private void schedule(CountryObservation observation) {
        if (closed || subscriptions.isEmpty()) return;
        long delay = observation.availability() == CountryObservation.Availability.AVAILABLE && !observation.stable()
                ? Math.max(1, STABLE_MILLIS - (observation.observedElapsedRealtimeMillis() - continuousSince))
                : RESAMPLE_MILLIS;
        handler.postDelayed(check, delay);
    }

    private void stopMonitoring() {
        handler.removeCallbacks(check);
        if (registered) { context.unregisterReceiver(receiver); registered = false; }
        resetContinuity();
    }

    private void resetContinuity() {
        continuousCountry = "";
        continuousSince = -1;
    }

    @Override public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        for (Entry entry : subscriptions) entry.active = false;
        subscriptions.clear();
        stopMonitoring();
    }

    private final class Entry implements Subscription {
        final Listener listener;
        boolean active = true;
        Entry(Listener listener) { this.listener = listener; }
        @Override public void close() {
            requireMainThread();
            if (!active) return;
            active = false;
            subscriptions.remove(this);
            if (subscriptions.isEmpty()) stopMonitoring();
        }
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Observer is closed"); }
    private static Context normalizeContext(Context context) {
        Context supplied = Objects.requireNonNull(context);
        Context application = supplied.getApplicationContext();
        return application == null ? supplied : application;
    }
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }
}
