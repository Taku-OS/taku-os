package org.agentos.shopping;

import android.content.Context;
import org.agentos.proactivity.api.*;
import org.json.JSONObject;

public final class ReminderAcceptanceTest {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        ReminderConnection connection=new ReminderConnection(new Context(),new ReminderConnection.Listener(){public void state(boolean allowed,String status){}});
        connection.connect();ObserverSpec spec=ProactivityClient.latest.registered;
        check(spec!=null && spec.prompt().equals(new PromptSpec("Try on","Not now")),"registration declares the actual two-button choice");
        check(spec.summary().equals(ReminderConnection.CONSENT_SUMMARY) && spec.summary().contains("camera video"),"original billed video consent remains in the prompt");
        check(ProactivityWire.spec(spec).getJSONObject("prompt").getString("acceptLabel").equals("Try on"),"main wire uses public PromptSpec");
        check(ReminderConnection.isTryOnAcceptance(new JSONObject().put("promptType","choice").put("actionId","accept")),"only the choice accept marker authorizes direct preparation");
        check(!ReminderConnection.isTryOnAcceptance(null),"missing receipt does not authorize");
        check(!ReminderConnection.isTryOnAcceptance(new JSONObject().put("actionId","accept")),"legacy Viewed receipt needs a new confirmation tap");
        check(!ReminderConnection.isTryOnAcceptance(new JSONObject().put("promptType","hint").put("actionId","accept")),"modern Viewed hint needs a new confirmation tap");
        check(!ReminderConnection.isTryOnAcceptance(new JSONObject().put("promptType","choice").put("actionId","cancel")),"Not now cannot authorize");
        check(!ReminderConnection.isTryOnAcceptance(new JSONObject().put("promptType","choice")),"choice publication alone cannot authorize");
        check(!ReminderConnection.isTryOnAcceptance(new JSONObject().put("promptType",true).put("actionId","accept")),"prompt type is not coerced");
        check(!ReminderConnection.isTryOnAcceptance(new JSONObject().put("promptType","choice").put("actionId",1)),"action ID is not coerced");
        check(!ReminderConnection.isTryOnAcceptance(new JSONObject().put("promptType","choice").put("actionId","try_on")),"legacy action requires the isolated verified adapter");
        connection.close();
        System.out.println(checks+" reminder explicit-choice assertions passed (offline; no capture/model/device work)");
    }
}
