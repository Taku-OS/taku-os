package org.agentos.lab;
import org.json.JSONObject;
public final class ImeReplyErrorTest {
 static int checks;
 static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static String body(String message)throws Exception{return new JSONObject().put("error",new JSONObject().put("message",message)).toString();}
 public static void main(String[] args)throws Exception{
  ImeReplyError expired=ImeReplyError.http(502,body("Codex: Codex is signed out (token refresh failed); run codex login"));
  check(expired.code.equals("auth_required"));check(expired.getMessage().contains("reconnect"));
  check(ImeReplyError.http(502,body("Codex token refresh failed (HTTP 401)")).code.equals("auth_required"));
  check(ImeReplyError.http(401,"").code.equals("auth_required"));
  check(ImeReplyError.http(429,body("quota")).code.equals("quota"));
  check(ImeReplyError.http(403,"<html>proxy error</html>").code.equals("unavailable"));
  check(ImeReplyError.http(502,body("network timeout")).code.equals("unavailable"));
  check(ImeReplyError.http(502,"bad JSON").code.equals("unavailable"));
  check(ImeReplyError.http(502,body("signed out of unrelated service")).code.equals("unavailable"));
  String secret="sensitive-test-only-value";
  check(!ImeReplyError.http(502,body("Codex signed out "+secret)).getMessage().contains(secret));
  check(!ImeReplyError.http(502,body(secret)).getMessage().contains(secret));
  check(ImeReplyError.http(502,"x".repeat(17000)).code.equals("unavailable"));
  System.out.println("PASS ImeReplyErrorTest: "+checks+" checks");
 }
}
