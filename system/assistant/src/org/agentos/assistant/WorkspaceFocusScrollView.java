package org.agentos.assistant;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Single scroll surface for a real frame and its controls at any available window height. */
final class WorkspaceFocusScrollView extends ScrollView {
    private final View frame, controls;
    private final int minimumFrameHeight;

    WorkspaceFocusScrollView(Context context, View frame, View controls) {
        super(context);
        this.frame = frame;
        this.controls = controls;
        float density = getResources().getDisplayMetrics().density;
        minimumFrameHeight = Math.round(144 * density);
        setFillViewport(true);
        setClipToPadding(false);
        LinearLayout contents = new LinearLayout(context);
        contents.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams frameSize = new LinearLayout.LayoutParams(-1, minimumFrameHeight);
        frameSize.leftMargin = frameSize.rightMargin = Math.round(16 * density);
        contents.addView(frame, frameSize);
        contents.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        addView(contents, new ScrollView.LayoutParams(-1, -2));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int viewport = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
        int availableWidth = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        // The PRD focus view gives the real app about 60% of the content rectangle.
        // With fewer real steps it may use spare room; long details or large text scroll
        // instead of shrinking the app to a thumbnail. This is a viewer layout mapping,
        // not an exported Figma dimension or a device-height minimum.
        controls.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int frameHeight = Math.max(minimumFrameHeight, Math.max(Math.round(viewport * .6f), viewport - controls.getMeasuredHeight()));
        frame.getLayoutParams().height = frameHeight;
        super.onMeasure(widthSpec, heightSpec);
    }
}
