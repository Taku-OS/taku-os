package org.agentos.lab;
import org.json.*;

public final class UiCardRequestTest {
    static int assertions;
    static void check(boolean value){assertions++;if(!value)throw new AssertionError();}
    static void rejects(JSONObject args){assertions++;try{UiCardRequest.validate(args);}catch(Exception expected){return;}throw new AssertionError("Expected rejection: "+args);}
    static void rejectsSchema(Object value,JSONObject schema){assertions++;try{UiCardRequest.validateSchema(value,schema);}catch(Exception expected){return;}throw new AssertionError("Expected schema rejection");}
    static JSONObject item(String id,String label,String amount,String url)throws Exception{return new JSONObject().put("id",id).put("label",label)
        .put("value",new JSONObject().put("type","money").put("value",amount).put("currency","USD").put("scale",2))
        .put("detail","").put("url",url).put("image","").put("icon","");}
    static JSONObject block(String kind)throws Exception{return new JSONObject().put("id","coat-prices").put("kind",kind).put("title","").put("text","")
        .put("value",new JSONObject().put("type","text").put("value",JSONObject.NULL)).put("image","").put("url","").put("items",new JSONArray()).put("config",new JSONObject());}
    public static void main(String[] args)throws Exception{
        JSONObject prices=block("collection").put("title","Black Friday").put("items",new JSONArray()
            .put(item("uniqlo-coat","Uniqlo","13900","https://example.com/a")).put(item("zara-coat","Zara","14900","")));
        JSONObject card=new JSONObject().put("version",2).put("key","coat-compare").put("mode","panel").put("title","Wool coat").put("summary","Lowest $139").put("blocks",new JSONArray().put(prices))
            .put("actions",new JSONArray().put(new JSONObject().put("id","watch-price").put("label","Watch price").put("reply","Tell me when it drops under $145").put("style","primary")));
        check(UiCardRequest.validate(card).getString("key").equals("coat-compare"));
        check(UiCardRequest.validate(new JSONObject(card.toString()).put("title","")).getBoolean("shown"));
        rejects(new JSONObject(card.toString()).put("blocks",new JSONArray().put(new JSONObject(prices.toString()).put("image","file:///sdcard/a.png"))));
        rejects(new JSONObject(card.toString()).put("key","Coat Compare"));
        rejects(new JSONObject(card.toString()).put("mode","widget"));
        rejects(new JSONObject(card.toString()).put("blocks",new JSONArray()));
        rejects(new JSONObject(card.toString()).put("blocks",new JSONArray().put(new JSONObject(prices.toString()).put("kind","weather"))));
        rejects(new JSONObject(card.toString()).put("blocks",new JSONArray().put(new JSONObject(prices.toString()).put("items",new JSONArray().put(item("coat","Coat","13900","javascript:alert(1)"))))));
        rejects(new JSONObject(card.toString()).put("actions",new JSONArray().put(new JSONObject().put("id","pay").put("label","Pay").put("reply","").put("style","primary"))));
        JSONObject noVersion=new JSONObject(card.toString());noVersion.remove("version");rejects(noVersion);
        JSONObject missingCurrency=item("coat","Coat","13900","");missingCurrency.getJSONObject("value").remove("currency");
        rejects(new JSONObject(card.toString()).put("blocks",new JSONArray().put(new JSONObject(prices.toString()).put("items",new JSONArray().put(missingCurrency)))));
        JSONObject duplicate=new JSONObject(card.toString());duplicate.getJSONArray("blocks").put(new JSONObject(prices.toString()));rejects(duplicate);
        JSONObject historical=new JSONObject(card.toString());
        historical.getJSONArray("blocks").getJSONObject(0).put("config",new JSONObject().put("layout","table")
            .put("tone","dark").put("supportingColor","#123C5F").put("gradient",new JSONArray().put("#459EF6").put("#F1F8FC")));
        String original=historical.toString();
        check(UiCardRequest.validate(historical).getBoolean("shown"));
        check(historical.toString().equals(original));
        JSONObject projected=UiCardRequest.project(historical);
        check(projected.getJSONArray("blocks").getJSONObject(0).getJSONObject("config").length()==1);
        check(projected.getJSONArray("blocks").getJSONObject(0).getJSONObject("config").getString("layout").equals("table"));
        check(projected.getJSONArray("blocks").getJSONObject(0).getJSONArray("items")==historical.getJSONArray("blocks").getJSONObject(0).getJSONArray("items"));
        check(projected.getJSONArray("actions")==historical.getJSONArray("actions"));
        check(historical.toString().equals(original));
        check(UiCardRequest.project(card)==card);
        JSONObject configSchema=UiCardRequest.schema().getJSONObject("blocks").getJSONObject("items").getJSONObject("properties").getJSONObject("config");
        for(JSONObject hints:new JSONObject[]{new JSONObject().put("tone","light"),new JSONObject().put("supportingColor",""),new JSONObject().put("gradient",new JSONArray())}) {
            JSONObject legacy=new JSONObject(card.toString());legacy.getJSONArray("blocks").getJSONObject(0).put("config",hints);
            check(UiCardRequest.validate(legacy).getBoolean("shown"));
            rejectsSchema(hints,configSchema);
        }
        for(JSONObject invalid:new JSONObject[]{new JSONObject().put("tone","blue"),new JSONObject().put("tone",JSONObject.NULL),
            new JSONObject().put("supportingColor",false),new JSONObject().put("supportingColor","x".repeat(81)),
            new JSONObject().put("gradient",new JSONArray().put(false)),new JSONObject().put("gradient",JSONObject.NULL),
            new JSONObject().put("gradient",new JSONArray().put("x".repeat(81))),
            new JSONObject().put("gradient",new JSONArray(java.util.Collections.nCopies(9,""))),
            new JSONObject().put("tone","dark").put("unrelated",true)}) {
            JSONObject legacy=new JSONObject(card.toString());legacy.getJSONArray("blocks").getJSONObject(0).put("config",invalid);rejects(legacy);
        }
        JSONObject camera=new JSONObject(card.toString());
        camera.getJSONArray("blocks").getJSONObject(0).put("config",new JSONObject().put("mediaType","camera").put("imageFilter","mono")
            .put("filterStrength",0).put("recordAudio",false).put("analysisPrompt","Frame the subject").put("tone","light"));
        String cameraBefore=camera.toString();check(UiCardRequest.validate(camera).getBoolean("shown"));check(camera.toString().equals(cameraBefore));
        JSONObject projectedCamera=UiCardRequest.project(camera).getJSONArray("blocks").getJSONObject(0).getJSONObject("config");
        check(projectedCamera.getDouble("filterStrength")==0 && !projectedCamera.getBoolean("recordAudio"));
        check(projectedCamera.getString("imageFilter").equals("mono") && projectedCamera.getString("analysisPrompt").equals("Frame the subject"));
        check(!projectedCamera.has("tone") && camera.toString().equals(cameraBefore));
        System.out.println("UiCardRequestTest: "+assertions+" assertions passed");
    }
}
