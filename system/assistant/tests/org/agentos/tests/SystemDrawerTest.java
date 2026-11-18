package org.agentos.tests;

import android.graphics.Rect;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import org.json.JSONObject;

import java.io.*;

/** Actual system-window/native-thread flow; this does not simulate a model response. */
public final class SystemDrawerTest extends UiAutomatorTestCase {
    private static final String PACKAGE = "org.agentos.assistant";

    private static final class Node extends UiObject {
        Node(UiSelector selector) {
            super(selector.packageName(PACKAGE));
        }

        boolean action(int operation) {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(2500);
            return node != null && node.performAction(operation);
        }

        boolean replace(String text) {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(2500);
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return node != null
                    && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        }
    }

    private Node description(String value) {
        return new Node(new UiSelector().description(value));
    }

    private Node text(String value) {
        return new Node(new UiSelector().text(value));
    }

    private Node contains(String value) {
        return new Node(new UiSelector().textContains(value));
    }

    private void click(Node node) throws Exception {
        ensureVisible(node);
        assertTrue(node.action(AccessibilityNodeInfo.ACTION_CLICK));
        sleep(400);
    }

    private void command(String... args) throws Exception {
        assertEquals(0, Runtime.getRuntime().exec(args).waitFor());
    }

    private void ensureVisible(Node node) throws Exception {
        if (!node.exists())
            new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true))
                    .scrollIntoView(node.getSelector());
    }

    private void capture(String name) throws Exception {
        sleep(400);
        command("/system/bin/screencap", "-p", "/data/local/tmp/drawer-" + name + ".png");
    }

    private void open() throws Exception {
        command("/system/bin/am", "start", "-n", PACKAGE + "/.PanelActivity");
        assertTrue(description("向下拖动收起助手").waitForExists(6000));
        sleep(1000);
    }

    private void rename(String value) throws Exception {
        click(description("助手设置"));
        ensureVisible(description("Edit conversation title"));
        assertTrue(description("Edit conversation title").replace(value));
        click(text("保存标题"));
        sleep(1000);
        click(text("返回对话"));
        assertTrue(text(value).waitForExists(5000));
    }

    private String read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private JSONObject accountState() throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("taku-agent-account"));
            socket.setSoTimeout(3000);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            int n;
            while ((n = socket.getInputStream().read(b)) != -1) {
                bytes.write(b, 0, n);
                if (bytes.size() > 32768) throw new Exception();
            }
            return new JSONObject(bytes.toString("UTF-8"));
        } catch (Exception unavailable) {
            throw new AssertionError("Account endpoint unreadable");
        }
    }

    public void testAccountLogin() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
        command("/system/bin/am", "start", "-n", "com.android.settings/.Settings");
        open();
        click(description("助手设置"));
        click(text("连接账户"));
        assertTrue(text("Sign in with ChatGPT").waitForExists(10000));
        click(text("Sign in with ChatGPT"));
        long deadline = System.nanoTime() + 45_000_000_000L;
        JSONObject account;
        do {
            sleep(300);
            account = accountState();
        } while (!account.optString("phase").equals("pending")
                && !account.optString("phase").equals("failed")
                && System.nanoTime() < deadline);
        boolean deviceChallenge = account.optString("phase").equals("pending");
        if (!deviceChallenge) {
            assertEquals("failed", account.optString("phase"));
            assertTrue(contains("暂时无法连接授权服务").waitForExists(6000));
            capture("account-error");
            click(text("Authorize in browser"));
            deadline = System.nanoTime() + 35_000_000_000L;
            do {
                sleep(300);
                account = accountState();
            } while (!account.optString("phase").equals("pending")
                    && !account.optString("phase").equals("failed")
                    && System.nanoTime() < deadline);
        }
        boolean browserOrDeviceChallenge = account.optString("phase").equals("pending");
        if (browserOrDeviceChallenge) {
            assertFalse(
                    "Native authorization URL must be present", account.optString("url").isEmpty());
            assertTrue(text("Cancel this sign-in").waitForExists(6000));
            java.lang.Process dump =
                    Runtime.getRuntime()
                            .exec(new String[] {"/system/bin/dumpsys", "window", "windows"});
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = dump.getInputStream().read(b)) != -1) data.write(b, 0, n);
            assertTrue(
                    "Pending authorization must be protected from screenshots",
                    data.toString("UTF-8").contains("SECURE"));
            click(text("Cancel this sign-in"));
            assertTrue(text("Sign in with ChatGPT").waitForExists(6000));
            account = accountState();
            assertTrue(account.optString("url").isEmpty() && account.optString("code").isEmpty());
            capture("account-cancelled");
        } else {
            assertEquals("failed", account.optString("phase"));
            capture("account-error");
        }
        assertFalse(
                "Test never completes OAuth or invents a connected account",
                account.optBoolean("connected"));
        try (FileOutputStream output =
                new FileOutputStream("/data/local/tmp/drawer-account-acceptance.json")) {
            output.write(
                    new JSONObject()
                            .put("nativeLoginStartInvoked", true)
                            .put("deviceChallengeReceived", deviceChallenge)
                            .put("anyNativeChallengeReceived", browserOrDeviceChallenge)
                            .put("oauthCompleted", false)
                            .put("modelTurnVerified", false)
                            .toString()
                            .getBytes("UTF-8"));
        }
        click(text("Back to settings"));
        click(text("返回对话"));
        getUiDevice().pressBack();
    }

    public void testSystemDrawer() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
        command("/system/bin/am", "start", "-n", "com.android.settings/.Settings");
        open();
        click(description("助手设置"));
        click(text("Dark"));
        capture("settings-dark");
        click(text("连接账户"));
        assertTrue(text("Sign in with ChatGPT").waitForExists(10000));
        capture("account-idle");
        click(text("Back to settings"));
        if (text("Include main-screen screenshot when sending").isChecked()) click(text("Include main-screen screenshot when sending"));
        click(text("返回对话"));
        Rect handle = description("向下拖动收起助手").getBounds();
        assertTrue(
                "Drawer must expose the original application above it",
                handle.top > getUiDevice().getDisplayHeight() * .06);
        click(description("切换会话"));
        click(text("New conversation"));
        sleep(1000);
        String first = "系统抽屉验收 " + System.currentTimeMillis();
        rename(first);
        capture("dark");
        assertFalse(text("Taku").exists());
        Node input = description("Taku 对话输入框");
        assertTrue(input.replace("这是一条未发送的草稿"));
        click(description("切换会话"));
        click(text("New conversation"));
        sleep(1000);
        String second = "第二个原生会话 " + System.currentTimeMillis();
        rename(second);
        assertFalse(input.getText().contains("未发送的草稿"));
        click(description("切换会话"));
        assertTrue(text(first).waitForExists(5000));
        assertTrue(text(second).exists());
        capture("sessions");
        click(text(first));
        sleep(800);
        assertEquals("这是一条未发送的草稿", input.getText());
        click(description("助手设置"));
        click(text("Light"));
        capture("settings-light");
        click(text("返回对话"));
        assertEquals("这是一条未发送的草稿", input.getText());
        capture("light");
        click(input);
        sleep(900);
        capture("keyboard");
        assertTrue(
                "Input must remain above the keyboard",
                input.getBounds().bottom < getUiDevice().getDisplayHeight() * .85);
        getUiDevice().pressBack();
        sleep(400);
        assertTrue(input.replace("读取当前媒体音量"));
        click(description("发送消息"));
        assertTrue(
                "Device login absence must be real and visible",
                contains("此设备尚未连接账户").waitForExists(15000));
        capture("needs-account");
        handle = description("向下拖动收起助手").getBounds();
        getUiDevice()
                .swipe(
                        handle.centerX(),
                        handle.centerY(),
                        handle.centerX(),
                        handle.centerY() + 500,
                        12);
        sleep(700);
        assertFalse(description("向下拖动收起助手").exists());
        assertTrue(
                "Dismiss must restore original Settings",
                new UiObject(new UiSelector().packageName("com.android.settings")).exists());
        capture("restored-app");
        open();
        assertTrue(text(first).waitForExists(5000));
        click(description("助手设置"));
        click(text("Dark"));
        click(text("Include main-screen screenshot when sending"));
        click(text("返回对话"));
        getUiDevice().pressBack();
        sleep(500);
        open();
        sleep(1000);
        assertTrue(description("Taku 对话输入框").replace("截图上下文验收"));
        click(description("发送消息"));
        sleep(2500);
        String root = "/data/user/0/org.agentos.assistant/files/runtime/";
        String latest = read(new File(root, "latest.id")).trim();
        JSONObject request =
                new JSONObject(read(new File(root + "tasks/" + latest, "request.json")));
        assertEquals("截图上下文验收", request.getString("prompt"));
        assertTrue(request.optBoolean("includeScreenshot"));
        assertTrue(request.getString("foreground").startsWith("com.android.settings/"));
        File image = new File(request.getString("screenshot"));
        assertTrue("Must use Android invocation capture", image.isFile() && image.length() > 2000);
        command("/system/bin/cp", image.getPath(), "/data/local/tmp/drawer-invocation-context.png");
        click(description("助手设置"));
        click(text("Include main-screen screenshot when sending"));
        click(text("返回对话"));
    }
}
