package org.agentos.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native, branded screen offer. A task starts only after the primary action is accepted. */
final class JevHintView extends FrameLayout {
    private final TextView title, body;
    private final Button accept;
    private final DrawerTheme theme;

    JevHintView(Context context, Runnable dismiss) { this(context,dismiss,false); }

    JevHintView(Context context, Runnable dismiss, boolean manual) {
        super(context);
        String preferred=context.getSharedPreferences("agent-settings",0).getString("theme","light");
        theme=new DrawerTheme("dark".equals(preferred));
        setOnClickListener(v -> dismiss.run());
        LinearLayout card=new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20),dp(16),dp(20),dp(20));
        card.setOnClickListener(v -> {});
        card.setBackground(theme.rounded(context,theme.raised,28,true));
        card.setElevation(dp(12));
        LayoutParams placement=new LayoutParams(-1,-2,Gravity.BOTTOM);
        placement.setMargins(dp(16),0,dp(16),dp(36));
        addView(card,placement);

        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(context);logo.setImageResource(R.drawable.taku_brand_mark);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(logo,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView brand=text("Taku",15,theme.ink);brand.setTypeface(null,Typeface.BOLD);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.leftMargin=dp(9);header.addView(brand,bp);
        header.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
        if(!manual) header.addView(text("Jev test trigger",11,theme.muted));
        ImageButton close=new ImageButton(context);close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(theme.muted));close.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        close.setContentDescription("Close screen suggestions");close.setPadding(dp(12),dp(12),dp(12),dp(12));
        close.setOnClickListener(v->dismiss.run());
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(44),dp(44));cp.leftMargin=dp(2);cp.rightMargin=-dp(10);header.addView(close,cp);
        card.addView(header,new LinearLayout.LayoutParams(-1,dp(36)));

        title=text(manual?"See what is on screen and how I can help":"Analyzing current screen",21,theme.ink);
        title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(12);card.addView(title,tp);
        body=text(manual?"Understanding the previous screen…":"Jev is checking this screenshot…",15,theme.muted);
        body.setLineSpacing(dp(4),1);
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.topMargin=dp(8);card.addView(body,dp);
        accept=new Button(context);accept.setText("Take a look  →");accept.setAllCaps(false);accept.setTextSize(15);
        accept.setTypeface(null,Typeface.BOLD);accept.setTextColor(theme.onAccent);
        accept.setBackground(theme.rounded(context,theme.accent,24,false));accept.setStateListAnimator(null);
        accept.setPadding(dp(18),0,dp(18),0);accept.setContentDescription("Accept screen suggestion and hand to Agent");
        accept.setVisibility(GONE);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(48));ap.topMargin=dp(18);card.addView(accept,ap);
    }

    void result(JevScreenDecision decision,Runnable action) {
        if(decision.actionable) {
            String[] parts=decision.suggestion.split("\n",2);
            title.setText(parts.length==2?parts[0]:"Would you like help with this?");
            body.setText(parts.length==2?parts[1]:decision.suggestion);
        } else { title.setText("No interruption this time");body.setText("No suitable suggestion for the current screen."); }
        accept.setVisibility(decision.actionable?VISIBLE:GONE);
        accept.setOnClickListener(v->{accept.setEnabled(false);action.run();});
    }
    void error(String message) { title.setText("Could not connect this time");body.setText(message);accept.setVisibility(GONE); }
    private TextView text(String value,int size,int color) {
        TextView view=new TextView(getContext());view.setText(value);view.setTextSize(size);view.setTextColor(color);
        view.setIncludeFontPadding(false);return view;
    }
    private int dp(int value) { return theme.dp(getContext(),value); }
}
