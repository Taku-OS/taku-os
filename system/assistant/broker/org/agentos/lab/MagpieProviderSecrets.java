package org.agentos.lab;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Splits provider credentials from values that can be published in Settings.Global. */
final class MagpieProviderSecrets {
 static final String PRIVATE_VALUES="takuPrivateValues";
 static final String[] SECRET_FIELDS={"key","keys","accessKeyID","secretAccessKey","headers","balanceToken"};
 static boolean secretField(String key){
  String k=key.replaceAll("[^A-Za-z0-9]","").toLowerCase(java.util.Locale.ROOT);
  return k.equals("key")||k.equals("keys")||k.equals("apikey")||k.equals("apikeys")||k.equals("accesskeyid")||k.equals("secretaccesskey")||k.equals("headers")||k.equals("balancetoken")||k.equals("password")||k.equals("passwd")||k.equals("token")||k.equals("accesstoken")||k.equals("refreshtoken")||k.equals("authorization")||k.equals("credentials")||k.equals("clientsecret");
 }
 static String publicUrl(String value,String path)throws Exception{
  if(!value.contains("://")){
   String lower=path.toLowerCase(java.util.Locale.ROOT);
   if((lower.contains("proxy")||lower.contains("proxies"))&&value.contains("@"))return publicUrl("http://"+value,path).substring(7);
   return value;
  }
  URI uri;
  try{uri=new URI(value);}catch(Exception e){throw new IllegalArgumentException("invalid provider URL",e);}
  String authority=uri.getRawAuthority();
  if(authority==null)throw new IllegalArgumentException("invalid provider URL authority");
  String result=value;
  int start=value.indexOf("://")+3,at=authority.lastIndexOf('@');
  if(at>=0)result=value.substring(0,start)+value.substring(start+at+1);
  int query=result.indexOf('?'),fragment=result.indexOf('#');
  int end=query<0?fragment:fragment<0?query:Math.min(query,fragment);
  return end<0?result:result.substring(0,end);
 }
 static String path(String parent,String key){return parent+"/"+key.replace("~","~0").replace("/","~1");}
 static void split(JSONObject provider,JSONObject secret)throws Exception{
  JSONObject values=secret.optJSONObject(PRIVATE_VALUES);if(values==null)values=new JSONObject();
  splitObject(provider,secret,values,"");
  if(values.length()>0)secret.put(PRIVATE_VALUES,values);
 }
 static void splitObject(JSONObject object,JSONObject secret,JSONObject values,String parent)throws Exception{
  ArrayList<String> keys=new ArrayList<>();for(Iterator<String> it=object.keys();it.hasNext();)keys.add(it.next());
  for(String key:keys){
   Object value=object.get(key);String p=path(parent,key);
   if(secretField(key)){
    object.remove(key);
    if(parent.isEmpty())secret.put(key,value);else values.put(p,new JSONObject().put("private",value));
   }else if(value instanceof JSONObject)splitObject((JSONObject)value,secret,values,p);
   else if(value instanceof JSONArray)splitArray((JSONArray)value,secret,values,p);
   else if(value instanceof String){String safe=publicUrl((String)value,p);if(!safe.equals(value)){values.put(p,new JSONObject().put("public",safe).put("private",value));object.put(key,safe);}}
  }
 }
 static void splitArray(JSONArray array,JSONObject secret,JSONObject values,String parent)throws Exception{
  for(int i=0;i<array.length();i++){Object value=array.get(i);String p=path(parent,Integer.toString(i));
   if(value instanceof JSONObject)splitObject((JSONObject)value,secret,values,p);
   else if(value instanceof JSONArray)splitArray((JSONArray)value,secret,values,p);
   else if(value instanceof String){String safe=publicUrl((String)value,p);if(!safe.equals(value)){values.put(p,new JSONObject().put("public",safe).put("private",value));array.put(i,safe);}}
  }
 }
 static void restore(JSONObject provider,JSONObject secret)throws Exception{
  for(String field:SECRET_FIELDS)if(secret.has(field))provider.put(field,secret.get(field));
  // Other recognized top-level credential names are kept in the same private store.
  for(Iterator<String> it=secret.keys();it.hasNext();){String key=it.next();if(secretField(key))provider.put(key,secret.get(key));}
  JSONObject values=secret.optJSONObject(PRIVATE_VALUES);if(values==null)return;
  for(Iterator<String> it=values.keys();it.hasNext();){String p=it.next();JSONObject entry=values.getJSONObject(p);String[] parts=p.substring(1).split("/",-1);Object current=provider;
   for(int i=0;i<parts.length-1&&current!=null;i++)current=child(current,unescape(parts[i]));
   if(current==null)continue;String key=unescape(parts[parts.length-1]);Object existing=child(current,key);
   if(entry.has("public")?!entry.get("public").equals(existing):existing!=null)continue;
   if(current instanceof JSONObject)((JSONObject)current).put(key,entry.get("private"));
   else if(current instanceof JSONArray){try{int index=Integer.parseInt(key);if(index>=0&&index<((JSONArray)current).length())((JSONArray)current).put(index,entry.get("private"));}catch(NumberFormatException ignored){}}
  }
 }
 static String unescape(String value){return value.replace("~1","/").replace("~0","~");}
 static Object child(Object parent,String key){if(parent instanceof JSONObject)return ((JSONObject)parent).opt(key);if(parent instanceof JSONArray)try{return ((JSONArray)parent).opt(Integer.parseInt(key));}catch(NumberFormatException ignored){}return null;}
}
