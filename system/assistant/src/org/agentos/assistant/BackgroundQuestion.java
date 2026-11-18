package org.agentos.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.agentos.surfaces.SurfaceSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure presentation of a pending phone question; rendering or dismissal never answers it. */
final class BackgroundQuestion {
    private final String method,title,summary;
    private final List<SurfaceSpec.Action> actions;
    private final List<SurfaceSpec.Block> blocks;

    static boolean supports(String method) {
        return Set.of("phone/askUser","phone/confirmAction","phone/action/requestApproval").contains(method);
    }

    static BackgroundQuestion fromPending(String method,JSONObject params,Locale locale) throws Exception {
        if(!supports(method))throw new IllegalArgumentException("Unsupported phone question");
        boolean chinese=locale!=null && locale.getLanguage().equals("zh");
        JSONObject args;
        if(method.equals("phone/action/requestApproval")) {
            String reason=params.optString("reason");
            if(reason.isBlank())reason=chinese?"允许这次后台操作吗？":"Allow this background action?";
            if(reason.length()>160)reason=reason.substring(0,159)+"…";
            args=new JSONObject().put("question",reason).put("options",new JSONArray()
                .put(chinese?"允许这一次":"Allow once").put(chinese?"不允许":"Don't allow"));
        } else args=params.getJSONObject("arguments");
        return new BackgroundQuestion(method,args,chinese);
    }

    private BackgroundQuestion(String method,JSONObject args,boolean chinese) throws Exception {
        this.method=method;
        List<SurfaceSpec.Action> nextActions=new ArrayList<>();
        List<SurfaceSpec.Block> nextBlocks=new ArrayList<>();
        if(method.equals("phone/confirmAction")) {
            title=text(args,"title",120);summary=text(args,"summary",300);
            String confirmLabel=text(args,"confirmLabel",40);
            JSONArray fields=args.getJSONArray("fields");
            if(fields.length()<1 || fields.length()>8)throw new IllegalArgumentException("Invalid confirmation field count");
            for(int i=0;i<fields.length();i++) {
                JSONObject field=fields.getJSONObject(i);
                String style=text(field,"style",10);
                if(!Set.of("field","fact","notice").contains(style))throw new IllegalArgumentException("Invalid confirmation field style");
                nextBlocks.add(new SurfaceSpec.Block(style,text(field,"label",80),text(field,"value",500)));
            }
            nextActions.add(new SurfaceSpec.Action("answer-0",confirmLabel,true));
            nextActions.add(new SurfaceSpec.Action("answer-1",chinese?"修改信息":"Edit details",false));
            nextActions.add(new SurfaceSpec.Action("answer-2",chinese?"暂不操作":"Not now",false));
        } else {
            title=text(args,"question",160);summary=chinese?"选择后继续在后台处理":"Choose an option to continue in the background";
            JSONArray options=args.getJSONArray("options");
            if(options.length()<1 || options.length()>3)throw new IllegalArgumentException("Invalid question options");
            for(int i=0;i<options.length();i++) {
                Object option=options.get(i);
                if(!(option instanceof String) || ((String)option).isBlank() || ((String)option).length()>80)
                    throw new IllegalArgumentException("Invalid question option");
                nextActions.add(new SurfaceSpec.Action("answer-"+i,(String)option,i==0));
            }
        }
        actions=List.copyOf(nextActions);blocks=List.copyOf(nextBlocks);
    }

    SurfaceSpec surface(String id,long revision) {
        return SurfaceSpec.choice(id,revision,title,summary,actions).withBlocks(blocks)
            .withPlacement("bottom").withInitialState("expanded");
    }

    JSONObject response(String actionId,long eventRevision,long shownRevision) throws Exception {
        if(eventRevision!=shownRevision || actionId==null || !actionId.matches("answer-[0-2]"))return null;
        int index=actionId.charAt(7)-'0';
        if(index>=actions.size())return null;
        if(method.equals("phone/askUser"))return new JSONObject().put("optionIndex",index);
        if(method.equals("phone/confirmAction"))return new JSONObject().put("decision",List.of("confirm","modify","cancel").get(index));
        return new JSONObject().put("decision",index==0?"accept":"decline");
    }

    private static String text(JSONObject object,String key,int maximum) throws Exception {
        Object raw=object.get(key);
        if(!(raw instanceof String) || ((String)raw).isBlank() || ((String)raw).length()>maximum)
            throw new IllegalArgumentException("Invalid question "+key);
        return (String)raw;
    }
}
