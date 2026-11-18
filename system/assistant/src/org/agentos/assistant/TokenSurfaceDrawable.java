package org.agentos.assistant;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Static token-driven surface. Micro dots are native pattern geometry, not a crop. */
final class TokenSurfaceDrawable extends Drawable {
 private final DesignTokens t;private final int color;private final boolean feature,texture;
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private int alpha=255;
 TokenSurfaceDrawable(Context context,int color,boolean feature,boolean texture){t=new DesignTokens(context);this.color=color;this.feature=feature;this.texture=texture;}
 @Override public void draw(Canvas canvas){Rect b=getBounds();RectF bounds=new RectF(b);float radius=t.px(feature?R.dimen.ag_radius_feature:R.dimen.ag_radius_tile);paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setAlpha(alpha);canvas.drawRoundRect(bounds,radius,radius,paint);
  if(texture){canvas.save();Path clip=new Path();clip.addRoundRect(bounds,radius,radius,Path.Direction.CW);canvas.clipPath(clip);paint.setColor(t.color(R.color.ag_color_text_primary));paint.setAlpha(Math.round(alpha*t.fraction(R.fraction.ag_texture_micro_opacity)));float pitch=t.px(R.dimen.ag_texture_micro_pitch),r=t.px(R.dimen.ag_texture_micro_diameter)/2;for(float y=b.top+pitch/2;y<b.bottom;y+=pitch)for(float x=b.left+pitch/2;x<b.right;x+=pitch)canvas.drawCircle(x,y,r,paint);canvas.restore();}
  float stroke=t.px(feature?R.dimen.ag_border_feature:R.dimen.ag_border_normal);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(t.color(feature?R.color.ag_color_surface:R.color.ag_color_border));paint.setAlpha(alpha);bounds.inset(stroke/2,stroke/2);canvas.drawRoundRect(bounds,radius,radius,paint);
 }
 @Override public void setAlpha(int alpha){this.alpha=alpha;invalidateSelf();}
 @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
 @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
