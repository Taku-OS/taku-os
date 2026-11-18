package org.agentos.assistant;
import android.app.AlertDialog;
import android.view.*;
final class NativeDialogs {
 static void show(View anchor,AlertDialog.Builder builder){show(anchor,builder.create());}
 static void show(View anchor,AlertDialog dialog){Window window=dialog.getWindow();window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);WindowManager.LayoutParams attrs=window.getAttributes();attrs.token=anchor.getWindowToken();window.setAttributes(attrs);dialog.show();}
}
