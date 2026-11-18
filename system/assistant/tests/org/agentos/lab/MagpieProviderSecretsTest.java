package org.agentos.lab;

import org.json.JSONObject;

public final class MagpieProviderSecretsTest {
 static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
 public static void main(String[] args)throws Exception{
  JSONObject p=new JSONObject("{\"id\":\"test\",\"key\":\"key-secret\",\"balanceToken\":\"balance-secret\",\"headers\":{\"Authorization\":\"header-secret\"},\"proxy\":\"http://user:password@proxy.example:8080\",\"accountProxies\":{\"a\":\"socks5://user:pass@p.example:1080\"},\"chat\":\"https://api.example/v1?token=query-secret\",\"vertex\":{\"project\":\"fixture\",\"credentials\":\"vertex-secret\"}}"), secret=new JSONObject();
  String original=p.toString();MagpieProviderSecrets.split(p,secret);String published=p.toString();
  for(String value:new String[]{"key-secret","balance-secret","header-secret","user:password","user:pass","query-secret","vertex-secret"})check(!published.contains(value),"published secret");
  check(p.getString("proxy").equals("http://proxy.example:8080"),"public proxy");
  check(p.getJSONObject("accountProxies").getString("a").equals("socks5://p.example:1080"),"nested proxy");
  check(p.getString("chat").equals("https://api.example/v1"),"public URL");
  MagpieProviderSecrets.restore(p,secret);check(p.similar(new JSONObject(original)),"restored runtime provider");
  JSONObject changed=new JSONObject("{\"proxy\":\"http://other.example:8080\",\"vertex\":{\"credentials\":\"new\"}}");
  MagpieProviderSecrets.restore(changed,secret);check(changed.getString("proxy").equals("http://other.example:8080"),"do not apply stale proxy password to a different host");
  check(changed.getJSONObject("vertex").getString("credentials").equals("new"),"do not replace changed nested field");
  JSONObject bare=new JSONObject("{\"proxy\":\"user:bare-password@proxy.example:8080\",\"accountProxies\":{\"a\":\"user:other-password@proxy.example:8080\"}}");JSONObject bareSecret=new JSONObject();MagpieProviderSecrets.split(bare,bareSecret);check(!bare.toString().contains("password"),"schemeless proxy credentials hidden");check(bare.getString("proxy").equals("proxy.example:8080"),"schemeless proxy address");MagpieProviderSecrets.restore(bare,bareSecret);check(bare.getString("proxy").contains("bare-password"),"schemeless proxy restored");
  boolean failed=false;try{MagpieProviderSecrets.split(new JSONObject("{\"proxy\":\"http://user:bad password@proxy\"}"),new JSONObject());}catch(IllegalArgumentException e){failed=true;}
  check(failed,"invalid URL fails closed");
  System.out.println("Magpie provider secret tests passed");
 }
}
