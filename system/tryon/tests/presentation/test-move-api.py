#!/usr/bin/env python3
"""Actual Provider carrier/admission and Controller move bodies; no device/model."""
from pathlib import Path
import os,re,subprocess,tempfile

MODULE=Path(__file__).resolve().parents[2]
provider=(MODULE/'app/src/org/taku/tryon/api/TryOnApiProvider.java').read_text()
controller=(MODULE/'app/src/org/taku/tryon/realtime/TryOnController.java').read_text()

def member(source,signature):
    start=source.index(signature)
    opening=source.index('{',start);at=opening+1;depth=1
    while depth:
        depth+=(source[at]=='{')-(source[at]=='}');at+=1
    return source[start:at]

fixture=r'''package org.taku.tryon.api;
import java.util.*;
public final class TryOnApiProvider extends ProviderBase {
 private void enforce(){AgentAccess.enforce(getContext(),Binder.getCallingUid());}
'''+member(provider,'public Bundle call(')+r'''
 static int checks;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static Bundle point(Object x,Object y){Bundle b=new Bundle();b.values.put("x",x);b.values.put("y",y);return b;}
 static void reject(TryOnApiProvider p,String method,String json,Bundle extras){int before=CallTryOnService.moves;Bundle b=p.call(method,json,extras);check(!b.getBoolean("ok")&&b.getString("code").equals("invalid_arguments")&&CallTryOnService.moves==before,"invalid carrier/fields cannot mutate: "+method+" "+json);}
 public static void main(String[] ignored){
  TryOnApiProvider p=new TryOnApiProvider();
  check(p.call("move",null,point(12,34L)).getBoolean("ok")&&CallTryOnService.moves==1&&CallTryOnService.x==12&&CallTryOnService.y==34,"integer extras route through the authoritative Controller");
  check(p.call("move","{\"x\":-2147483648,\"y\":2147483647}",null).getBoolean("ok")&&CallTryOnService.x==Integer.MIN_VALUE&&CallTryOnService.y==Integer.MAX_VALUE,"strict JSON preserves int32 boundaries without coercion");
  reject(p,"move","{\"x\":1,\"y\":2}",point(1,2));
  for(String json:new String[]{"{}","{\"x\":1}","{\"x\":1,\"y\":2,\"mode\":\"character\"}","{\"x\":1.5,\"y\":2}","{\"x\":\"1\",\"y\":2}","{\"x\":1,\"y\":true}","{\"x\":1,\"y\":null}","{\"x\":2147483648,\"y\":2}","{\"x\":1,\"y\":-2147483649}","{\"x\":1,\"x\":2,\"y\":3}"})reject(p,"move",json,null);
  reject(p,"move",null,null);Bundle missing=new Bundle();missing.putLong("x",1);reject(p,"move",null,missing);
  Bundle extra=point(1,2);extra.putString("extra","value");reject(p,"move",null,extra);
  for(Object bad:new Object[]{null,"1",true,1.0,Float.NaN,Short.valueOf((short)1),2147483648L})reject(p,"move",null,point(bad,2));
  for(String method:new String[]{"configure","draft","start","clearCredential","minimize","expand","fullscreen","status"})reject(p,method,"{\"x\":1,\"y\":2}",null);
  Bundle describe=p.call("describe",null,null);check(describe.getString("methods").contains(",move,")&&describe.getString("moveArguments").contains("canMove"),"describe makes the operation and geometry discoverable");
  CallTryOnService.accepted=false;Bundle unavailable=p.call("move",null,point(10,20));check(!unavailable.getBoolean("ok")&&unavailable.getString("code").equals("presentation_unavailable"),"closed/nonmovable window cannot claim successful movement");
  int previous=CallTryOnService.moves;AgentAccess.allowed=false;boolean denied=false;try{p.call("move",null,point(10,20));}catch(SecurityException expected){denied=true;}
  check(denied&&CallTryOnService.moves==previous&&AgentAccess.calls>0,"same provider permission enforcement precedes move validation/mutation");
  System.out.println("Provider move carrier/admission checks: "+checks);
 }
}
class ProviderBase {Object getContext(){return this;}String getCallingPackage(){return "test";}}
class Binder {static int getCallingUid(){return 12001;}}
class AgentAccess {static boolean allowed=true;static int calls;static void enforce(Object c,int uid){calls++;if(!allowed)throw new SecurityException("permission_denied");}}
class Bundle {final Map<String,Object> values=new HashMap<>();boolean isEmpty(){return values.isEmpty();}Set<String> keySet(){return values.keySet();}Object get(String key){return values.get(key);}void putString(String key,String v){values.put(key,v);}void putLong(String key,long v){values.put(key,v);}void putBoolean(String key,boolean v){values.put(key,v);}String getString(String key){return (String)values.get(key);}String getString(String key,String fallback){return (String)values.getOrDefault(key,fallback);}boolean getBoolean(String key){return Boolean.TRUE.equals(values.get(key));}}
class CallTryOnService {static int moves,x,y;static boolean accepted=true;static boolean move(int px,int py){moves++;if(!accepted)return false;x=px;y=py;return true;}}
class TryOnController {
'''+ '\n'.join(member(controller,signature) for signature in ['public static Bundle move(','private static int moveCoordinate(','public static boolean validArguments(','public static Bundle result('])+r'''
 static Bundle unexpected(){throw new AssertionError("Unexpected unrelated operation");}
 static Bundle status(Object c){return unexpected();}static Bundle diagnostics(Object c){return unexpected();}static Bundle draft(Object c,Bundle args){return unexpected();}static Bundle configure(Object c,Bundle args){return unexpected();}static Bundle start(Object c,Bundle args){return unexpected();}static Bundle retry(Object c){return unexpected();}static Bundle stop(Object c){return unexpected();}static Bundle presentation(String s){return unexpected();}static Bundle credential(Object c,String p,String k,boolean clear){return unexpected();}
}
'''
# The real Provider uses the legacy gateway's fully-qualified name. The test only
# replaces that unrelated delegation type; move still runs the real call body.
fixture=fixture.replace('org.taku.tryon.realtime.LegacyCosplayGateway','LegacyCosplayGateway')
fixture+='\nclass LegacyCosplayGateway {static boolean handles(String method){return false;}static Bundle call(Object c,String m,String a,Bundle e,String p){throw new AssertionError("Unexpected legacy route");}}\n'
jdk=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'))
with tempfile.TemporaryDirectory(prefix='taku-move-api-') as temporary:
    target=Path(temporary)/'TryOnApiProvider.java';target.write_text(fixture)
    subprocess.run([str(jdk/'bin/javac'),'--release','17','-d',temporary,str(target),str(MODULE/'app/src/org/taku/tryon/api/ApiArguments.java')],check=True)
    subprocess.run([str(jdk/'bin/java'),'-cp',temporary,'org.taku.tryon.api.TryOnApiProvider'],check=True,timeout=10)
