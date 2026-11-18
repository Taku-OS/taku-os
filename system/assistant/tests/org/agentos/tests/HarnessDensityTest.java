package org.agentos.tests;

import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.File;

/** Actual managed context, disclosure rows, compact header and themes on the dedicated AVD. */
public final class HarnessDensityTest extends UiAutomatorTestCase {
    private static final String PACKAGE = "org.agentos.assistant";
    private UiObject text(String copy) { return new UiObject(new UiSelector().packageName(PACKAGE).text(copy)); }
    private UiObject description(String copy) { return new UiObject(new UiSelector().packageName(PACKAGE).description(copy)); }
    private void click(UiObject object) throws Exception {
        if (!object.exists()) new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true)).scrollIntoView(object.getSelector());
        assertTrue(object.click()); sleep(400);
    }
    private void capture(String name) throws Exception {
        assertTrue(getUiDevice().takeScreenshot(new File("/data/local/tmp/harness-compact-" + name + ".png")));
    }
    private int visibleModules() throws Exception {
        int count = 0;
        int bottom = getUiDevice().getDisplayHeight() - (int)(48 * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        for (int i = 0; i < 20; i++) {
            UiObject row = new UiObject(new UiSelector().packageName(PACKAGE).descriptionStartsWith("Harness module ").instance(i));
            if (!row.exists()) break;
            android.graphics.Rect bounds = row.getBounds();
            if (bounds.top > 0 && bounds.bottom <= bottom && bounds.height() > 120) count++;
        }
        return count;
    }
    public void testCompactInspection() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        assertTrue(description("助手设置").waitForExists(8000)); click(description("助手设置"));
        click(text("Context"));
        assertTrue(text("背景模块 · 7").waitForExists(10000));
        assertFalse(text("Back to settings").exists());
        int count = visibleModules();
        System.out.println("Complete visible Harness modules: " + count);
        assertEquals("Overview should expose all seven module identities without scrolling", 7, count);
        capture("overview");
        click(description("Harness 模块 identity"));
        assertTrue(new UiObject(new UiSelector().packageName(PACKAGE).textContains("来源：assistant-apk")).exists());
        capture("module"); click(description("Harness 模块 identity"));
        click(description("Harness stable context"));
        assertTrue(new UiObject(new UiSelector().packageName(PACKAGE).textContains("<taku_harness version=")).exists());
        capture("background"); click(description("Harness stable context"));
        click(description("Harness environment details"));
        assertTrue(new UiObject(new UiSelector().packageName(PACKAGE).textContains("runtimeUid")).exists());
        capture("environment"); click(description("Harness environment details"));
        UiObject receipt = new UiObject(new UiSelector().packageName(PACKAGE).descriptionStartsWith("Harness receipt ").instance(0));
        click(receipt); assertTrue(new UiObject(new UiSelector().packageName(PACKAGE).textContains("\"sequence\"")).exists());
        capture("receipt"); click(receipt);
        click(description("上下文选项")); click(new UiObject(new UiSelector().text("Reload Harness")));
        assertTrue(text("背景模块 · 7").waitForExists(10000));
        click(description("返回 Agent 设置"));
        assertTrue(description("助手设置").exists()); click(text("Appearance")); click(new UiObject(new UiSelector().text("Light")));
        click(text("Context")); assertTrue(text("背景模块 · 7").waitForExists(10000));
        capture("light"); getUiDevice().pressBack(); sleep(400);
        click(text("Appearance")); click(new UiObject(new UiSelector().text("Dark"))); click(new UiObject(new UiSelector().packageName(PACKAGE).description("返回对话")));
        assertTrue(description("Taku 对话输入框").waitForExists(5000));
    }
}
