package org.taku.plugins.host;

import java.util.*;

final class WorkspaceState {
    private static final Map<String,Set<String>> TRANSITIONS = Map.of(
        "new", Set.of("inspecting", "stopped"),
        "inspecting", Set.of("building", "needs_you", "stopped"),
        "building", Set.of("checking", "needs_you", "stopped"),
        "checking", Set.of("ready", "needs_you", "stopped"),
        "needs_you", Set.of("inspecting", "building", "checking", "stopped"),
        "ready", Set.of("inspecting", "stopped"),
        "stopped", Set.of("inspecting"));
    private final String state;
    WorkspaceState(String state) {
        if (!TRANSITIONS.containsKey(state)) throw new IllegalArgumentException("unknown state");
        this.state = state;
    }
    WorkspaceState transition(String next) {
        if (!TRANSITIONS.get(state).contains(next)) throw new IllegalArgumentException("invalid transition " + state + " -> " + next);
        return new WorkspaceState(next);
    }
    String value() { return state; }
}
