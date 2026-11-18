package org.agentos.surfaces;

import java.util.List;

/** Fit, wrap, and keyboard regressions for content-measured structured cards. */
public final class SurfaceLayoutTest {
    private static int assertions;
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Layout check "+assertions); }
    public static void main(String[] args) {
        var normal=SurfaceLayout.rows(List.of(140,64,64),320,16);
        check(normal.size()==1 && normal.get(0).widths().size()==3);
        var narrow=SurfaceLayout.rows(List.of(140,64,64),240,16);
        check(narrow.size()==2 && narrow.get(0).widths().size()==2 && narrow.get(1).start()==2);
        var largeFont=SurfaceLayout.rows(List.of(280,128,128),320,16);
        check(largeFont.size()==2 && largeFont.get(0).widths().equals(List.of(320)));
        var longLabel=SurfaceLayout.rows(List.of(900,64),240,16);
        check(longLabel.size()==2 && longLabel.get(0).widths().equals(List.of(240)));
        check(SurfaceLayout.rows(List.of(1,1),1,16).size()==2);
        check(SurfaceLayout.rows(List.of(),320,16).isEmpty());
        for (int available:new int[]{1,90,200,240,320,640}) {
            for (var row:SurfaceLayout.rows(List.of(140,64,64,900,80,220),available,16)) {
                check(row.widths().stream().mapToInt(Integer::intValue).sum()+16*(row.widths().size()-1)==available);
                check(row.widths().stream().allMatch(width -> width>0 && width<=available));
            }
        }
        check(!SurfaceLayout.stackField(30,80,320,16,false,1));
        check(SurfaceLayout.stackField(30,600,320,16,false,1)); // Long address gets the full width.
        check(SurfaceLayout.stackField(80,100,160,16,false,1));
        check(SurfaceLayout.stackField(30,80,320,16,true,1));
        check(SurfaceLayout.stackField(30,80,320,16,false,1.3f));
        check(!SurfaceLayout.longFact(140,320,false)); // A date remains a short fact.
        check(SurfaceLayout.longFact(700,320,false)); // A sentence uses body typography.
        check(SurfaceLayout.longFact(140,320,true));
        var normalPanels=SurfaceLayout.panels(800,760,44,28,120,96);
        check(normalPanels.bodyHeight()==384 && normalPanels.footerHeight()==120);
        var keyboard=SurfaceLayout.panels(800,280,44,28,120,96);
        check(keyboard.bodyHeight()==88 && keyboard.footerHeight()==120);
        var largeKeyboard=SurfaceLayout.panels(800,280,44,28,160,96);
        check(largeKeyboard.bodyHeight()==48 && largeKeyboard.footerHeight()==160);
        var extreme=SurfaceLayout.panels(800,200,44,28,220,96);
        check(extreme.bodyHeight()>0 && extreme.footerHeight()<220); // Scrolling, never clipping/shrinking actions.
        check(SurfaceLayout.panels(800,50,44,28,120,96).equals(new SurfaceLayout.Panels(0,0)));
        for (int safe:new int[]{100,200,280,400,760}) for (int footer:new int[]{0,48,120,160,320}) {
            var panel=SurfaceLayout.panels(800,safe,44,28,footer,96);
            check(panel.bodyHeight()>=0 && panel.footerHeight()>=0 && panel.footerHeight()<=footer);
            check(panel.bodyHeight()+panel.footerHeight()+72<=safe);
        }
        System.out.println(assertions+" surface layout assertions passed");
    }
}
