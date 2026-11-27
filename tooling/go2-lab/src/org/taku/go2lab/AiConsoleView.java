package org.taku.go2lab;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

/** Compact composer remains visible beneath every robot page. */
final class AiConsoleView {
  final LinearLayout root;
  final EditText input;
  final Button send, confirm, cancel, settings;
  final ImageButton mode;
  final TextView heading, status, result;
  private final Activity activity;
  final android.content.Context context;

  AiConsoleView(Activity activity) {
    this.activity = activity;
    android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
    config.setLocales(new android.os.LocaleList(java.util.Locale.ENGLISH));
    context = activity.createConfigurationContext(config);
    int ink = context.getColor(R.color.go2_ink);
    root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16),dp(8),dp(16),dp(10));
    GradientDrawable surface = shape(context.getColor(R.color.go2_paper),24);
    surface.setCornerRadii(new float[]{dp(24),dp(24),dp(24),dp(24),0,0,0,0});root.setBackground(surface);root.setElevation(dp(8));
    LinearLayout title=row();title.setGravity(Gravity.CENTER_VERTICAL);
    heading=text(13,true);heading.setText(R.string.ai_title);title.addView(heading,new LinearLayout.LayoutParams(0,dp(28),1));heading.setGravity(Gravity.CENTER_VERTICAL);
    cancel=button(context.getString(R.string.ai_stop),11);cancel.setTextColor(0xff8e493e);cancel.setContentDescription(context.getString(R.string.ai_stop_label));
    title.addView(cancel,new LinearLayout.LayoutParams(-2,dp(36)));
    settings=button("•••",14);settings.setContentDescription(context.getString(R.string.ai_settings));
    title.addView(settings,new LinearLayout.LayoutParams(dp(44),dp(36)));root.addView(title);
    status=text(10,false);status.setTextColor(0xff6b7680);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setText(R.string.ai_loading);
    root.addView(status);
    result=text(12,false);result.setMaxLines(2);result.setEllipsize(android.text.TextUtils.TruncateAt.END);result.setLineSpacing(dp(2),1);
    result.setPadding(0,dp(4),0,dp(2));result.setVisibility(android.view.View.GONE);
    result.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){result.setVisibility(s.length()==0?android.view.View.GONE:android.view.View.VISIBLE);}public void afterTextChanged(android.text.Editable s){}});
    result.setOnClickListener(v->new android.app.AlertDialog.Builder(activity).setTitle(context.getString(R.string.ai_title)).setMessage(result.getText()).setPositiveButton(context.getString(android.R.string.ok),null).show());root.addView(result);
    LinearLayout composer=row();composer.setGravity(Gravity.CENTER_VERTICAL);composer.setPadding(dp(4),dp(3),dp(4),dp(3));composer.setBackground(shape(0xffffffff,30));
    LinearLayout.LayoutParams composeParams=new LinearLayout.LayoutParams(-1,dp(54));composeParams.topMargin=dp(8);root.addView(composer,composeParams);
    mode=new ImageButton(context);mode.setImageResource(R.drawable.ic_manual);mode.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
    mode.setContentDescription(context.getString(R.string.ai_takeover));mode.setTooltipText(context.getString(R.string.ai_takeover));mode.setBackground(shape(0xffeaf0f5,24));mode.setPadding(dp(11),dp(11),dp(11),dp(11));
    composer.addView(mode,new LinearLayout.LayoutParams(dp(44),dp(44)));
    input=new EditText(context);input.setTextSize(14);input.setTextColor(ink);input.setSingleLine(true);input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    input.setHint(R.string.ai_input_hint);input.setHintTextColor(0xff84909b);input.setContentDescription(context.getString(R.string.ai_input_label));
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(800)});input.setPadding(dp(12),0,dp(6),0);
    composer.addView(input,new LinearLayout.LayoutParams(0,dp(46),1));
    send=button("↑",24);send.setTextColor(0xffffffff);send.setBackground(shape(0xff171b20,24));send.setContentDescription(context.getString(R.string.ai_send));
    send.setBackgroundTintList(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xffaebdc9,0xff171b20}));
    composer.addView(send,new LinearLayout.LayoutParams(dp(44),dp(44)));
    confirm=button(context.getString(R.string.ai_confirm),13);confirm.setTextColor(0xffffffff);confirm.setBackground(shape(ink,24));confirm.setVisibility(android.view.View.GONE);
    LinearLayout.LayoutParams confirmParams=new LinearLayout.LayoutParams(-1,dp(44));confirmParams.topMargin=dp(8);root.addView(confirm,confirmParams);
  }
  private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  private Button button(String label,int size){Button b=new Button(context);b.setText(label);b.setTextSize(size);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(10),0,dp(10),0);b.setBackgroundColor(android.graphics.Color.TRANSPARENT);return b;}

  int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
  private LinearLayout row() { LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
  private TextView text(int size, boolean bold) {
    TextView view = new TextView(context); view.setTextSize(size); view.setTextColor(context.getColor(R.color.go2_ink));
    if (bold) view.setTypeface(null, Typeface.BOLD); return view;
  }
  String english(int id) {
    return context.getString(id);
  }
  void sceneOwner(boolean manual, String phase) {
    settings.setText("•••");send.setText("↑");
    input.setEnabled(!manual);send.setEnabled(!manual);
    input.setHint(english(R.string.ai_scene_input));input.setContentDescription(english(R.string.ai_scene_input));
    mode.setImageResource(manual?R.drawable.ic_ai:R.drawable.ic_manual);
    String label=english(manual?R.string.ai_enable:R.string.ai_takeover);mode.setContentDescription(label);mode.setTooltipText(label);
    mode.setEnabled(true);confirm.setText(english(R.string.ai_scene_confirm));
    confirm.setVisibility("confirm".equals(phase)?android.view.View.VISIBLE:android.view.View.GONE);confirm.setEnabled(!manual);
    cancel.setText(english(R.string.ai_scene_end));cancel.setContentDescription(english(R.string.ai_scene_end));
    result.setMaxLines(2);
  }
  void ordinaryLabels() {
    settings.setText("•••");send.setText("↑");confirm.setText(R.string.ai_confirm);
    input.setContentDescription(context.getString(R.string.ai_input_label));
  }
  void owner(boolean ai, boolean busy) {
    mode.setImageResource(ai ? R.drawable.ic_manual : R.drawable.ic_ai);
    String label = context.getString(ai ? R.string.ai_takeover : R.string.ai_enable);
    mode.setContentDescription(label); mode.setTooltipText(label);
    input.setEnabled(ai); send.setEnabled(ai && !busy);
    input.setHint(ai ? R.string.ai_input_hint : R.string.ai_manual_hint);
  }
}
