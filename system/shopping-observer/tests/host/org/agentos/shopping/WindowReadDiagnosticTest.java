// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.util.ArrayList;
import java.util.List;

public final class WindowReadDiagnosticTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        check(WindowReadDiagnostic.packageKind("com.zhiliaoapp.musically")==WindowReadDiagnostic.PackageKind.TIKTOK,"verified TikTok package is fixed category");
        check(WindowReadDiagnostic.packageKind("com.ss.android.ugc.trill")==WindowReadDiagnostic.PackageKind.TIKTOK,"second TikTok package is fixed category");
        check(WindowReadDiagnostic.packageKind("org.agentos.shopping")==WindowReadDiagnostic.PackageKind.SHOPPING,"own package is fixed category");
        check(WindowReadDiagnostic.packageKind("org.agentos.surfaces")==WindowReadDiagnostic.PackageKind.SURFACES,"system hint package is fixed category");
        check(WindowReadDiagnostic.packageKind("com.android.systemui")==WindowReadDiagnostic.PackageKind.SYSTEM_UI,"system UI package is fixed category");
        check(WindowReadDiagnostic.packageKind("personal.private.application")==WindowReadDiagnostic.PackageKind.OTHER,"arbitrary packages are not retained");
        check(WindowReadDiagnostic.packageKind(null)==WindowReadDiagnostic.PackageKind.UNKNOWN
            &&WindowReadDiagnostic.packageKind("")==WindowReadDiagnostic.PackageKind.UNKNOWN,"unreadable package is explicit unknown");
        var app=new WindowReadDiagnostic.Window(1258,1,2,true,true,false,false,0,0,1080,2424,
            WindowReadDiagnostic.PackageKind.TIKTOK);
        var overlay=new WindowReadDiagnostic.Window(1264,4,3,false,false,true,false,40,500,1040,750,
            WindowReadDiagnostic.PackageKind.SHOPPING);
        String evidence=WindowReadDiagnostic.render("app_not_focused",List.of(app,overlay),2);
        check(evidence.contains("\"schema\":\"taku.window-read.v1\"")&&evidence.contains("\"reason\":\"app_not_focused\""),"stable schema and exact fixed read reason present");
        check(evidence.contains("\"defaultWindowCount\":2,\"truncated\":false"),"complete list has exact count");
        check(evidence.contains("\"id\":1258,\"type\":1,\"layer\":2,\"active\":true,\"focused\":true,\"accessibilityFocused\":false,\"pip\":false,\"bounds\":[0,0,1080,2424],\"package\":\"TIKTOK\""),"one immutable window's flags and geometry remain together");
        check(evidence.contains("\"id\":1264,\"type\":4,\"layer\":3,\"active\":false,\"focused\":false,\"accessibilityFocused\":true"),"overlay flags are independently preserved");
        check(!evidence.contains("com.")&&!evidence.contains("org.")&&!evidence.contains("title")&&!evidence.contains("text"),"diagnostics expose no raw packages, title or node text");
        String unknown=WindowReadDiagnostic.render("private screenshot contents",List.of(app),1);
        check(unknown.contains("\"reason\":\"unknown\"")&&!unknown.contains("private"),"unknown reason strings cannot leak arbitrary text");
        check(WindowReadDiagnostic.render(null,null,0).endsWith("\"windows\":[]}"),"empty and unknown snapshots remain valid bounded JSON shape");
        var extreme=new WindowReadDiagnostic.Window(Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,
            false,false,false,false,Integer.MIN_VALUE,-17000,Integer.MAX_VALUE,17000,null);
        String bounds=WindowReadDiagnostic.render("snapshot_unavailable",List.of(extreme),Integer.MAX_VALUE);
        check(bounds.contains("\"id\":-1,\"type\":100,\"layer\":1000000"),"scalar values are bounded");
        check(bounds.contains("\"bounds\":[-16384,-16384,16384,16384]")&&bounds.contains("\"package\":\"UNKNOWN\""),"coordinates and missing categories are bounded");
        List<WindowReadDiagnostic.Window> many=new ArrayList<>();
        for(int i=0;i<100;i++)many.add(new WindowReadDiagnostic.Window(i,1,i,true,true,false,false,0,0,1080,2424,WindowReadDiagnostic.PackageKind.OTHER));
        String limited=WindowReadDiagnostic.render("live_room",many,100);
        check(limited.split("\"id\":",-1).length-1==16&&limited.contains("\"truncated\":true"),"at most sixteen window records can be retained");
        check(!limited.contains("\"id\":16,")&&limited.length()<8192,"bounded snapshot cannot grow with the input window count");
        check(WindowReadDiagnostic.render("live_room",List.of(),-10).contains("\"defaultWindowCount\":0"),"negative total count cannot escape diagnostic domain");
        System.out.println(assertions+" window read diagnostic assertions passed");
    }
}
