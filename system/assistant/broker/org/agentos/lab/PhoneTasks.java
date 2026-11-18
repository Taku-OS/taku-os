package org.agentos.lab;

import java.util.*;
import java.util.regex.*;

/** Android tasks on every display, as `am stack list` prints them. */
interface PhoneTasks {
    /** id: the task number; packageName: the App the task was started for (its root activity). */
    record Task(int id, String packageName, int displayId) {}

    List<Task> list() throws Exception;

    /**
     * An id Android no longer has is a no-op: removeTask only logs it and `am` still exits 0
     * (android16-release).
     */
    void remove(int id) throws Exception;

    PhoneTasks DEVICE =
            new PhoneTasks() {
                public List<Task> list() throws Exception {
                    return parse(TaskBroker.command("/system/bin/am", "stack", "list"));
                }

                public void remove(int id) throws Exception {
                    TaskBroker.command("/system/bin/am", "stack", "remove", Integer.toString(id));
                }
            };

    Pattern ROOT = Pattern.compile("^RootTask id=\\d+ .*\\bdisplayId=(-?\\d+)");
    Pattern CHILD = Pattern.compile("^\\s+taskId=(\\d+): (\\S+)");

    /**
     * Each display's tasks top to bottom (Android lists root tasks that way). Tasks without an App
     * are left out: Android names them "unknown" (split-screen containers on the main screen).
     */
    static List<Task> parse(String text) {
        List<Task> out = new ArrayList<>();
        int display = -1;
        for (String line : text.split("\n")) {
            Matcher root = ROOT.matcher(line);
            if (root.find()) {
                display = Integer.parseInt(root.group(1));
                continue;
            }
            Matcher child = CHILD.matcher(line);
            if (!child.find() || child.group(2).equals("unknown")) continue;
            String name = child.group(2);
            int slash = name.indexOf('/');
            out.add(
                    new Task(
                            Integer.parseInt(child.group(1)),
                            slash < 0 ? name : name.substring(0, slash),
                            display));
        }
        return out;
    }
}
