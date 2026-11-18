package org.agentos.lab;

import org.json.JSONObject;

/** Disposable-emulator acceptance; calls the production discovery implementation. */
public final class WorkspaceDiscoveryProbe {
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (Exception error) { System.err.println(error.getMessage()); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("query|launchable JSON");
        JSONObject input = new JSONObject(args[1]);
        JSONObject result;
        if (args[0].equals("query")) result = PhoneAppCatalog.query(input);
        else if (args[0].equals("launchable")) result = PhoneAppCatalog.requireLaunchable(input.getString("packageName"));
        else throw new IllegalArgumentException("Unknown discovery probe");
        System.out.println("TAKU_DISCOVERY_TEST:" + result);
    }
}
