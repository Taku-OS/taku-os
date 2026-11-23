// SPDX-License-Identifier: Apache-2.0
import org.agentos.proactivity.api.*;
import org.json.JSONObject;

public final class PixelLegacyProactivityTest {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static JSONObject accepted()throws Exception{
        return new JSONObject().put("ok",true).put("type","accepted").put("result",new JSONObject()
            .put("observerId","org.agentos.shopping.tiktok").put("sourcePackage","org.agentos.shopping")
            .put("actionId","try_on").put("actionLabel","Try on").put("targetPackage","org.taku.tryon").put("consentCode","live_frame_try_on_v2")
            .put("presentationToken","test_lease").put("presentationId","test_id").put("presentationExpiresAt",123456));
    }
    public static void main(String[] args)throws Exception{
        ObserverSpec spec=new ObserverSpec("org.agentos.shopping.tiktok","Name","org.agentos.shopping.garment_ready",500,60000,1,60000,"Title","Summary",new PromptSpec("Try on","Not now"));
        JSONObject wire=PixelLegacyProactivity.spec(spec);
        check(!wire.has("prompt") && wire.getString("hintPresentation").equals("island"),"phone wire uses only old host fields");
        JSONObject action=wire.getJSONObject("action");
        check(action.length()==4 && action.getString("id").equals("try_on") && action.getString("label").equals("Try on")
            && action.getString("targetPackage").equals("org.taku.tryon") && action.getString("consentCode").equals("live_frame_try_on_v2"),"legacy fixed action declaration");
        JSONObject raw=accepted(),mapped=PixelLegacyProactivity.receipt(raw),result=mapped.getJSONObject("result");
        check(result.getString("promptType").equals("choice") && result.getString("actionId").equals("accept"),"authenticated old action maps to modern choice marker");
        check(raw.getJSONObject("result").getString("actionId").equals("try_on"),"raw receipt remains unchanged");
        check(result.getString("presentationToken").equals("test_lease") && result.getString("presentationId").equals("test_id")
            && result.getLong("presentationExpiresAt")==123456,"genuine opaque presentation fields survive translation");
        for(String field:new String[]{"observerId","sourcePackage","actionId","actionLabel","targetPackage","consentCode"}){
            JSONObject invalid=accepted();invalid.getJSONObject("result").put(field,"different").put("promptType","choice");
            check(!PixelLegacyProactivity.receipt(invalid).getJSONObject("result").has("promptType"),"unverified legacy field cannot authorize: "+field);
        }
        JSONObject viewed=accepted();viewed.getJSONObject("result").put("actionId","accept").remove("consentCode");
        check(!PixelLegacyProactivity.receipt(viewed).getJSONObject("result").has("promptType"),"Viewed never becomes a choice");
        JSONObject coerced=accepted().put("ok","true");
        check(!PixelLegacyProactivity.receipt(coerced).getJSONObject("result").has("promptType"),"ok must be an actual boolean");
        JSONObject published=accepted().put("type","published");
        check(!PixelLegacyProactivity.receipt(published).getJSONObject("result").has("promptType"),"publication is not a user gesture");
        System.out.println(checks+" Pixel legacy prompt-adapter assertions passed (offline)");
    }
}
