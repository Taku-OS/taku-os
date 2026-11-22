// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.api;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict, bounded CLI object syntax. Nested values, duplicate keys and coercions are forbidden. */
public final class ApiArguments {
    private static final int MAX_BYTES=32*1024;
    private static final Set<String> FIELDS=Set.of("mode","provider","region","referenceUri","prompt","referenceExpires","x","y");
    private final String source;
    private int at;
    private ApiArguments(String source){this.source=source;}
    public static Map<String,Object> parse(String text){
        if(text==null||text.length()>MAX_BYTES||text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid();
        return new ApiArguments(text).object();
    }
    private Map<String,Object> object(){
        Map<String,Object> result=new LinkedHashMap<>();space();expect('{');space();
        if(take('}')){end();return result;}
        while(true){
            space();String key=string();
            if(!FIELDS.contains(key)||result.containsKey(key))throw invalid();
            space();expect(':');space();
            Object value=("referenceExpires".equals(key)||"x".equals(key)||"y".equals(key))?integer():string();result.put(key,value);space();
            if(take('}')){end();return result;}
            expect(',');
        }
    }
    private String string(){
        expect('"');StringBuilder out=new StringBuilder();
        while(at<source.length()){
            char c=source.charAt(at++);
            if(c=='"')return out.toString();
            if(c<0x20)throw invalid();
            if(c!='\\'){out.append(c);continue;}
            if(at>=source.length())throw invalid();
            char escaped=source.charAt(at++);
            switch(escaped){
                case '"':case '\\':case '/':out.append(escaped);break;
                case 'b':out.append('\b');break;case 'f':out.append('\f');break;
                case 'n':out.append('\n');break;case 'r':out.append('\r');break;case 't':out.append('\t');break;
                case 'u':
                    int code=0;
                    for(int n=0;n<4;n++){
                        if(at>=source.length())throw invalid();char hex=source.charAt(at++);
                        int digit=hex>='0'&&hex<='9'?hex-'0':hex>='a'&&hex<='f'?hex-'a'+10:hex>='A'&&hex<='F'?hex-'A'+10:-1;
                        if(digit<0)throw invalid();code=code*16+digit;
                    }
                    out.append((char)code);break;
                default:throw invalid();
            }
        }
        throw invalid();
    }
    private Long integer(){
        int start=at;take('-');
        if(at>=source.length())throw invalid();
        if(!take('0')){
            if(source.charAt(at)<'1'||source.charAt(at)>'9')throw invalid();
            while(at<source.length()&&source.charAt(at)>='0'&&source.charAt(at)<='9')at++;
        }
        try{return Long.valueOf(source.substring(start,at));}catch(NumberFormatException invalid){throw invalid();}
    }
    private void space(){while(at<source.length()){char c=source.charAt(at);if(c!=' '&&c!='\t'&&c!='\r'&&c!='\n')break;at++;}}
    private boolean take(char c){if(at<source.length()&&source.charAt(at)==c){at++;return true;}return false;}
    private void expect(char c){if(!take(c))throw invalid();}
    private void end(){space();if(at!=source.length())throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid_arguments");}
}
