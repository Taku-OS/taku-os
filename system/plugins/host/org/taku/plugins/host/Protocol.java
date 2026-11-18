package org.taku.plugins.host;

import java.nio.file.Paths;
import java.util.*;

final class Protocol {
    private Protocol() { }
    static Map<String,Object> execute(PluginManager manager, Map<?,?> request) throws Exception {
        Object operation = request.get("operation"), argument = request.get("argument");
        if (!(operation instanceof String)) throw new PluginException("USAGE", "operation required");
        Object result;
        switch ((String)operation) {
            case "ping": result = Map.of("running", true); break;
            case "list": if (argument != null) throw new PluginException("USAGE", "list takes no argument"); result = manager.list(); break;
            case "tools": result = manager.tools(argument == null ? null : required(argument)); break;
            case "catalog": result = manager.catalog(); break;
            case "tool": result = manager.tool(required(argument), required(request.get("name"))); break;
            case "checks": result = manager.checks(argument == null ? null : required(argument)); break;
            case "evidence": result = manager.evidence(argument == null ? null : required(argument)); break;
            case "local":
                Object local = request.get("arguments");
                if (!(local instanceof Map)) throw new PluginException("USAGE", "local arguments must be an object");
                result = new LocalDeviceBackend().execute((Map<?,?>)local); break;
            case "show": result = manager.show(required(argument)); break;
            case "install": result = manager.install(Paths.get(required(argument))); break;
            case "install-url":
                Object remote = request.get("arguments");
                if (!(remote instanceof Map)) throw new PluginException("USAGE", "install-url arguments must be an object");
                Map<?,?> remoteArgs = (Map<?,?>)remote;
                Object signature = remoteArgs.get("signature"), publicKey = remoteArgs.get("publicKey");
                result = manager.installUrl(required(remoteArgs.get("url")), required(remoteArgs.get("sha256")),
                        signature instanceof String ? (String)signature : null,
                        publicKey instanceof String ? (String)publicKey : null); break;
            case "remove": result = manager.remove(required(argument)); break;
            case "enable": result = manager.enable(required(argument)); break;
            case "disable": result = manager.disable(required(argument)); break;
            case "reload": result = manager.reload(required(argument)); break;
            case "rollback": result = manager.rollback(required(argument)); break;
            case "call-tool":
            case "run-check":
                Object values = request.get("arguments");
                if (!(values instanceof Map)) throw new PluginException("USAGE", "arguments must be an object");
                @SuppressWarnings("unchecked") Map<String,Object> arguments = (Map<String,Object>)values;
                result = "run-check".equals(operation) ? manager.runCheck(required(argument), required(request.get("name")), arguments) : manager.callTool(required(argument), required(request.get("name")), arguments); break;
            case "shutdown": result = Map.of("stopping", true); break;
            default: throw new PluginException("USAGE", "unknown operation");
        }
        return Map.of("ok", true, "result", result);
    }
    private static String required(Object value) throws PluginException {
        if (!(value instanceof String) || ((String)value).isEmpty()) throw new PluginException("USAGE", "argument required");
        return (String)value;
    }
}
