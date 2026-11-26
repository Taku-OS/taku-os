package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;

import android.app.*;
import android.content.Context;
import android.view.accessibility.*;

import java.io.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Reads the top WhatsApp window on the authorized execution display. */
final class DiningPollUi {
    record VerifiedReceipt(Receipt receipt, String address, String existingCardId) {}

    static <T> T read(DiningPollSendAutomation.Read<T> work) throws Exception {
        return DiningPollSendAutomation.read(work);
    }

    static AccessibilityNodeInfo root(UiAutomation ui, int display, String owner) throws Exception {
        IOException last = null;
        for (int i = 0; i < 5; i++)
            try {
                return rootOnce(ui, display, owner);
            } catch (IOException notReady) {
                last = notReady;
                Thread.sleep(80);
            }
        throw last;
    }

    private static AccessibilityNodeInfo rootOnce(UiAutomation ui, int display, String owner)
            throws Exception {
        List<AccessibilityWindowInfo> windows = ui.getWindowsOnAllDisplays().get(display);
        AccessibilityWindowInfo top = null;
        AccessibilityNodeInfo result = null;
        if (windows != null)
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo node = window.getRoot();
                if (node == null) continue;
                String pkg = value(node.getPackageName());
                if (pkg.equals("org.agentos.assistant")) continue;
                if (top == null || window.getLayer() > top.getLayer()) {
                    top = window;
                    result = node;
                }
            }
        if (result == null || !result.refresh() || !owner.equals(value(result.getPackageName())))
            throw new IOException("POLL_SOURCE_NOT_VISIBLE");
        return result;
    }

    static VerifiedReceipt receipt(State state, int display) throws Exception {
        return receipt(state, display, null);
    }

    static VerifiedReceipt receipt(State state, int display, LocalDate provenDate)
            throws Exception {
        return read(
                ui -> {
                    DiningPollReceipt.Receipt r = DiningPollReceipt.read(ui, display, provenDate);
                    String name = r.restaurant();
                    Integer year = r.year();
                    int month = r.month(), day = r.day();
                    LocalTime time = r.time();
                    int people = r.partySize();
                    ZonedDateTime target = state.meal().when();
                    if (!DiningPollReceipt.matchesDate(r, target))
                        throw new IOException("RECEIPT_TARGET_CHANGED");
                    Receipt receipt =
                            new Receipt(
                                    ReceiptStatus.CONFIRMED,
                                    state.booking().key(),
                                    state.booking().restaurant().id(),
                                    name,
                                    target,
                                    people,
                                    "",
                                    "",
                                    "",
                                    "",
                                    new Evidence(
                                            Source.OPENTABLE_RECEIPT_UI,
                                            UUID.randomUUID().toString(),
                                            Instant.now()));
                    String address = r.address();
                    String canonical =
                            normalize(name)
                                    + "\n"
                                    + normalize(address)
                                    + "\n"
                                    + target.toInstant().toEpochMilli()
                                    + "\n"
                                    + people;
                    String existing =
                            "dining.reservation."
                                    + HexFormat.of()
                                            .formatHex(
                                                    java.security.MessageDigest.getInstance(
                                                                    "SHA-256")
                                                            .digest(
                                                                    canonical.getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)))
                                            .substring(0, 24);
                    return new VerifiedReceipt(receipt, address, existing);
                });
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
                .replaceAll("[\\p{Z}\\s]+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    static String value(CharSequence value) {
        return value == null ? "" : value.toString().strip();
    }

    static ActivityManager.RunningTaskInfo visible(Context context) {
        for (ActivityManager.RunningTaskInfo task :
                context.getSystemService(ActivityManager.class).getRunningTasks(100)) {
            if (display(task) == 0 && task.topActivity != null) return task;
        }
        return null;
    }

    static int display(ActivityManager.RunningTaskInfo task) {
        try {
            return task.getClass().getField("displayId").getInt(task);
        } catch (ReflectiveOperationException unavailable) {
            return -1;
        }
    }
}
