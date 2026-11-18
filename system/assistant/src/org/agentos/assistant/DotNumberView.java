package org.agentos.assistant;

import android.content.Context;
import android.graphics.*;
import android.util.TypedValue;
import android.view.View;

/** One shared fixed 5x7 glyph asset. It never depends on a platform dot font. */
final class DotNumberView extends View {
 private static final String[] GLYPHS={
  "01110100011001110101110011000101110","00100011000010000100001000010001110",
  "01110100010000100010001000100011111","11110000010000101110000010000111110",
  "00010001100101010010111110001000010","11111100001000011110000010000111110",
  "01110100001000011110100011000101110","11111000010001000100010000100001000",
  "01110100011000101110100011000101110","01110100011000101111000010000101110"};
 private final DesignTokens tokens;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 private String value="00:00";private boolean fallback;
 DotNumberView(Context context){super(context);tokens=new DesignTokens(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);paint.setColor(tokens.color(R.color.ag_color_text_primary));}
 void setTime(int hour,int minute){String next=String.format(java.util.Locale.ROOT,"%02d:%02d",hour,minute);if(!next.equals(value)){value=next;requestLayout();invalidate();}setContentDescription(hour+"h"+minute+"m");}
 float dotWidth(){float diameter=tokens.px(R.dimen.ag_dot_home_diameter),pitch=tokens.px(R.dimen.ag_dot_home_pitch),gap=tokens.px(R.dimen.ag_dot_home_digit_gap);float width=0;for(int i=0;i<value.length();i++){int columns=value.charAt(i)==':'?1:tokens.integer(R.integer.ag_dot_glyph_columns);width+=(columns-1)*pitch+diameter;if(i+1<value.length())width+=gap;}return width;}
 @Override protected void onMeasure(int widthSpec,int heightSpec){
  int widthMode=MeasureSpec.getMode(widthSpec),available=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();fallback=getResources().getConfiguration().fontScale>1.2f||(widthMode!=MeasureSpec.UNSPECIFIED&&dotWidth()>available);
  paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(tokens.px(R.dimen.ag_type_context_title_size));
  int desiredWidth=Math.round(fallback?paint.measureText(value):dotWidth())+getPaddingLeft()+getPaddingRight();
  Paint.FontMetrics fm=paint.getFontMetrics();float height=fallback?Math.max(fm.descent-fm.ascent,tokens.px(R.dimen.ag_type_context_title_line_height)):(tokens.integer(R.integer.ag_dot_glyph_rows)-1)*tokens.px(R.dimen.ag_dot_home_pitch)+tokens.px(R.dimen.ag_dot_home_diameter);
  setMeasuredDimension(resolveSize(desiredWidth,widthSpec),resolveSize(Math.round(height)+getPaddingTop()+getPaddingBottom(),heightSpec));
 }
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float x=getPaddingLeft(),y=getPaddingTop();
  if(fallback){Paint.FontMetrics fm=paint.getFontMetrics();canvas.drawText(value,x,y-fm.ascent,paint);return;}
  float diameter=tokens.px(R.dimen.ag_dot_home_diameter),pitch=tokens.px(R.dimen.ag_dot_home_pitch),gap=tokens.px(R.dimen.ag_dot_home_digit_gap);int rows=tokens.integer(R.integer.ag_dot_glyph_rows);
  for(int n=0;n<value.length();n++){char c=value.charAt(n);int columns=c==':'?1:tokens.integer(R.integer.ag_dot_glyph_columns);String shape=c>='0'&&c<='9'?GLYPHS[c-'0']:null;
   for(int row=0;row<rows;row++)for(int col=0;col<columns;col++){boolean on=shape!=null&&shape.charAt(row*columns+col)=='1';if(c==':')on=row==2||row==4;if(c=='-')on=row==3;if(c=='+')on=row==3||col==2;if(on)canvas.drawCircle(x+col*pitch+diameter/2,y+row*pitch+diameter/2,diameter/2,paint);}
   x+=(columns-1)*pitch+diameter+gap;
  }
 }
}
