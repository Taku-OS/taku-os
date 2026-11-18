package org.taku.plugins;

public final class RootDaemonBridge {
    public interface Transport {
        String request(String operation, String payload) throws Exception;
    }

    private final Transport transport;

    public RootDaemonBridge(Transport transport) {
        if (transport == null) throw new IllegalArgumentException("daemon transport required");
        this.transport = transport;
    }

    public String dispatch(String operation, String payload) throws Exception {
        if (operation == null || operation.isEmpty()) throw new IllegalArgumentException("operation required");
        return transport.request(operation, payload);
    }
}
