// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import javax.net.SocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** One cancellable HTTP CONNECT + platform TLS transport for the fixed fal signaling host. */
public final class FalSignalingSocketFactory extends SocketFactory implements AutoCloseable {
    public interface Observer {
        default void onRoute(String route) {}
        default void onProxyConnectStatus(int status) {}
        default void onConnectionStage(String stage) {}
    }
    interface Resolver { InetAddress resolve(String host) throws IOException; }
    interface Dialer { Socket create() throws IOException; }
    private static final String HOST = "fal.run";
    private static final int PORT = 443, BUDGET_MS = 15000, MAX_HEADER_BYTES = 8192, MAX_HEADER_LINES = 64;
    private static final URI SELECTOR_URI = URI.create("https://fal.run/");
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private static final ThreadPoolExecutor RESOLVERS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), daemon("tryon-proxy-resolver"));
    private final Object lock = new Object();
    private final InetSocketAddress proxyAddress;
    private final Observer observer;
    private final SSLSocketFactory tlsFactory;
    private final Resolver resolver;
    private final Dialer dialer;
    private final LongSupplier nanoTime;
    private final long budgetNanos;
    private boolean started, opened, closed, expired;
    private long began;
    private Socket raw;
    private SSLSocket tls;
    private Future<?> lookup;
    private ScheduledFuture<?> deadline;

    /** Uses only the first system choice; an error never silently falls back to a direct route. */
    public static Proxy selectProxy() {
        ProxySelector selector = ProxySelector.getDefault();
        if (selector == null) return Proxy.NO_PROXY;
        final List<Proxy> choices;
        try { choices = selector.select(SELECTOR_URI); }
        catch (RuntimeException failed) { throw new IllegalStateException("signaling_proxy_selection_failed"); }
        if (choices == null || choices.isEmpty()) return Proxy.NO_PROXY;
        Proxy first = choices.get(0);
        if (first == null) throw new IllegalStateException("signaling_proxy_selection_failed");
        return first;
    }

    public static String route(Proxy proxy) {
        if (proxy == null) throw new IllegalArgumentException("signaling_proxy_required");
        switch (proxy.type()) {
            case DIRECT: return "direct";
            case HTTP: return "http_connect_proxy";
            case SOCKS: return "socks_proxy";
            default: throw new IllegalArgumentException("signaling_proxy_unsupported");
        }
    }

    public FalSignalingSocketFactory(Proxy httpProxy, Observer observer) {
        this(httpProxy, observer, (SSLSocketFactory) SSLSocketFactory.getDefault(),
                InetAddress::getByName, () -> new Socket(Proxy.NO_PROXY), System::nanoTime, BUDGET_MS);
    }

    /** Local-test injection only; production always uses platform trust and a single 15-second budget. */
    FalSignalingSocketFactory(Proxy httpProxy, Observer observer, SSLSocketFactory tlsFactory,
            Resolver resolver, Dialer dialer, LongSupplier nanoTime, long budgetMs) {
        if (httpProxy == null || httpProxy.type() != Proxy.Type.HTTP || !(httpProxy.address() instanceof InetSocketAddress)
                || tlsFactory == null || resolver == null || dialer == null || nanoTime == null || budgetMs < 1 || budgetMs > BUDGET_MS)
            throw new IllegalArgumentException("signaling_proxy_configuration_invalid");
        proxyAddress = (InetSocketAddress) httpProxy.address();
        if (proxyAddress.getPort() < 1 || proxyAddress.getPort() > 65535 || proxyAddress.getHostString().isEmpty())
            throw new IllegalArgumentException("signaling_proxy_configuration_invalid");
        this.observer = observer == null ? new Observer() {} : observer;
        this.tlsFactory = tlsFactory; this.resolver = resolver; this.dialer = dialer; this.nanoTime = nanoTime;
        budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMs);
    }

    @Override public Socket createSocket() throws IOException {
        synchronized (lock) {
            requireOpen();
            if (started) throw new IOException("signaling_socket_already_created");
            started = true; began = nanoTime.getAsLong();
            deadline = DEADLINES.schedule(() -> shutdown(true), budgetNanos, TimeUnit.NANOSECONDS);
        }
        eventRoute(); eventStage("proxy_connecting");
        try {
            Socket transport = dialer.create();
            register(transport, false);
            InetAddress address = proxyAddress.getAddress();
            if (address == null) address = resolveProxy();
            transport.connect(new InetSocketAddress(address, proxyAddress.getPort()), remainingMillis());
            transport.setSoTimeout(remainingMillis());
            eventStage("proxy_tunneling");
            connectTunnel(transport);
            eventStage("tls_handshaking");
            Socket secured = tlsFactory.createSocket(transport, HOST, PORT, true);
            if (!(secured instanceof SSLSocket)) { closeQuietly(secured); throw new IOException("signaling_tls_socket_required"); }
            SSLSocket secure = (SSLSocket) secured;
            register(secure, true);
            SSLParameters parameters = secure.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(HOST)));
            secure.setSSLParameters(parameters);
            secure.setSoTimeout(remainingMillis());
            secure.startHandshake();
            secure.setSoTimeout(remainingMillis());
            eventStage("websocket_handshaking");
            synchronized (lock) { requireOpen(); }
            return secure;
        } catch (IOException | RuntimeException failure) {
            boolean timeout = isExpired();
            shutdown(timeout);
            if (timeout) throw timeout();
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("signaling_transport_failed", failure);
        }
    }

    private InetAddress resolveProxy() throws IOException {
        final Future<InetAddress> pending;
        try { pending = RESOLVERS.submit(() -> resolver.resolve(proxyAddress.getHostString())); }
        catch (RejectedExecutionException busy) { throw new IOException("signaling_proxy_resolution_busy"); }
        synchronized (lock) {
            if (closed) { pending.cancel(true); requireOpen(); }
            lookup = pending;
        }
        try {
            InetAddress address = pending.get(remainingMillis(), TimeUnit.MILLISECONDS);
            if (address == null) throw new IOException("signaling_proxy_resolution_failed");
            remainingMillis();
            return address;
        } catch (TimeoutException failed) {
            shutdown(true); throw timeout();
        } catch (CancellationException failed) {
            synchronized (lock) { requireOpen(); }
            throw new IOException("signaling_proxy_resolution_cancelled");
        } catch (InterruptedException failed) {
            Thread.currentThread().interrupt(); throw new IOException("signaling_proxy_resolution_interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("signaling_proxy_resolution_failed", cause);
        } finally {
            synchronized (lock) { if (lookup == pending) lookup = null; }
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private void connectTunnel(Socket transport) throws IOException {
        OutputStream out = transport.getOutputStream();
        out.write(("CONNECT " + HOST + ":" + PORT + " HTTP/1.1\r\nHost: " + HOST + ":" + PORT
                + "\r\nProxy-Connection: Keep-Alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = transport.getInputStream(); int[] bytes = {0};
        String line = line(in, bytes);
        if (!(line.startsWith("HTTP/1.1 ") || line.startsWith("HTTP/1.0 ")) || line.length() < 12
                || line.length() > 12 && line.charAt(12) != ' ')
            throw new ProtocolException("signaling_proxy_invalid_response");
        int status = 0;
        for (int i = 9; i < 12; i++) {
            char digit = line.charAt(i);
            if (digit < '0' || digit > '9') throw new ProtocolException("signaling_proxy_invalid_response");
            status = status * 10 + digit - '0';
        }
        if (status < 100 || status > 599) throw new ProtocolException("signaling_proxy_invalid_response");
        eventStatus(status);
        if (status != 200) throw new ProtocolException("signaling_proxy_connect_rejected");
        int headers = 0;
        while (!line(in, bytes).isEmpty())
            if (++headers > MAX_HEADER_LINES) throw new ProtocolException("signaling_proxy_headers_limit");
    }

    private String line(InputStream in, int[] bytes) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream(80); int previous = -1;
        while (true) {
            remainingMillis();
            int value = in.read();
            if (value < 0) throw new ProtocolException("signaling_proxy_incomplete_response");
            if (++bytes[0] > MAX_HEADER_BYTES) throw new ProtocolException("signaling_proxy_headers_limit");
            if (value == '\n') {
                if (previous != '\r') throw new ProtocolException("signaling_proxy_invalid_response");
                byte[] line = text.toByteArray();
                return new String(line, 0, line.length - 1, StandardCharsets.US_ASCII);
            }
            if (value < 32 && value != '\r' && value != '\t' || value > 126)
                throw new ProtocolException("signaling_proxy_invalid_response");
            if (previous == '\r') throw new ProtocolException("signaling_proxy_invalid_response");
            text.write(value); previous = value;
        }
    }

    /** Called at the WebSocket callback entry, before initial model input is sent. */
    public boolean markOpened() {
        boolean ready = false, timeout = false;
        synchronized (lock) {
            if (closed || !started || tls == null) return false;
            timeout = isExpiredLocked();
            if (!timeout) try {
                tls.setSoTimeout(0); opened = true; ready = true;
                if (deadline != null) deadline.cancel(false);
            } catch (SocketException failed) {
                // Cancellation and close-notify stay outside this small ownership monitor.
            }
        }
        if (!ready) { shutdown(timeout); return false; }
        eventStage("connected"); return true;
    }
    public boolean timedOut() { synchronized (lock) { return expired || started && !opened && isExpiredLocked(); } }
    public void cancel() { shutdown(false); }
    @Override public void close() { cancel(); }

    private int remainingMillis() throws IOException {
        synchronized (lock) {
            requireOpen();
            long remaining = budgetNanos - (nanoTime.getAsLong() - began);
            if (remaining <= 0) throw timeout();
            return (int) Math.min(BUDGET_MS, Math.max(1, (remaining + 999999L) / 1000000L));
        }
    }
    private boolean isExpiredLocked() { return !closed && started && !opened && nanoTime.getAsLong() - began >= budgetNanos; }
    private boolean isExpired() { synchronized (lock) { return expired || isExpiredLocked(); } }
    private void requireOpen() throws IOException {
        if (expired) throw timeout();
        if (closed) throw new SocketException("signaling_socket_cancelled");
    }
    private static SocketTimeoutException timeout() { return new SocketTimeoutException("signaling_connect_timeout"); }
    private void register(Socket socket, boolean secure) throws IOException {
        boolean accepted;
        synchronized (lock) {
            accepted = !closed;
            if (accepted) { if (secure) tls = (SSLSocket) socket; else raw = socket; }
        }
        if (!accepted) {
            closeQuietly(socket);
            synchronized (lock) { requireOpen(); }
        }
    }
    private void shutdown(boolean deadlineExpired) {
        Socket plain, secure; Future<?> resolving;
        synchronized (lock) {
            if (closed || deadlineExpired && opened) return;
            closed = true; expired = deadlineExpired;
            plain = raw; secure = tls; resolving = lookup;
            if (deadline != null) deadline.cancel(false);
        }
        if (resolving != null) resolving.cancel(true);
        closeQuietly(plain); // Abort native TLS IO before attempting TLS close-notify.
        if (secure != plain) closeQuietly(secure);
    }
    private static void closeQuietly(Socket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) {} }
    private void eventRoute() { try { observer.onRoute("http_connect_proxy"); } catch (RuntimeException ignored) {} }
    private void eventStatus(int status) { try { observer.onProxyConnectStatus(status); } catch (RuntimeException ignored) {} }
    private void eventStage(String stage) { try { observer.onConnectionStage(stage); } catch (RuntimeException ignored) {} }
    private static ThreadFactory daemon(String name) { return task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }; }
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, daemon("tryon-proxy-deadline"));
        executor.setRemoveOnCancelPolicy(true); return executor;
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        if (!HOST.equals(host) || port != PORT) throw new IOException("signaling_target_override_rejected");
        return createSocket();
    }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        throw new IOException("signaling_local_bind_unsupported");
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        throw new IOException("signaling_target_override_rejected");
    }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        throw new IOException("signaling_local_bind_unsupported");
    }
}
