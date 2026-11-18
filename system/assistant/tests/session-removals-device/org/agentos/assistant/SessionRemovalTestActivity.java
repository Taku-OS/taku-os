package org.agentos.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;

/** Test APK only; renders production controls against the isolated fixture broker. */
public final class SessionRemovalTestActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{
            AgentNativeView view=new AgentNativeView(this,new DrawerTheme(false),new AgentRepository(this),
                    "00000000-0000-0000-0000-000000000001",new AgentNativeView.Actions(){
                public void back(){finish();}
                public void select(String id){}
                public void changed(String title){setTitle(title);}
                public void dismiss(){finish();}
            });
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(view);setContentView(scroll);
            if("archive".equals(getIntent().getStringExtra("page")))view.openMethod("thread/archive");
            else view.removalProgress();
        }catch(Exception error){throw new IllegalStateException(error);}
    }
}
