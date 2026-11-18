package org.agentos.assistant;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

/** Resource-backed access. All semantic values are generated from source tokens. */
final class DesignTokens {
 final Context context;final Resources resources;
 DesignTokens(Context context){this.context=context;resources=context.getResources();}
 int color(int id){return resources.getColor(id,context.getTheme());}
 float px(int id){return resources.getDimension(id);}
 int pixels(int id){return Math.round(px(id));}
 int dp(int id){return Math.round(px(id)/resources.getDisplayMetrics().density);}
 float fraction(int id){return resources.getFraction(id,1,1);}
 int integer(int id){return resources.getInteger(id);}
 int rawDp(float value){return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,value,resources.getDisplayMetrics()));}
}
