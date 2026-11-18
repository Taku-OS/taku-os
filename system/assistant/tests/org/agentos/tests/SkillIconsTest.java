package org.agentos.tests;

import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;

/** Real package icons on catalog, installed rows and details; no generated icon fixtures. */
public final class SkillIconsTest extends UiAutomatorTestCase {
    private static final String PACKAGE="org.agentos.assistant";
    private UiObject text(String copy){return new UiObject(new UiSelector().packageName(PACKAGE).text(copy));}
    private UiObject description(String copy){return new UiObject(new UiSelector().packageName(PACKAGE).description(copy));}
    private void click(UiObject object)throws Exception {
        if(!object.exists())new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true)).scrollIntoView(object.getSelector());
        assertTrue(object.click());sleep(400);
    }
    private static final class Search extends UiObject {
        Search(){super(new UiSelector().packageName(PACKAGE).description("Search skills"));}
        void replace(String copy) {
            Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,copy);
            AccessibilityNodeInfo node=findAccessibilityNodeInfo(3000);assertNotNull(node);assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value));
        }
    }
    private void capture(String name)throws Exception {
        assertTrue(getUiDevice().takeScreenshot(new File("/data/local/tmp/skill-icons-"+name+".png")));
    }
    public void testPackageIcons()throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        if(!text("Appearance").exists())click(description("助手设置"));click(text("Skills"));
        assertTrue(description("原包图标 aspnet-core").waitForExists(30000));
        assertTrue(description("原包图标 cloudflare-deploy").exists());
        assertFalse(description("原包图标 chatgpt-apps").exists());capture("catalog");
        click(description("技能条目 aspnet-core"));
        UiObject install=text("Install skill");long end=System.currentTimeMillis()+20000;
        while(install.exists()&&!install.isEnabled()&&System.currentTimeMillis()<end)sleep(100);
        assertTrue(description("原包图标 aspnet-core").exists());capture("detail");
        getUiDevice().pressBack();sleep(400);
        click(text("Installed"));new Search().replace("gh-fix-ci");sleep(500);
        assertTrue(description("原包图标 gh-fix-ci").exists());capture("installed");
        getUiDevice().pressBack();sleep(400);click(text("Appearance"));click(new UiObject(new UiSelector().text("Light")));
        click(text("Skills"));assertTrue(description("原包图标 aspnet-core").waitForExists(30000));capture("light");
        getUiDevice().pressBack();sleep(400);click(text("Appearance"));click(new UiObject(new UiSelector().text("Dark")));getUiDevice().pressBack();
    }
}
