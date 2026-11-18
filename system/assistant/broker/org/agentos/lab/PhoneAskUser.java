package org.agentos.lab;
import org.json.*;

/** Bounded choice questions; a reply must identify an actual option from the pending call. */
final class PhoneAskUser {
    static JSONObject validate(JSONObject arguments) throws Exception {
        String question=arguments.getString("question");
        JSONArray options=arguments.getJSONArray("options");
        if(question.isBlank() || question.length()>160 || options.length()<1 || options.length()>3)
            throw new IllegalArgumentException("Question or options exceed the allowed range");
        for(int i=0;i<options.length();i++) {
            Object value=options.get(i);
            if(!(value instanceof String) || ((String)value).isBlank() || ((String)value).length()>80)
                throw new IllegalArgumentException("Invalid option format");
        }
        return arguments;
    }
    static JSONObject answer(JSONObject arguments,JSONObject response) throws Exception {
        validate(arguments);
        Object raw=response.get("optionIndex");
        if(!(raw instanceof Integer)) throw new IllegalArgumentException("Select an option that is actually displayed");
        int index=(Integer)raw;
        if(index<0 || index>=arguments.getJSONArray("options").length())
            throw new IllegalArgumentException("This option is no longer available");
        return new JSONObject().put("question",arguments.getString("question"))
            .put("answer",arguments.getJSONArray("options").getString(index)).put("optionIndex",index)
            .put("userProvided",true);
    }
}
