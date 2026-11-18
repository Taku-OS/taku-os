package org.agentos.assistant;

/** Host validation of the narrow return marker; installed signature/Activity routing needs device QA. */
public final class AgentFrontendLauncherTest {
    public static void main(String[] args) {
        String thread = "f891f77d-019c-48e1-8935-75ee445126c1";
        String other = "f891f77d-019c-48e1-8935-75ee445126c2";
        if (!AgentFrontendLauncher.matchesWorkspaceReturn(true, thread, thread)) throw new AssertionError("Exact gateway return rejected");
        if (AgentFrontendLauncher.matchesWorkspaceReturn(false, thread, thread)) throw new AssertionError("Ordinary entry bypassed its guard");
        if (AgentFrontendLauncher.matchesWorkspaceReturn(true, thread, other)) throw new AssertionError("Different session returned");
        if (AgentFrontendLauncher.matchesWorkspaceReturn(true, null, thread)) throw new AssertionError("Missing lease accepted");
        if (AgentFrontendLauncher.matchesWorkspaceReturn(true, thread, null)) throw new AssertionError("Missing return accepted");
        if (AgentFrontendLauncher.matchesWorkspaceReturn(true, "bad", "bad")) throw new AssertionError("Invalid UUID accepted");
        System.out.println("AgentFrontendLauncherTest: 6 return-boundary checks passed");
    }
}
