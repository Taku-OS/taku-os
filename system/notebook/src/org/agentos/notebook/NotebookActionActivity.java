package org.agentos.notebook;

import android.app.Activity;
import android.graphics.ImageDecoder;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class NotebookActionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 36, 28, 24);
        layout.setFitsSystemWindows(true);
        var title = new TextView(this);
        title.setText(getIntent().getStringExtra("title") + " · " + getIntent().getStringExtra("source"));
        title.setTextSize(18);
        layout.addView(title);
        var image = new ImageView(this);
        image.setContentDescription("Original screenshot");
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        var uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        new Thread(() -> {
            try {
                var bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, src) -> decoder.setTargetSampleSize(2));
                runOnUiThread(() -> image.setImageBitmap(bitmap));
            } catch (Exception unavailable) {
                runOnUiThread(() -> title.setText("Screenshot unavailable"));
            }
        }, "notebook-preview").start();
    }
}
