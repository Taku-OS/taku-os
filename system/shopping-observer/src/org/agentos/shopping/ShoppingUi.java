// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;

final class ShoppingUi {
    static final int BG = 0xfff7f8f5, INK = 0xff242825, MUTED = 0xff667064, ORANGE = 0xffff5a1f;
    final Context context;
    ShoppingUi(Context context) { this.context = context; }
    int dp(int n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    TextView text(String value, int size, boolean bold) {
        TextView v = new TextView(context); v.setText(value); v.setTextSize(size); v.setTextColor(bold ? INK : MUTED);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p);
    }
    Button button(String label, Runnable action) {
        Button b = new Button(context); b.setText(label); b.setAllCaps(false); b.setMinimumHeight(dp(48));
        b.setTextColor(INK); b.setContentDescription(label); b.setOnClickListener(v -> action.run()); return b;
    }
    LinearLayout page() {
        LinearLayout root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom); return insets; }); return root;
    }
}
