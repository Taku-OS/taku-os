package org.agentos.assistant;

import java.util.Locale;
import org.agentos.surfaces.SurfaceSpec;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BackgroundQuestionTest {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static JSONObject params() throws Exception {
        return new JSONObject().put("threadId","actual-thread").put("arguments",new JSONObject()
            .put("title","Review before booking").put("summary","Only these details will be submitted")
            .put("confirmLabel","Confirm reservation").put("fields",new JSONArray()
                .put(new JSONObject().put("label","Restaurant").put("value","Verified branch").put("style","field"))
                .put(new JSONObject().put("label","Time").put("value","2026-10-26 20:00").put("style","fact"))
                .put(new JSONObject().put("label","Allergy").put("value","Seafood allergy").put("style","notice"))));
    }
    private static void rejects(JSONObject params) throws Exception {
        try { BackgroundQuestion.fromPending("phone/confirmAction",params,Locale.ENGLISH); }
        catch(Exception expected){checks++;return;}throw new AssertionError("Invalid pending confirmation rendered");
    }
    public static void main(String[] unused) throws Exception {
        JSONObject pending=params();BackgroundQuestion question=BackgroundQuestion.fromPending("phone/confirmAction",pending,Locale.ENGLISH);
        SurfaceSpec surface=question.surface("ask-pending-key",7);
        check(surface.kind().equals("choice"),"Confirmation uses a choice surface");
        check(surface.placement().equals("bottom"),"Confirmation is pinned to bottom");
        check(surface.initialState().equals("expanded"),"Confirmation opens expanded");
        check(surface.tapActionId().isEmpty(),"Opening card must not answer");
        check(surface.title().equals("Review before booking") && surface.summary().equals("Only these details will be submitted"),"Actual title and summary");
        check(surface.blocks().size()==3 && surface.actions().size()==3,"All content and choices rendered");
        String[] types={"field","fact","notice"};
        for(int i=0;i<types.length;i++)check(surface.blocks().get(i).type().equals(types[i]),"Style preserved: "+types[i]);
        check(surface.blocks().get(2).text().equals("Seafood allergy"),"Actual notice value");
        check(surface.actions().get(0).label().equals("Confirm reservation") && surface.actions().get(0).primary(),"Provided confirmation button");
        check(surface.actions().get(1).label().equals("Edit details") && !surface.actions().get(1).primary(),"English edit button");
        check(surface.actions().get(2).label().equals("Not now") && !surface.actions().get(2).primary(),"English cancel button");
        for(int i=0;i<3;i++)check(question.response("answer-"+i,7,7).getString("decision").equals(new String[]{"confirm","modify","cancel"}[i]),"Decision mapping");
        for(String action:new String[]{"published","dismissed","expired","resume","","confirm","answer-3","answer--1"})check(question.response(action,7,7)==null,"No approval from "+action);
        check(question.response(null,7,7)==null,"No missing action");
        check(question.response("answer-0",6,7)==null,"Previous revision cannot approve");
        check(question.response("answer-0",8,7)==null,"Unshown revision cannot approve");
        pending.getJSONObject("arguments").put("title","Mutated title").getJSONArray("fields").getJSONObject(0).put("value","Mutated venue");
        check(question.surface("ask-pending-key",8).title().equals(surface.title()),"Presentation owns title snapshot");
        check(question.surface("ask-pending-key",8).blocks().get(0).text().equals("Verified branch"),"Presentation owns fields snapshot");
        SurfaceSpec chinese=BackgroundQuestion.fromPending("phone/confirmAction",params(),Locale.SIMPLIFIED_CHINESE).surface("cn",1);
        check(chinese.actions().get(1).label().equals("修改信息") && chinese.actions().get(2).label().equals("暂不操作"),"Chinese system actions");
        SurfaceSpec fallback=BackgroundQuestion.fromPending("phone/confirmAction",params(),Locale.FRENCH).surface("fr",1);
        check(fallback.actions().get(1).label().equals("Edit details"),"English default for other locales");

        JSONObject ask=new JSONObject().put("arguments",new JSONObject().put("question","Which time?").put("options",new JSONArray().put("19:00").put("20:00")));
        BackgroundQuestion choice=BackgroundQuestion.fromPending("phone/askUser",ask,Locale.ENGLISH);
        check(choice.surface("ask-user",1).placement().equals("bottom") && choice.surface("ask-user",1).initialState().equals("expanded"),"Questions remain expanded at bottom");
        check(choice.response("answer-1",1,1).getInt("optionIndex")==1,"ask_user remains index-based");
        check(choice.response("answer-2",1,1)==null,"Cannot answer an absent option");
        BackgroundQuestion approval=BackgroundQuestion.fromPending("phone/action/requestApproval",new JSONObject().put("reason","Allow local action?"),Locale.ENGLISH);
        check(approval.surface("ask-approval",1).placement().equals("bottom") && approval.surface("ask-approval",1).initialState().equals("expanded"),"Legacy approval remains expanded at bottom");
        check(approval.response("answer-0",1,1).getString("decision").equals("accept"),"Legacy accept remains compatible");
        check(approval.response("answer-1",1,1).getString("decision").equals("decline"),"Legacy decline remains compatible");
        check(!BackgroundQuestion.supports("unrecognized") && BackgroundQuestion.supports("phone/confirmAction"),"Pending method routing");
        for(Object bad:new Object[]{"",true,123,JSONObject.NULL}) {
            JSONObject invalid=params();invalid.getJSONObject("arguments").put("title",bad);rejects(invalid);
        }
        JSONObject invalid=params();invalid.getJSONObject("arguments").getJSONArray("fields").getJSONObject(0).put("style","html");rejects(invalid);
        invalid=params();invalid.getJSONObject("arguments").put("fields",new JSONArray());rejects(invalid);
        invalid=params();invalid.getJSONObject("arguments").put("confirmLabel","x".repeat(41));rejects(invalid);

        check(BackgroundTaskResult.message("failed","Execution timed out","I am about to book").equals("Execution timed out"),"Failure beats stale progress");
        check(BackgroundTaskResult.message("cancelled","Stopped by you","Booking in progress").equals("Stopped by you"),"Cancellation beats stale progress");
        check(BackgroundTaskResult.message("completed","Task completed","Request awaiting merchant confirmation").equals("Request awaiting merchant confirmation"),"Actual completed result preserved");
        check(BackgroundTaskResult.message("failed","","Explicit tool error").equals("Explicit tool error"),"Fallback when state has no explanation");
        check(BackgroundTaskResult.message("completed","Task completed",null).equals("Task completed"),"No final text fallback");
        System.out.println("BackgroundQuestionTest: "+checks+" checks passed");
    }
}
