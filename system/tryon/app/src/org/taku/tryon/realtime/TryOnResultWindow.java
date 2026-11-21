// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import org.taku.tryon.ui.Ui;
/** App-owned terminal sheet. Retry opens a visible Activity before another camera/cloud attempt. */
final class TryOnResultWindow {
    private static TryOnResultWindow shown;
    private final WindowManager windows;
    private final LinearLayout root;
    private TryOnResultWindow(Context c,String detail){
        windows=c.getSystemService(WindowManager.class);Ui ui=new Ui(c);root=ui.column();
        root.setPadding(ui.dp(24),ui.dp(20),ui.dp(24),ui.dp(24));root.setBackground(ui.rounded(Color.rgb(13,13,13),0,28));
        ui.add(root,ui.text("Taku · Try-on stopped",23,Color.WHITE,true));ui.space(root,20);
        ui.add(root,ui.text(detail,17,Color.WHITE,false));ui.space(root,16);
        ui.add(root,ui.text("Camera is off. Retry starts a new cloud session, up to 60 seconds. Provider charges may apply.",14,Color.LTGRAY,false));ui.space(root,20);
        LinearLayout row=ui.row();Button close=ui.button("Close",false),retry=ui.button("Retry",true);
        row.addView(close,new LinearLayout.LayoutParams(0,ui.dp(52),1));row.addView(retry,new LinearLayout.LayoutParams(0,ui.dp(52),1));ui.add(root,row);
        close.setOnClickListener(v->dismiss());retry.setOnClickListener(v->{dismiss();c.startActivity(new Intent(c,TryOnRetryActivity.class).setAction("retry").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));});
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.BOTTOM;p.width=Math.min(c.getResources().getDisplayMetrics().widthPixels-ui.dp(24),ui.dp(520));p.y=ui.dp(28);p.setTitle("Taku try-on result");windows.addView(root,p);
    }
    static void show(Context c,String detail){dismissCurrent();try{shown=new TryOnResultWindow(c,detail);}catch(RuntimeException unavailable){}}
    static void dismissCurrent(){if(shown!=null)shown.dismiss();}
    private void dismiss(){try{windows.removeViewImmediate(root);}catch(RuntimeException ignored){}if(shown==this)shown=null;}
}
