package org.agentos.tests;

import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;

/** Actual native settings panels, theme switching, expansion and navigation. No model fixtures. */
public final class HarnessPanelTest extends UiAutomatorTestCase {
    private static final String PACKAGE="org.agentos.assistant";
    private UiObject text(String value){return new UiObject(new UiSelector().packageName(PACKAGE).text(value));}
    private UiObject contains(String value){return new UiObject(new UiSelector().packageName(PACKAGE).textContains(value));}
    private static final class Search extends UiObject {
        Search(){super(new UiSelector().packageName(PACKAGE).description("Search skills"));}
        void replace(String copy) {
            AccessibilityNodeInfo node=findAccessibilityNodeInfo(3000);
            Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,copy);
            assertNotNull(node);assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value));
        }
    }
    private void click(UiObject object)throws Exception {
        if(!object.exists())new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true)).scrollIntoView(object.getSelector());
        assertTrue("Button click unavailable: "+object.getSelector(),object.click());sleep(600);
    }
    private void capture(String name)throws Exception {
        assertTrue(getUiDevice().takeScreenshot(new File("/data/local/tmp/harness-panel-"+name+".png")));
    }
    private void options()throws Exception {
        click(new UiObject(new UiSelector().packageName(PACKAGE).description("技能选项")));
    }
    private int completeRows()throws Exception {
        android.graphics.Rect first=new UiObject(new UiSelector().packageName(PACKAGE).description("技能条目 aspnet-core")).getBounds();
        int count=0,limit=getUiDevice().getDisplayHeight()-(int)(48*android.content.res.Resources.getSystem().getDisplayMetrics().density);
        for(int i=0;i<100;i++) {
            UiObject row=new UiObject(new UiSelector().packageName(PACKAGE).descriptionStartsWith("Skill entry ").instance(i));
            if(!row.exists())break;
            android.graphics.Rect bounds=row.getBounds();
            if(bounds.top>=first.top&&bounds.bottom<=limit&&bounds.height()>=first.height())count++;
        }
        return count;
    }
    public void testNativePanels()throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        UiObject settings=new UiObject(new UiSelector().packageName(PACKAGE).description("助手设置"));
        assertTrue(settings.waitForExists(8000));click(settings);
        click(text("Context"));
        assertTrue(text("Harness · Current conversation").waitForExists(10000));
        assertTrue(contains("稳定前缀：").exists());
        capture("overview");
        click(new UiObject(new UiSelector().packageName(PACKAGE).description("Harness stable context")));
        assertTrue(contains("<taku_harness version=").waitForExists(5000));capture("background");
        click(new UiObject(new UiSelector().packageName(PACKAGE).description("Harness stable context")));
        new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true)).scrollIntoView(text("OS and execution environment").getSelector());
        capture("environment");
        getUiDevice().pressBack();sleep(700);
        assertTrue(text("Appearance").waitForExists(5000));
        click(text("Skills"));
        assertTrue(new Search().waitForExists(10000));
        assertTrue(text("ASP.NET Core").waitForExists(30000));
        int visible=completeRows();System.out.println("Complete visible skill rows: "+visible);
        assertTrue("Compact library must show at least 8 complete skill rows",visible>=8);
        assertFalse("Browse rows must not be install buttons",text("安装 aspnet-core").exists());
        capture("skills");capture("catalog");
        new Search().replace("gh-address-comments");sleep(600);
        assertTrue(text("Address code reviews").exists());assertFalse(text("ASP.NET Core").exists());capture("search");
        click(new UiObject(new UiSelector().packageName(PACKAGE).description("技能条目 gh-address-comments")));
        UiObject install=text("Install skill");
        long timeout=System.currentTimeMillis()+25000;
        while(install.exists()&&!install.isEnabled()&&System.currentTimeMillis()<timeout)sleep(200);
        capture("detail");
        if(install.exists()){assertTrue("Actual metadata must load before installation",install.isEnabled());click(install);}
        assertTrue(text("Recognized by runtime").waitForExists(60000));capture("installed-detail");
        click(text("Provenance"));assertTrue(contains("SHA-256 ").exists());
        getUiDevice().pressBack();sleep(600);assertTrue(new Search().exists());
        new Search().replace("no-such-skill-acceptance");sleep(400);
        assertTrue(contains("没有找到技能").exists());capture("empty");
        new Search().replace("");click(text("Installed"));new Search().replace("security-threat-model");sleep(500);
        click(new UiObject(new UiSelector().packageName(PACKAGE).description("技能条目 security-threat-model")));
        assertTrue(text("Recognized by runtime").exists());capture("community-skill");
        getUiDevice().pressBack();sleep(400);
        getUiDevice().pressBack();sleep(600);
        click(text("Appearance")); click(new UiObject(new UiSelector().text("Light")));
        click(text("Context"));
        assertTrue(text("Harness · Current conversation").waitForExists(10000));capture("light");
        getUiDevice().pressBack();sleep(600);click(text("Appearance")); click(new UiObject(new UiSelector().text("Dark")));click(new UiObject(new UiSelector().packageName(PACKAGE).description("返回对话")));
        assertTrue(new UiObject(new UiSelector().packageName(PACKAGE).description("Taku 对话输入框")).waitForExists(5000));
    }

    public void testSkillsAppearance()throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        click(new UiObject(new UiSelector().packageName(PACKAGE).description("助手设置")));
        click(text("Skills"));
        assertTrue(text("ASP.NET Core").waitForExists(30000));
        options();click(new UiObject(new UiSelector().text("分类 · 创作")));assertFalse(text("ASP.NET Core").exists());
        assertTrue(contains("figma").exists());capture("category");
        options();click(new UiObject(new UiSelector().text("分类 · 全部")));
        options();click(new UiObject(new UiSelector().text("Manage sources")));
        click(new UiObject(new UiSelector().text("添加公开仓库")));
        assertTrue(new UiObject(new UiSelector().packageName(PACKAGE).description("Skill community repository")).exists());capture("source-form");
        getUiDevice().pressBack();sleep(500);assertTrue(new Search().exists());
        getUiDevice().pressBack();sleep(500);click(text("Appearance")); click(new UiObject(new UiSelector().text("Light")));
        click(text("Skills"));assertTrue(text("ASP.NET Core").waitForExists(30000));capture("skills-light");
        getUiDevice().pressBack();sleep(500);click(text("Appearance")); click(new UiObject(new UiSelector().text("Dark")));click(new UiObject(new UiSelector().packageName(PACKAGE).description("返回对话")));
    }
}
