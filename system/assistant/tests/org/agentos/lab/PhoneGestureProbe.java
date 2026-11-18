package org.agentos.lab;

import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;

/** Opt-in stdin probe; no action until an explicit JSON line. Never installed as an app/API. */
public final class PhoneGestureProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        JSONObject owner=new JSONObject().put("threadId","00000000-0000-0000-0000-000000000001")
            .put("turnId","00000000-0000-0000-0000-000000000002");
        PhoneGestures.startTurn(owner.getString("threadId"),owner.getString("turnId"));
        try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in,"UTF-8"))) {
            String line;
            while((line=input.readLine())!=null)try {
                JSONObject command=new JSONObject(line),result;
                if(command.getString("op").equals("touch")) {
                    int display=PhoneGestures.integer(command,"displayId");
                    result=PhoneGestures.touch(owner,display,command);
                } else if(command.getString("op").equals("state"))result=PhoneGestures.state();
                else throw new IllegalArgumentException("Only touch and state are supported");
                System.out.println(new JSONObject().put("ok",true).put("result",result));System.out.flush();
            } catch(Exception error) {
                System.out.println(new JSONObject().put("ok",false).put("error",error.getClass().getSimpleName())
                    .put("message",String.valueOf(error.getMessage())));System.out.flush();
            }
        } finally {
            PhoneGestures.cancelAll();
            System.out.println(new JSONObject().put("cleanup",PhoneGestures.state()));System.out.flush();
        }
    }
}
