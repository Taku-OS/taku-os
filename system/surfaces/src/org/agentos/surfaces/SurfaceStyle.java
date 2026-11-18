package org.agentos.surfaces;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

/** Ticker/Halal Lab reference geometry, adapted to the existing Taku brand. */
final class SurfaceStyle {
    static final int TEXT=0xfff6f6f2, MUTED=0xffa4a7a1, ORANGE=0xffff6728, LINE=0xff343631;
    static final int CAPSULE_WIDTH=292, CARD_WIDTH=360, CAPSULE_HEIGHT=56, CARD_INSET=20, TOUCH_SIZE=44;
    final Context context;
    SurfaceStyle(Context context) { this.context=context; }
    int dp(float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    TextView readableText(String value,int size,int color,boolean medium) {
        TextView text=text(value,size,color,medium);
        text.setEllipsize(null); text.setLineSpacing(dp(3),1);
        return text;
    }
    TextView text(String value,int size,int color,boolean medium) {
        TextView text=new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setFontFeatureSettings("kern"); text.setIncludeFontPadding(false);
        text.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        text.setEllipsize(TextUtils.TruncateAt.END); text.setTextDirection(TextView.TEXT_DIRECTION_FIRST_STRONG); return text;
    }
    Button action(String label,boolean primary) {
        Button button=new Button(context); button.setText(label); button.setContentDescription(label); button.setAllCaps(false);
        button.setTextSize(13); button.setTypeface(Typeface.create("sans-serif-medium",0));
        button.setTextColor(primary?0xff141710:TEXT); button.setMaxLines(2); button.setEllipsize(TextUtils.TruncateAt.END);
        button.setIncludeFontPadding(false); button.setLineHeight(dp(18)); button.setGravity(Gravity.CENTER); button.setPadding(dp(12),dp(4),dp(12),dp(4));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(TOUCH_SIZE)); button.setMinimumHeight(dp(TOUCH_SIZE));
        button.setStateListAnimator(null); button.setElevation(0);
        int fill=primary?0xfff0f1eb:0xff2d302b;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22777777),shape(fill,22,0),null)); return button;
    }
    Button cardAction(String label,boolean primary,boolean structured) {
        Button button=action(label,primary);
        // A confirmation must remain readable at large font sizes and on narrow displays.
        button.setMaxLines(Integer.MAX_VALUE); button.setEllipsize(null);
        button.setTextSize(structured?15:13); button.setLineSpacing(dp(3),1);
        button.setPadding(dp(16),dp(13),dp(16),dp(13));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        if (structured && primary) {
            button.setTextColor(0xff201710);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22777777),shape(ORANGE,16,0),null));
        } else {
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22777777),shape(primary?0xfff0f1eb:0xff2d302b,16,0),null));
        }
        return button;
    }
    ImageButton icon(int resource,String description) {
        ImageButton button=new ImageButton(context); button.setImageResource(resource); button.setContentDescription(description);
        button.setImageTintList(ColorStateList.valueOf(MUTED)); button.setPadding(dp(12),dp(12),dp(12),dp(12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),null,shape(0xffffffff,22,0)));
        button.setStateListAnimator(null); button.setElevation(0); return button;
    }
    GradientDrawable shape(int color,int radius,int line) {
        GradientDrawable value=new GradientDrawable(); value.setColor(color); value.setCornerRadius(dp(radius));
        if (line!=0) value.setStroke(dp(1),line); return value;
    }
    GradientDrawable material(int radius) {
        GradientDrawable value=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff20221f,0xff131512});
        value.setCornerRadius(dp(radius)); value.setStroke(dp(1),LINE); return value;
    }
}
