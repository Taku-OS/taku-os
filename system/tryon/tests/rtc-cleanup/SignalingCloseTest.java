// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.os.Handler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/** Actual adapter/factory close paths with sockets that model Conscrypt's close-notify. */
public final class SignalingCloseTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        for (FalRealtimeProfile profile : FalRealtimeProfile.values()) {
            cleanup(profile, false, false);
            cleanup(profile, true, false);
            cleanup(profile, false, true);
        }
        concurrentRelease();
        concurrentAbort();
        lateTlsCreation(false);
        lateTlsCreation(true);
        System.out.println("SignalingCloseTest: " + checks + " actual TLS/RTC actor, abort, idempotence and cleanup-confirmation checks passed; no network/JNI/model");
    }

    private static void cleanup(FalRealtimeProfile profile, boolean failTls, boolean failRaw) throws Exception {
        Thread ui = Thread.currentThread();
        Plain raw = new Plain(failRaw);
        Tls tls = new Tls(raw, ui, failTls);
        FalSignalingSocketFactory transport = transport(raw, tls);
        AtomicInteger receipts = new AtomicInteger(), confirmedReceipts = new AtomicInteger();
        OfflineSocket socket = new OfflineSocket(ui);
        CallTryOnRtc rtc = new CallTryOnRtc(new Context(), new CallTryOnRtc.Listener() {
            public void onState(String state) {}
            public void onRemoteFrame(byte[] frame,int width,int height) { throw new AssertionError(); }
            public void onFailure(String code) { throw new AssertionError(code); }
            public void onCleanup(boolean confirmed) {
                check(Thread.currentThread() != ui, "cleanup receipt stays on actor");
                check(tls.closes.get() == 1 && socket.closes.get() == 1, "receipt waits for both TLS and WebSocket close");
                receipts.incrementAndGet(); if (confirmed) confirmedReceipts.incrementAndGet();
            }
        }, profile);
        set(rtc, "signalingTransport", transport); set(rtc, "socket", socket);
        Handler actor = (Handler)get(rtc, "control");
        rtc.stop();
        check(raw.closes.get() == 1, "UI stop immediately aborts the raw socket");
        check(tls.closes.get() == 0 && socket.closes.get() == 0, "UI stop never performs TLS/WebSocket close-notify");
        check(receipts.get() == 0, "cleanup cannot be claimed before actor release");
        Thread cleanup = new Thread(actor::drain, "offline-rtc-cleanup"); cleanup.start(); cleanup.join(3000);
        check(!cleanup.isAlive() && receipts.get() == 1, "actor completes one receipt");
        check(confirmedReceipts.get() == (failTls || failRaw ? 0 : 1), "failed close keeps cleanup unconfirmed");
        check(actor.hasQuit(), "existing cleanup actor is released");
        rtc.stop(); transport.cancel();
        check(transport.completeClose() == !(failTls || failRaw), "completion result is stable on repeat");
        check(raw.closes.get() == 1 && tls.closes.get() == 1 && socket.closes.get() == 1, "late stop/close does not duplicate resource release");
    }

    private static void concurrentRelease() throws Exception {
        Plain raw = new Plain(false);
        Tls tls = new Tls(raw, Thread.currentThread(), false);
        tls.entered = new CountDownLatch(1); tls.release = new CountDownLatch(1);
        FalSignalingSocketFactory transport = transport(raw, tls);
        AtomicInteger results = new AtomicInteger();
        Thread first = new Thread(() -> { if (transport.completeClose()) results.incrementAndGet(); }, "close-first");
        Thread second = new Thread(() -> { if (transport.completeClose()) results.incrementAndGet(); }, "close-second");
        first.start(); check(tls.entered.await(3,TimeUnit.SECONDS), "first release enters TLS close");
        second.start(); transport.cancel();
        check(results.get() == 0, "no completion before TLS close returns");
        tls.release.countDown(); first.join(3000); second.join(3000);
        check(!first.isAlive() && !second.isAlive() && results.get() == 2, "concurrent completion callers wait for the same release");
        check(raw.closes.get() == 1 && tls.closes.get() == 1, "concurrent release closes each owned socket once");
    }

    private static void concurrentAbort() throws Exception {
        Plain raw = new Plain(false);
        raw.entered = new CountDownLatch(1); raw.release = new CountDownLatch(1);
        Tls tls = new Tls(raw, Thread.currentThread(), false);
        FalSignalingSocketFactory transport = transport(raw,tls);
        AtomicInteger results = new AtomicInteger();
        Thread abort = new Thread(transport::cancel,"abort-fixture");
        Thread complete = new Thread(() -> { if (transport.completeClose()) results.incrementAndGet(); },"complete-fixture");
        abort.start(); check(raw.entered.await(3,TimeUnit.SECONDS),"abort owns the raw socket close");
        complete.start();
        check(tls.closes.get() == 0 && results.get() == 0,"concurrent release cannot bypass the unfinished raw abort");
        raw.release.countDown(); abort.join(3000); complete.join(3000);
        check(!abort.isAlive() && !complete.isAlive() && results.get() == 1,"release completes after raw abort");
        check(raw.closes.get() == 1 && tls.closes.get() == 1,"racing abort and release close once");
    }

    private static void lateTlsCreation(boolean failLateClose) throws Exception {
        Plain raw = new Plain(false);
        Tls tls = new Tls(raw,Thread.currentThread(),failLateClose);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        SSLSocketFactory factory = new SSLSocketFactory() {
            public Socket createSocket(Socket socket,String host,int port,boolean autoClose) throws IOException {
                check(socket == raw && autoClose,"actual CONNECT path requests the owned TLS wrapper");
                entered.countDown();
                try { if (!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("TLS construction timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return tls;
            }
            public String[] getDefaultCipherSuites(){return new String[0];}
            public String[] getSupportedCipherSuites(){return new String[0];}
            public Socket createSocket(String h,int p){throw new AssertionError();}
            public Socket createSocket(String h,int p,InetAddress l,int q){throw new AssertionError();}
            public Socket createSocket(InetAddress h,int p){throw new AssertionError();}
            public Socket createSocket(InetAddress h,int p,InetAddress l,int q){throw new AssertionError();}
        };
        FalSignalingSocketFactory transport = new FalSignalingSocketFactory(
                new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1234)),null,factory,
                host -> { throw new AssertionError("DNS forbidden"); },() -> raw,System::nanoTime,15000);
        AtomicInteger rejections = new AtomicInteger(), results = new AtomicInteger();
        CountDownLatch cleanupReturned = new CountDownLatch(1);
        Thread creator = new Thread(() -> {
            try { transport.createSocket(); throw new AssertionError("cancelled creation returned a socket"); }
            catch (IOException expected) { rejections.incrementAndGet(); }
        },"late-TLS-creator");
        Thread cleanup = new Thread(() -> {
            if (transport.completeClose()) results.incrementAndGet(); cleanupReturned.countDown();
        },"late-TLS-cleanup");
        creator.start(); check(entered.await(3,TimeUnit.SECONDS),"real creation reaches the blocked TLS factory");
        transport.cancel(); cleanup.start();
        check(raw.closes.get() == 1,"stop immediately aborts raw socket during TLS creation");
        check(!cleanupReturned.await(100,TimeUnit.MILLISECONDS),"cleanup waits for late TLS creation and rejection release");
        check(tls.closes.get() == 0,"no TLS wrapper exists to release yet");
        release.countDown(); creator.join(3000); cleanup.join(3000);
        check(!creator.isAlive() && !cleanup.isAlive() && rejections.get() == 1,"cancelled creator exits without ownership or deadlock");
        check(tls.closes.get() == 1 && results.get() == (failLateClose ? 0 : 1),"late wrapper closes once and its failure reaches cleanup confirmation");
        check(transport.completeClose() == !failLateClose,"late wrapper result remains stable");
    }

    private static FalSignalingSocketFactory transport(Plain raw, Tls tls) throws Exception {
        FalSignalingSocketFactory result = new FalSignalingSocketFactory(
                new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1234)), null);
        set(result,"raw",raw); set(result,"tls",tls); return result;
    }
    private static final class Plain extends Socket {
        final AtomicInteger closes = new AtomicInteger(); final boolean fail;
        CountDownLatch entered, release;
        Plain(boolean fail) { this.fail=fail; }
        @Override public void connect(SocketAddress endpoint,int timeout) {}
        @Override public void setSoTimeout(int timeout) {}
        @Override public InputStream getInputStream() { return new ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public void close() throws IOException {
            closes.incrementAndGet();
            if (entered != null) { entered.countDown(); try { if (!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("abort timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); } }
            if (fail) throw new IOException("fixture raw close");
        }
    }
    private static final class Tls extends SSLSocket {
        final Plain raw; final Thread ui; final boolean fail; final AtomicInteger closes = new AtomicInteger();
        CountDownLatch entered, release;
        Tls(Plain raw,Thread ui,boolean fail) { this.raw=raw; this.ui=ui; this.fail=fail; }
        @Override public void close() throws IOException {
            check(Thread.currentThread() != ui, "Conscrypt close-notify must never run on the UI caller");
            check(raw.closes.get() == 1, "raw transport is aborted before TLS close-notify");
            closes.incrementAndGet();
            if (entered != null) { entered.countDown(); try { if (!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); } }
            if (fail) throw new IOException("fixture TLS close");
        }
        public String[] getSupportedCipherSuites(){return new String[0];}
        public String[] getEnabledCipherSuites(){return new String[0];}
        public void setEnabledCipherSuites(String[] values){}
        public String[] getSupportedProtocols(){return new String[0];}
        public String[] getEnabledProtocols(){return new String[0];}
        public void setEnabledProtocols(String[] values){}
        public SSLSession getSession(){return null;}
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener){}
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener){}
        public void startHandshake(){throw new AssertionError("network forbidden");}
        public void setUseClientMode(boolean value){}
        public boolean getUseClientMode(){return true;}
        public void setNeedClientAuth(boolean value){}
        public boolean getNeedClientAuth(){return false;}
        public void setWantClientAuth(boolean value){}
        public boolean getWantClientAuth(){return false;}
        public void setEnableSessionCreation(boolean value){}
        public boolean getEnableSessionCreation(){return false;}
    }
    private static final class OfflineSocket extends WebSocketClient {
        final Thread ui; final AtomicInteger closes = new AtomicInteger();
        OfflineSocket(Thread ui) { super(URI.create("wss://fixture.invalid/no-network")); this.ui=ui; }
        @Override public void closeConnection(int code,String reason) {
            check(Thread.currentThread() != ui, "WebSocket channel close also stays off UI"); closes.incrementAndGet();
        }
        @Override public void connect(){throw new AssertionError("network forbidden");}
        public void onOpen(ServerHandshake value){throw new AssertionError();}
        public void onMessage(String value){throw new AssertionError();}
        public void onClose(int code,String reason,boolean remote){}
        public void onError(Exception value){throw new AssertionError(value);}
    }
    private static Object get(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    private static synchronized void check(boolean value,String message) {
        checks++; if(!value)throw new AssertionError(message);
    }
}
