package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;

final class PhoneNetwork {
    private PhoneNetwork() {}

    static HttpURLConnection openConnection(URL url) throws Exception {
        // The root Broker does not use Android VpnService's per-app routing.
        File setting = new File("/data/local/agentos/network-proxy");
        if (!setting.isFile()) return (HttpURLConnection) url.openConnection();
        URI endpoint = new URI(TaskBroker.read(setting).trim());
        if (!"http".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535 || endpoint.getUserInfo() != null) {
            throw new IOException("Invalid phone network proxy configuration");
        }
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(endpoint.getHost(), endpoint.getPort()));
        return (HttpURLConnection) url.openConnection(proxy);
    }
}
