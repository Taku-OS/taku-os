package org.agentos.surfaces;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry shared by native flow layout and host regression tests. Units are pixels. */
final class SurfaceLayout {
    record Row(int start,List<Integer> widths) { Row { widths=List.copyOf(widths); } }
    static List<Row> rows(List<Integer> wanted,int available,int gap) {
        if (available<1 || gap<0) throw new IllegalArgumentException("Invalid flow bounds");
        List<Row> result=new ArrayList<>();
        for (int start=0;start<wanted.size();) {
            int end=start,total=0;
            while (end<wanted.size()) {
                int next=Math.max(1,Math.min(available,wanted.get(end)));
                if (end>start && total+gap+next>available) break;
                total+=(end==start?0:gap)+next; end++;
            }
            int count=end-start,remaining=available-total;
            List<Integer> widths=new ArrayList<>();
            for (int i=start;i<end;i++) widths.add(Math.max(1,Math.min(available,wanted.get(i)))+remaining/count+(i-start<remaining%count?1:0));
            result.add(new Row(start,widths)); start=end;
        }
        return List.copyOf(result);
    }
    record Panels(int bodyHeight,int footerHeight) {}
    static Panels panels(int displayHeight,int safeHeight,int headerHeight,int padding,int footerHeight,int minimumBodyHeight) {
        int target=Math.max(Math.round(displayHeight*.72f),headerHeight+padding+footerHeight+minimumBodyHeight);
        int usable=Math.max(0,Math.min(target,safeHeight)-headerHeight-padding);
        // If IME/large text leaves less room than the buttons themselves need, retain
        // a readable body viewport and let the footer scroll rather than clip a label.
        int bodyReserve=Math.min(minimumBodyHeight/2,usable/3);
        int footer=Math.min(footerHeight,Math.max(0,usable-bodyReserve));
        return new Panels(usable-footer,footer);
    }
    static boolean stackField(float labelWidth,float valueWidth,int available,int gap,boolean multiline,float fontScale) {
        int usable=Math.max(1,available-gap),labelColumn=usable/3;
        return multiline || fontScale>=1.3f || labelWidth>labelColumn || valueWidth>usable-labelColumn;
    }
    static boolean longFact(float valueWidth,int available,boolean multiline) {
        return multiline || valueWidth>available*.7f;
    }
}
