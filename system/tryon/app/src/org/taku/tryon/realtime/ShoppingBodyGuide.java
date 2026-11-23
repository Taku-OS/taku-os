// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Local positioning guide only. It performs no body detection or image processing. */
final class ShoppingBodyGuide extends View {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    ShoppingBodyGuide(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        outline.setColor(Color.argb(220, 255, 255, 255));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f * density);
        outline.setStrokeCap(Paint.Cap.ROUND);
        outline.setPathEffect(new DashPathEffect(new float[]{6 * density, 5 * density}, 0));
        shadow.set(outline);
        shadow.setColor(Color.argb(90, 0, 0, 0));
        shadow.setStrokeWidth(3.5f * density);
        caption.setColor(Color.WHITE);
        caption.setTypeface(ShoppingIslandStyle.font(context));
        caption.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        caption.setTextAlign(Paint.Align.CENTER);
        captionBackground.setColor(Color.argb(130, 13, 13, 13));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width < 1 || height < 1) return;
        // Keep the head beneath the draggable status strip and the caption above controls.
        float top = Math.max(42 * density, height * .15f);
        float available = Math.max(1, height - top - 35 * density);
        float center = width * .5f;
        float headWidth = Math.min(width * .25f, available * .35f);
        float headHeight = headWidth * 1.24f;
        RectF head = new RectF(center - headWidth / 2, top, center + headWidth / 2, top + headHeight);
        canvas.drawOval(head, shadow);
        canvas.drawOval(head, outline);

        float neck = top + headHeight;
        float shoulder = neck + available * .09f;
        float waist = top + available * .96f;
        Path body = new Path();
        body.moveTo(center - headWidth * .24f, neck + 3 * density);
        body.lineTo(center - headWidth * .24f, shoulder - available * .04f);
        body.cubicTo(center - width * .20f, shoulder, center - width * .33f, shoulder,
            center - width * .36f, shoulder + available * .14f);
        body.lineTo(center - width * .42f, waist);
        body.moveTo(center + headWidth * .24f, neck + 3 * density);
        body.lineTo(center + headWidth * .24f, shoulder - available * .04f);
        body.cubicTo(center + width * .20f, shoulder, center + width * .33f, shoulder,
            center + width * .36f, shoulder + available * .14f);
        body.lineTo(center + width * .42f, waist);
        body.moveTo(center - width * .26f, shoulder + available * .23f);
        body.lineTo(center - width * .25f, waist);
        body.moveTo(center + width * .26f, shoulder + available * .23f);
        body.lineTo(center + width * .25f, waist);
        canvas.drawPath(body, shadow);
        canvas.drawPath(body, outline);

        String text = "Align head & shoulders";
        float baseline = height - 11 * density;
        float half = caption.measureText(text) / 2 + 9 * density;
        canvas.drawRoundRect(center - half, baseline - 15 * density,
            center + half, baseline + 6 * density, 10 * density, 10 * density, captionBackground);
        canvas.drawText(text, center, baseline, caption);
    }
}
