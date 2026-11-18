/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package org.agentos.island;

/** Shared Messenger wire contract; the service uses a separate process and signature binding. */
public final class IslandContentProtocol {
    public static final int VERSION = 1;
    public static final int ATTACH = 1;
    public static final int RELEASE = 2;
    public static final int READY = 3;
    public static final int FAILED = 4;
    public static final String BIND_PERMISSION = "org.agentos.permission.BIND_ISLAND_CONTENT";
    public static final String VERSION_KEY = "version";
    public static final String HOST_TOKEN = "hostToken";
    public static final String WIDTH = "width";
    public static final String HEIGHT = "height";
    public static final String DISPLAY = "display";
    public static final String TOKEN = "token";
    public static final String SURFACE = "surface";
    public static final int MAX_DIMENSION = 4096;
    private IslandContentProtocol() {}
}
