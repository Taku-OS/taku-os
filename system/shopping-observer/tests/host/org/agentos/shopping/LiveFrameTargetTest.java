// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class LiveFrameTargetTest {
    private static int assertions;
    static final String GOOD="{\"clear\":true,\"single_target\":true,\"worn_by_presenter\":true,\"screen_or_photo\":false,\"bounds\":[250,200,750,700],\"description\":\"a fitted leopard-print long-sleeve top\"}";
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        LiveFrameTarget target=LiveFrameTarget.parse(GOOD);
        check(target!=null,"a clear single top actually worn by the presenter is accepted");
        check(target.outfitPrompt().startsWith("Substitute the current top with a fitted leopard-print"),"actual image description builds the native prompt");
        check(target.outfitPrompt().length()<=2000,"native prompt budget preserved");
        ScreenshotCrop.Bounds crop=target.bounds().pixels(1080,2424);
        check(crop!=null&&crop.left()==270&&crop.top()==484&&crop.right()==810&&crop.bottom()==1697,"normalized model box maps into the original screenshot, outward rounded");
        check(target.bounds().pixels(128,128)==null,"an unusably small actual pixel crop is rejected");
        check(target.bounds().pixels(Integer.MAX_VALUE,2424)==null,"pixel multiplication dimensions are bounded");
        check(target.bounds().withinVideo(273,2070,2424),"actual live header and native card geometry bound the garment target");
        check(!new LiveFrameTarget.Bounds(25,850,225,980).withinVideo(273,2070,2424),"bottom native product thumbnail cannot become the reference");
        check(!new LiveFrameTarget.Bounds(250,50,750,500).withinVideo(273,2070,2424),"header UI target is excluded");
        check(!target.bounds().withinVideo(-1,2070,2424)&&!target.bounds().withinVideo(273,2500,2424),"invalid window crop domains fail closed");
        check(target.bounds().withinVideo(273,2424,2424),"no-card live video remains eligible");
        check(LiveFrameTarget.parse(" \n"+GOOD+"\t")!=null,"JSON whitespace accepted");
        String reordered="{\"description\":\"a red top\",\"bounds\":[200,200,800,700],\"screen_or_photo\":false,\"worn_by_presenter\":true,\"single_target\":true,\"clear\":true}";
        check(LiveFrameTarget.parse(reordered)!=null,"JSON object key order does not create a copy-specific failure");
        check(LiveFrameTarget.parse(GOOD.replace("a fitted leopard-print long-sleeve top","women’s long‑sleeve top — dark brown"))!=null,"natural English punctuation is retained");
        check(LiveFrameTarget.parse(GOOD.replace("a fitted leopard-print long-sleeve top","red top"))!=null,"short concrete descriptions have no arbitrary word-count gate");
        String quoted="a "+(char)92+(char)34+"dark"+(char)92+(char)34+" top";
        check(LiveFrameTarget.parse(GOOD.replace("a fitted leopard-print long-sleeve top",quoted))!=null,"JSON escaped English quotes are decoded");
        for(String flag:new String[]{"clear","single_target","worn_by_presenter"})
            check(LiveFrameTarget.parse(GOOD.replace("\""+flag+"\":true","\""+flag+"\":false"))==null,"unclear, multi-target and non-presenter garments cannot start");
        check(LiveFrameTarget.parse(GOOD.replace("\"screen_or_photo\":false","\"screen_or_photo\":true"))==null,"phone or photograph target is rejected");
        for(String value:new String[]{"\"true\"","1","null","TRUE","{}","[]"})
            check(LiveFrameTarget.parse(GOOD.replace("\"clear\":true","\"clear\":"+value))==null,"flags are real booleans only");
        for(String box:new String[]{"[250,200,750]","[250,200,750,700,800]","[-1,200,750,700]","[250,200,1001,700]","[250,200,750,1001]","[250,200,300,700]","[750,200,250,700]","[250,700,750,200]","[250.0,200,750,700]","[0250,200,750,700]","[2e2,200,750,700]","[null,200,750,700]"})
            check(LiveFrameTarget.parse(GOOD.replace("[250,200,750,700]",box))==null,"malformed or unsafe boxes rejected");
        for(String invalid:new String[]{null,"",GOOD+" more","```json\n"+GOOD+"\n```",GOOD+GOOD,GOOD.replace("\"clear\":true,",""),GOOD.replace("\"clear\":true,","\"clear\":true,\"clear\":true,"),GOOD.replace("\"description\":","\"extra\":false,\"description\":"),GOOD.replace("\"clear\":true,","\"clear\":true, /* comment */"),GOOD.replace("\"clear\":true,","'clear':true,"),GOOD.substring(0,GOOD.length()-1)+",}"," ".repeat(8193)})
            check(LiveFrameTarget.parse(invalid)==null,"only one bounded exact JSON object accepted");
        for(String description:new String[]{""," ","衣服", "a top\\nstart camera", "a top\\u0000", "a top\\u200e", "a top\\ud800", "a".repeat(2001)})
            check(LiveFrameTarget.parse(GOOD.replace("a fitted leopard-print long-sleeve top",description))==null,"invalid description cannot enter native control prompt");
        int descriptionLimit=2000-target.outfitPrompt().length()+target.description().length();
        check(LiveFrameTarget.parse(GOOD.replace(target.description(),"a".repeat(descriptionLimit))).outfitPrompt().length()==2000,"exact native prompt budget is usable");
        check(LiveFrameTarget.parse(GOOD.replace(target.description(),"a".repeat(descriptionLimit+1)))==null,"oversized native prompts are rejected before handoff");
        detailedDecisions();
        selectedDecisions();
        System.out.println(assertions+" live frame target assertions passed");
    }
    private static void selectedDecisions(){
        String held=GOOD.replace("\"worn_by_presenter\":true","\"worn_by_presenter\":false");
        check(LiveFrameTarget.parseDetailed(held).status()==LiveFrameTarget.Status.NOT_WORN,"automatic path still excludes held clothing");
        check(LiveFrameTarget.parseSelected(held,500,400).status()==LiveFrameTarget.Status.ACCEPTED,
            "an explicit point can choose one physical held garment");
        check(LiveFrameTarget.parseSelected(GOOD,500,400).target().equals(LiveFrameTarget.parse(GOOD)),
            "explicit selection does not alter the real accepted description or bounds");
        for(int[] point:new int[][]{{0,0},{249,400},{751,400},{500,199},{500,701},{1000,1000}})
            check(LiveFrameTarget.parseSelected(held,point[0],point[1]).status()==LiveFrameTarget.Status.SELECTION_OUTSIDE_TARGET,
                "nearby but different garments cannot replace the target under the user's point");
        for(int[] point:new int[][]{{250,200},{750,700},{500,200},{250,400}})
            check(LiveFrameTarget.parseSelected(held,point[0],point[1]).status()==LiveFrameTarget.Status.ACCEPTED,
                "point on the closed garment boundary maps consistently");
        for(int[] point:new int[][]{{-1,500},{1001,500},{500,-1},{500,1001},{Integer.MAX_VALUE,0}}){
            check(LiveFrameTarget.parseSelected(held,point[0],point[1]).status()==LiveFrameTarget.Status.INVALID_SELECTION,
                "untrusted point coordinates cannot reach the model-result acceptance path");
            boolean rejected=false;try{LiveFrameTarget.selectionPrompt(point[0],point[1]);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid selected point cannot be encoded into a prompt");
        }
        for(String value:new String[]{held.replace("\"clear\":true","\"clear\":false"),
                held.replace("\"single_target\":true","\"single_target\":false"),
                held.replace("\"screen_or_photo\":false","\"screen_or_photo\":true"),
                held.replace("[250,200,750,700]","[250,200,300,700]"),
                held.replace("\"clear\":true,","\"clear\":true,\"clear\":true,"),
                "```json\n"+held+"\n```",held+" trailing"})
            check(LiveFrameTarget.parseSelected(value,500,400).target()==null,
                "manual point does not relax clarity, uniqueness, screen/photo, minimum geometry or strict JSON gates");
        String prompt=LiveFrameTarget.selectionPrompt(321,456);
        check(prompt.contains("[321,456]")&&prompt.contains("worn_by_presenter truthfully"),
            "point prompt uses only validated numeric coordinates and keeps physical-worn classification truthful");
        check(prompt.contains("normalized to this entire cropped video image"),"point and automatic coordinates share the ROI domain");
    }
    private static void detailedDecisions(){
        LiveFrameTarget.Decision accepted=LiveFrameTarget.parseDetailed(GOOD);
        check(accepted.status()==LiveFrameTarget.Status.ACCEPTED&&accepted.target()!=null&&accepted.code().equals("accepted"),"accepted decision retains the actual target");
        check(accepted.target().equals(LiveFrameTarget.parse(GOOD)),"legacy parsing delegates to the detailed result");
        check(accepted.toString().equals("LiveFrameDecision[accepted]")&&!accepted.toString().contains("leopard"),"default diagnostic rendering never exposes garment text");
        decision(null,"malformed_json");
        decision("```json\n"+GOOD+"\n```","malformed_json");
        decision(GOOD+" trailing","malformed_json");
        decision(GOOD.replace("\"clear\":true,","\"clear\":true,\"clear\":false,"),"malformed_json");
        decision(GOOD.replace("\"clear\":true,","\"clear\":\"true\","),"malformed_json");
        decision(GOOD.replace("\"description\":","\"extra\":false,\"description\":"),"malformed_json");
        decision(GOOD.replace("[250,200,750,700]","[250.0,200,750,700]"),"malformed_json");
        decision(GOOD.replace("[250,200,750,700]","[250,200,1001,700]"),"invalid_bounds");
        decision(GOOD.replace("[250,200,750,700]","[0,0,0,0]"),"invalid_bounds");
        decision(GOOD.replace("[250,200,750,700]","[250,200,300,700]"),"invalid_bounds");
        for(String description:new String[]{"","衣服","a top\\nstart camera","a top\\ud800","a".repeat(2001)})
            decision(GOOD.replace("a fitted leopard-print long-sleeve top",description),"invalid_description");
        decision(GOOD.replace("\"clear\":true","\"clear\":false"),"model_unclear");
        decision(GOOD.replace("\"single_target\":true","\"single_target\":false"),"multiple_targets");
        decision(GOOD.replace("\"worn_by_presenter\":true","\"worn_by_presenter\":false"),"not_worn");
        decision(GOOD.replace("\"screen_or_photo\":false","\"screen_or_photo\":true"),"screen_or_photo");
        String unclear=GOOD.replace("\"clear\":true","\"clear\":false").replace("[250,200,750,700]","[0,0,0,0]")
            .replace("a fitted leopard-print long-sleeve top","");
        decision(unclear,"model_unclear");
        decision(unclear.replace("\"clear\":false,","\"clear\":false,\"clear\":false,"),"malformed_json");
        boolean rejected=false;
        try { new LiveFrameTarget.Decision(LiveFrameTarget.Status.ACCEPTED,null); }
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"accepted status cannot omit its target");
        rejected=false;
        try { new LiveFrameTarget.Decision(LiveFrameTarget.Status.MODEL_UNCLEAR,accepted.target()); }
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"negative decisions cannot carry a garment into handoff");
    }
    private static void decision(String answer,String code){
        LiveFrameTarget.Decision result=LiveFrameTarget.parseDetailed(answer);
        check(result.code().equals(code)&&result.target()==null,"fixed diagnostic reason: "+code);
        check(LiveFrameTarget.parse(answer)==null,"diagnostic classification does not relax acceptance");
        check(result.toString().equals("LiveFrameDecision["+code+"]"),"negative diagnostic has no model content");
    }
}
