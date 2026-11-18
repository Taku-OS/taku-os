package org.agentos.surfaces;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.text.Layout;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Independent native capsule and expanded card; no knowledge of content producers. */
final class SurfaceWindow implements SurfaceRenderer {
    private final Context context; private final WindowManager manager; private final Listener listener; private final SurfaceStyle style;
    private View root; private SurfaceStore.Key active; private List<SurfaceStore.Entry> entries=List.of();
    private boolean expanded; private long activeRevision;
    private final SurfacePositionPrefs positionPrefs;
    private SurfacePosition.Anchor anchor;
    private WindowManager.LayoutParams layout;
    private WindowInsets insets;
    private boolean bottom,dragging;
    private SurfacePosition.Point dragStart;
    SurfaceWindow(Context service,Listener listener) {
        Display display=service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        context=service.createWindowContext(display,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        manager=context.getSystemService(WindowManager.class); this.listener=listener; style=new SurfaceStyle(context);
        positionPrefs=new SurfacePositionPrefs(context); anchor=positionPrefs.read();
    }
    @Override public void render(List<SurfaceStore.Entry> value,SurfaceStore.Key preferred) {
        entries=value;
        SurfaceStore.Key requested=preferred==null?active:preferred;
        SurfaceStore.Entry entry=value.stream().filter(e -> e.key.equals(requested)).findFirst().orElse(value.get(value.size()-1));
        if (!entry.key.equals(active) || entry.spec.revision()!=activeRevision)
            expanded=entry.spec.initialState().equals("expanded");
        active=entry.key; activeRevision=entry.spec.revision();
        hide();
        bottom=entry.spec.placement().equals("bottom");
        insets=manager.getCurrentWindowMetrics().getWindowInsets();
        LinearLayout card=expanded?expanded(entry):capsule(entry); root=card;
        card.setElevation(style.dp(expanded?16:10)); card.setClipToOutline(true);
        int width=Math.min(style.dp(expanded?SurfaceStyle.CARD_WIDTH:SurfaceStyle.CAPSULE_WIDTH),safeArea(false).width());
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(width,-2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0); // We clamp against system bars, cutout and IME in one coordinate space.
        params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        params.setTitle("Taku System Surface");
        layout=params;
        card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(safeArea(true).height(),View.MeasureSpec.AT_MOST));
        SurfacePosition.Point point=SurfacePosition.place(anchor,safeArea(false),safeArea(true),width,card.getMeasuredHeight(),bottom);
        params.x=point.x(); params.y=point.y();
        card.setOnApplyWindowInsetsListener((view,valueInsets) -> {
            // View insets are clipped to this small moving window; metrics describe the whole display.
            if (view==root) { insets=manager.getCurrentWindowMetrics().getWindowInsets(); view.requestLayout(); updatePosition(); }
            return valueInsets;
        });
        card.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if (v==root) updatePosition(); });
        try { manager.addView(root,params); } catch (RuntimeException error) { root=null; throw error; }
    }
    private LinearLayout capsule(SurfaceStore.Entry entry) {
        LinearLayout pill=dragHandle(); pill.setPadding(style.dp(12),0,style.dp(4),0);
        pill.setMinimumHeight(style.dp(SurfaceStyle.CAPSULE_HEIGHT)); pill.setBackground(style.material(28)); pill.setClickable(true); pill.setFocusable(true);
        boolean direct=!entry.selected && !entry.spec.tapActionId().isEmpty();
        String actionLabel=entry.spec.actions().stream().filter(a -> a.id().equals(entry.spec.tapActionId())).map(SurfaceSpec.Action::label).findFirst().orElse(context.getString(R.string.expand));
        pill.setContentDescription(context.getString(R.string.capsule_help,entry.spec.title(),direct?actionLabel:context.getString(R.string.expand)));
        pill.setOnClickListener(v -> {
            if (direct) listener.onAction(entry.key,entry.spec.revision(),entry.spec.tapActionId()); else expand();
        });
        pill.addView(brand(),new LinearLayout.LayoutParams(style.dp(28),style.dp(28)));
        TextView title=style.text(entry.spec.title(),14,SurfaceStyle.TEXT,true); title.setMaxLines(1);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1); titleParams.setMarginStart(style.dp(10)); titleParams.setMarginEnd(style.dp(8)); pill.addView(title,titleParams);
        TextView badge=style.text(entries.size()>1?Integer.toString(entries.size()):kind(entry),10,SurfaceStyle.ORANGE,true);
        badge.setGravity(Gravity.CENTER); badge.setPadding(style.dp(8),0,style.dp(8),0);
        badge.setBackground(style.shape(0xff37281d,12,0)); pill.addView(badge,new LinearLayout.LayoutParams(-2,style.dp(24)));
        ImageButton open=style.icon(R.drawable.surface_expand,context.getString(R.string.expand_surface)); open.setOnClickListener(v -> expand()); pill.addView(open,controlSize()); return pill;
    }
    private LinearLayout expanded(SurfaceStore.Entry entry) {
        LinearLayout card=new SurfaceCard(context,() -> manager.getCurrentWindowMetrics().getBounds().height(),() -> safeArea(true).height(),style.dp(96));
        card.setPadding(style.dp(SurfaceStyle.CARD_INSET),style.dp(8),style.dp(SurfaceStyle.CARD_INSET),style.dp(20)); card.setBackground(style.material(28));
        LinearLayout header=dragHandle(); header.setContentDescription(context.getString(R.string.drag_help)); header.addView(brand(),new LinearLayout.LayoutParams(style.dp(24),style.dp(24)));
        TextView source=style.text("taku",14,SurfaceStyle.TEXT,true); source.setPadding(style.dp(8),0,0,0); header.addView(source,new LinearLayout.LayoutParams(0,-2,1));
        if (entries.size()>1) {
            Button next=style.action((entries.indexOf(entry)+1)+"/"+entries.size(),false);
            next.setContentDescription(context.getString(R.string.next_item,entries.size())); next.setTextSize(11); next.setPadding(0,0,0,0);
            next.setOnClickListener(v -> { active=entries.get((entries.indexOf(entry)+1)%entries.size()).key; activeRevision=0; redraw(); }); header.addView(next,controlSize());
        }
        ImageButton shrink=style.icon(R.drawable.surface_collapse,context.getString(R.string.collapse_surface)); shrink.setOnClickListener(v -> { expanded=false; redraw(); }); header.addView(shrink,controlSize());
        ImageButton close=style.icon(R.drawable.surface_close,context.getString(R.string.close_surface)); close.setOnClickListener(v -> listener.onDismiss(entry.key)); header.addView(close,controlSize()); card.addView(header);
        boolean structured=entry.spec.kind().equals("choice") && !entry.spec.blocks().isEmpty();
        LinearLayout body=column(); TextView title=style.readableText(entry.spec.title(),structured?22:20,SurfaceStyle.TEXT,true);
        body.addView(title,withTopSpace(8));
        if (!entry.spec.summary().isBlank()) {
            TextView summary=style.readableText(entry.spec.summary(),14,SurfaceStyle.MUTED,false);
            body.addView(summary,withTopSpace(8));
        }
        LinearLayout blocks=column();
        List<SurfaceSpec.Block> content=entry.spec.blocks();
        for (int i=0;i<content.size();i++) {
            SurfaceSpec.Block block=content.get(i);
            if (block.type().equals("fact") && shortFact(block)) {
                LinearLayout group=column(); group.addView(divider(),new LinearLayout.LayoutParams(-1,style.dp(1)));
                SurfaceFlow facts=new SurfaceFlow(context,style.dp(16),style.dp(64));
                facts.setPadding(0,style.dp(16),0,style.dp(16));
                do {
                    SurfaceSpec.Block fact=content.get(i);
                    LinearLayout cell=column();
                    cell.addView(style.readableText(fact.label(),12,SurfaceStyle.MUTED,false));
                    TextView value=style.readableText(fact.text(),18,SurfaceStyle.TEXT,true);
                    value.setFontFeatureSettings("kern, tnum"); cell.addView(value,withTopSpace(6));
                    facts.addView(cell);
                    i++;
                } while (i<content.size() && content.get(i).type().equals("fact") && shortFact(content.get(i)));
                i--;
                group.addView(facts,new LinearLayout.LayoutParams(-1,-2)); group.addView(divider(),new LinearLayout.LayoutParams(-1,style.dp(1)));
                blocks.addView(group,withTopSpace(blocks.getChildCount()==0?0:12));
            } else if (block.type().equals("notice")) {
                LinearLayout notice=column(); notice.setPadding(style.dp(14),style.dp(14),style.dp(14),style.dp(14));
                notice.setBackground(style.shape(0xff382b20,16,0xff61452f));
                notice.addView(style.readableText(block.label(),13,0xffffae79,true));
                notice.addView(style.readableText(block.text(),14,SurfaceStyle.TEXT,false),withTopSpace(6));
                blocks.addView(notice,withTopSpace(blocks.getChildCount()==0?0:16));
            } else if (block.type().equals("field") || block.type().equals("fact")) {
                blocks.addView(new SurfaceField(style,block.label(),block.text(),block.type().equals("fact")),new LinearLayout.LayoutParams(-1,-2));
            } else {
                TextView text=style.readableText(block.text(),13,SurfaceStyle.TEXT,false); text.setPadding(0,style.dp(6),0,style.dp(6)); blocks.addView(text);
            }
        }
        if (blocks.getChildCount()>0) body.addView(blocks,withTopSpace(12));
        if (entry.spec.artifact()!=null) {
            TextView artifact=style.readableText(entry.spec.artifact().title(),13,SurfaceStyle.TEXT,true);
            body.addView(artifact,withTopSpace(12));
        }
        LinearLayout actions=column();
        if (entry.selected) {
            TextView selected=style.text(entry.spec.kind().equals("choice")?context.getString(R.string.selected_full):context.getString(R.string.responded_full),12,SurfaceStyle.ORANGE,true); selected.setPadding(0,style.dp(8),0,style.dp(4)); body.addView(selected);
        } else if (!entry.spec.actions().isEmpty()) {
            SurfaceFlow secondary=new SurfaceFlow(context,style.dp(8),style.dp(88));
            for (SurfaceSpec.Action action:entry.spec.actions()) {
                Button button=style.cardAction(action.label(),action.primary(),structured);
                button.setOnClickListener(v -> listener.onAction(entry.key,entry.spec.revision(),action.id()));
                if (action.primary()) actions.addView(button,new LinearLayout.LayoutParams(-1,-2));
                else secondary.addView(button);
            }
            if (secondary.getChildCount()>0) actions.addView(secondary,withTopSpace(actions.getChildCount()>0?8:0));
            actions.setPadding(0,style.dp(16),0,0);
        }
        ScrollView viewport=new ScrollView(context);
        viewport.setFillViewport(false); viewport.setVerticalScrollBarEnabled(true); viewport.addView(body);
        card.addView(viewport,new LinearLayout.LayoutParams(-1,-2));
        // Usually the complete footer is visible. Only impossibly short IME/font
        // combinations scroll this region; approval text is never clipped to fit.
        ScrollView footer=new ScrollView(context); footer.setFillViewport(false); footer.setVerticalScrollBarEnabled(true); footer.addView(actions);
        card.addView(footer,new LinearLayout.LayoutParams(-1,-2)); return card;
    }
    private boolean shortFact(SurfaceSpec.Block block) {
        TextView value=style.readableText(block.text(),18,SurfaceStyle.TEXT,true);
        int available=Math.max(1,Math.min(style.dp(SurfaceStyle.CARD_WIDTH),safeArea(false).width())-style.dp(SurfaceStyle.CARD_INSET*2));
        return !SurfaceLayout.longFact(Layout.getDesiredWidth(block.text(),value.getPaint()),available,block.text().contains("\n"));
    }
    private String kind(SurfaceStore.Entry entry) {
        if (entry.selected) return entry.spec.kind().equals("choice")?context.getString(R.string.selected):context.getString(R.string.responded);
        return entry.spec.kind().equals("choice")?context.getString(R.string.awaiting_choice):entry.spec.kind().equals("result")?context.getString(R.string.ready):context.getString(R.string.new_content);
    }
    private ImageView brand() { ImageView view=new ImageView(context); view.setImageResource(R.drawable.taku_brand_mark); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return view; }
    private View divider() { View view=new View(context); view.setBackgroundColor(SurfaceStyle.LINE); return view; }
    private LinearLayout.LayoutParams controlSize() { return new LinearLayout.LayoutParams(style.dp(SurfaceStyle.TOUCH_SIZE),style.dp(SurfaceStyle.TOUCH_SIZE)); }
    private LinearLayout.LayoutParams withTopSpace(int space) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=style.dp(space); return params;
    }
    private void expand() { expanded=true; redraw(); }
    private void redraw() { hide(); render(entries,null); }
    private SurfacePosition.Area safeArea(boolean withKeyboard) {
        Rect bounds=manager.getCurrentWindowMetrics().getBounds();
        Insets bars=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        int bottomInset=withKeyboard?Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom):bars.bottom;
        int left=bars.left+style.dp(16), top=bars.top+style.dp(12);
        return new SurfacePosition.Area(left,top,Math.max(left,bounds.width()-bars.right-style.dp(16)),
                Math.max(top,bounds.height()-bottomInset-style.dp(12)));
    }
    private void updatePosition() {
        if (root==null || !root.isAttachedToWindow() || dragging) return;
        move(SurfacePosition.place(anchor,safeArea(false),safeArea(true),root.getWidth(),root.getHeight(),bottom));
    }
    private void move(SurfacePosition.Point point) {
        if (layout.x==point.x() && layout.y==point.y()) return;
        layout.x=point.x(); layout.y=point.y(); manager.updateViewLayout(root,layout);
    }
    private LinearLayout dragHandle() {
        SurfaceDragHandle handle=new SurfaceDragHandle(context,new SurfaceDragHandle.Listener() {
            @Override public void start() { dragging=true; dragStart=new SurfacePosition.Point(layout.x,layout.y); }
            @Override public void move(float dx,float dy) {
                SurfaceWindow.this.move(SurfacePosition.clamp(new SurfacePosition.Point(dragStart.x()+Math.round(dx),dragStart.y()+Math.round(dy)),safeArea(true),root.getWidth(),root.getHeight()));
            }
            @Override public void finish(boolean cancelled) {
                dragging=false;
                if (!cancelled) {
                    anchor=SurfacePosition.remember(new SurfacePosition.Point(layout.x,layout.y),safeArea(false),root.getWidth(),root.getHeight());
                    positionPrefs.write(anchor);
                }
                updatePosition();
            }
        });
        handle.setGravity(Gravity.CENTER_VERTICAL);
        handle.setOnLongClickListener(view -> {
            PopupMenu menu=new PopupMenu(context,view);
            menu.getMenu().add(context.getString(R.string.reset_position)).setOnMenuItemClickListener(item -> {
                anchor=null; positionPrefs.clear(); redraw(); return true;
            });
            menu.show(); return true;
        });
        return handle;
    }
    @Override public void hide() {
        dragging=false;
        if (root!=null) { root.animate().cancel(); manager.removeViewImmediate(root); root=null; }
    }
    private LinearLayout row() { LinearLayout view=new LinearLayout(context); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout column() { LinearLayout view=row(); view.setOrientation(LinearLayout.VERTICAL); view.setGravity(Gravity.START); return view; }
}
