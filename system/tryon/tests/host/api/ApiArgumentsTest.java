// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.api;
import java.util.Map;
public final class ApiArgumentsTest {
    private static int checks;
    public static void main(String[] args){
        Map<String,Object> values=ApiArguments.parse("{\"mode\":\"character\",\"referenceUri\":\"content://org.taku.tryon.api/reference/current\",\"referenceExpires\":0,\"prompt\":\"a:b\\n\\u89d2\\u8272\"}");
        check("character".equals(values.get("mode")));check("content://org.taku.tryon.api/reference/current".equals(values.get("referenceUri")));
        check(Long.valueOf(0).equals(values.get("referenceExpires")));check("a:b\n角色".equals(values.get("prompt")));
        check(ApiArguments.parse(" \n{}\t").isEmpty());
        check(Long.valueOf(Long.MAX_VALUE).equals(ApiArguments.parse("{\"referenceExpires\":9223372036854775807}").get("referenceExpires")));
        check(Long.valueOf(Long.MIN_VALUE).equals(ApiArguments.parse("{\"referenceExpires\":-9223372036854775808}").get("referenceExpires")));
        for(String bad:new String[]{"", "[]", "null", "{} {}", "{\"mode\":\"a\",\"mode\":\"b\"}","{mode:'a'}","{\"mode\":\"a\",}",
                "{\"unknown\":\"a\"}","{\"mode\":null}","{\"mode\":true}","{\"mode\":1}","{\"mode\":{}}","{\"prompt\":[]}",
                "{\"referenceExpires\":1.0}","{\"referenceExpires\":1e2}","{\"referenceExpires\":\"1\"}","{\"referenceExpires\":01}",
                "{\"referenceExpires\":+1}","{\"referenceExpires\":-}","{\"referenceExpires\":9223372036854775808}",
                "{\"prompt\":\"bad\nline\"}","{\"prompt\":\"\\x10\"}","{\"prompt\":\"\\uZZZZ\"}","{\"mode\":\"a\"/*c*/}"})reject(bad);
        reject("{\"prompt\":\""+"a".repeat(32768)+"\"}");
        reject("{\"prompt\":\""+"角".repeat(11000)+"\"}");
        String exact="{\"prompt\":\""+"a".repeat(32755)+"\"}";check(exact.length()==32768);check(ApiArguments.parse(exact).get("prompt") instanceof String);
        System.out.println("ApiArgumentsTest: "+checks+" strict JSON/URI/type/boundary checks passed; no Android/model");
    }
    private static void reject(String value){boolean rejected=false;try{ApiArguments.parse(value);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);}
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
}
