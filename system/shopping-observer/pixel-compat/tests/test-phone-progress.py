#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Actual phone factory + main progress callbacks + fixed diagnostics. No device/model work."""
from pathlib import Path
import ast, os, subprocess, tempfile

here = Path(__file__).resolve().parents[1]
root = here.parents[2]
module = root / 'system/shopping-observer'
tree = ast.parse((module / 'tests/test-live-progress-host.py').read_text())
values = {}
for item in tree.body:
    if isinstance(item, ast.Assign):
        for name in item.targets:
            if isinstance(name, ast.Name) and name.id in ('stubs', 'main_test'):
                values[name.id] = ast.literal_eval(item.value)
stubs = values['stubs']
for name in ('org/json/JSONObject.java', 'org/json/JSONArray.java'):
    del stubs[name]
stubs.update({
'android/os/Message.java': '''package android.os; public final class Message {public int what,sendingUid;private final Bundle data=new Bundle();public Bundle getData(){return data;}}''',
'android/os/Bundle.java': '''package android.os; public final class Bundle {public String getString(String key,String fallback){return fallback;}}''',
'android/util/Log.java': '''package android.util; public final class Log {public static final java.util.List<String> lines=new java.util.ArrayList<>();public static int i(String tag,String line){lines.add(line);return 0;}}''',
'org/agentos/proactivity/api/ProactivityWire.java': '''package org.agentos.proactivity.api; public final class ProactivityWire {public static final int MAX_BYTES=65536;}''',
'org/agentos/shopping/LiveFrameProgressTest.java': values['main_test'],
'org/agentos/shopping/PixelSystemProgressTest.java': r'''package org.agentos.shopping;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.util.*;
import org.agentos.proactivity.api.ObserverLinkDiagnostic;
import org.agentos.proactivity.api.ProactivityClient;
import org.json.JSONObject;
import org.taku.tryon.common.IslandPublisher;

public final class PixelSystemProgressTest {
 static int checks,cancelled,retried,selected;
 static final String TOKEN="A".repeat(43), ID="00112233-4455-6677-8899-aabbccddeeff";
 static final PixelProgressHost.Factory factory=new PixelProgressHost.Factory();
 static JSONObject receipt(){return new JSONObject().put("presentationToken",TOKEN).put("presentationId",ID).put("presentationExpiresAt",100000L);}
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void invalid(JSONObject receipt){boolean failed=false;try{factory.session(receipt);}catch(IllegalArgumentException expected){failed=true;}check(failed,"partial/invalid capability stays rejected");}
 static JSONObject event(long revision,String action){return new JSONObject().put("ok",true).put("type","presentation_action")
   .put("result",new JSONObject().put("presentationId",ID).put("revision",revision).put("actionId",action));}
 static String log(JSONObject receipt){ObserverLinkDiagnostic.receipt("client_receipt",receipt,false);return Log.lines.get(Log.lines.size()-1);}
 public static void main(String[] ignored){
  check(factory.session(new JSONObject())==null,"missing optional lease stays optional");
  invalid(new JSONObject().put("presentationToken",TOKEN));
  invalid(new JSONObject().put("presentationId",ID));
  invalid(new JSONObject().put("presentationExpiresAt",100000L));
  invalid(new JSONObject().put("presentationToken",TOKEN).put("presentationId",ID));
  invalid(receipt().put("presentationToken","bad"));invalid(receipt().put("presentationId","bad"));
  invalid(receipt().put("presentationExpiresAt",999));invalid(receipt().put("presentationExpiresAt","100000"));
  invalid(receipt().put("presentationExpiresAt",100000.5));
  LiveFrameProgressHost.Session session=factory.session(receipt());
  check(!session.toString().contains(TOKEN)&&!session.toString().contains(ID),"opaque session never exposes received capability");
  check(ProactivityClient.clients.isEmpty(),"parsing a received lease does not bind a presentation client");
  LiveFrameProgressHost.installFactory(factory);Context context=new Context();
  LiveFrameProgress progress=new LiveFrameProgress(context,session,()->cancelled++);progress.show();
  ProactivityClient client=ProactivityClient.clients.get(0);
  check(IslandPublisher.posts.isEmpty()&&client.requests.size()==1,"received lease uses original system card host rather than a notification");
  JSONObject wire=client.requests.get(0).arguments();
  check(wire.optString("status").equals("working")&&wire.optString("title").equals("Matching…"),"matching uses the host working island");
  progress.showFailure("Choose the outfit",()->retried++,()->selected++);
  wire=client.requests.get(1).arguments();
  check(!wire.has("status")&&wire.getJSONArray("actions").length()==2,"selection uses expanded needs-action card with original two buttons");
  check(wire.getJSONArray("actions").getJSONObject(0).getString("id").equals("select"),"Select outfit is immediately available on the system card");
  client.listener.onMessage(event(1,"cancel"));check(cancelled==0,"old matching callback cannot cancel the replacement selection card");
  client.listener.onMessage(event(2,"select"));client.listener.onMessage(event(2,"select"));
  check(selected==1&&retried==0,"system card selection is one-use through main's dispatcher");
  progress.showFailure("Try again",()->retried++,null);progress.setRetryEnabled(false);
  client.listener.onMessage(event(3,"retry"));check(retried==0,"disabled retry rejects an older card revision");
  progress.close();check(client.closed&&ObserverActions.latest.isEmpty(),"close dismisses the system card and leaves no notification callbacks");
  boolean[] live={true};progress=new LiveFrameProgress(context,session,()->{cancelled++;live[0]=false;});progress.show();
  ProactivityClient failed=ProactivityClient.clients.get(1);
  failed.listener.onMessage(new JSONObject().put("type","present_action").put("requestId",failed.requests.get(0).id()).put("ok",false));
  check(!live[0]&&cancelled==1,"host publication rejection cancels the existing attempt");progress.close();
  String flat=log(new JSONObject().put("type","present_action").put("ok",false).put("code","not_authorized").put("message",TOKEN).put("presentationToken",TOKEN));
  check(flat.contains("code=not_authorized")&&!flat.contains(TOKEN),"legacy flat fixed error code is visible without message/capability");
  String nested=log(new JSONObject().put("type","present_action").put("ok",false).put("code","rate_limited")
    .put("error",new JSONObject().put("code","invalid_or_unavailable").put("message",TOKEN)));
  check(nested.contains("code=invalid_or_unavailable"),"nested current error code takes precedence");
  check(log(new JSONObject().put("code","rate_limited").put("error",new JSONObject().put("code",JSONObject.NULL))).contains("code=rate_limited"),"explicit null nested code falls back to flat code");
  check(log(new JSONObject().put("code",TOKEN)).contains("code=absent_or_other")&&!Log.lines.get(Log.lines.size()-1).contains(TOKEN),"arbitrary alphabetic code cannot leak a token");
  check(log(new JSONObject().put("code",7)).contains("code=absent_or_other"),"invalid error types stay fixed metadata");
  check(log(new JSONObject()).contains("code=none"),"successful receipts without code stay explicit");
  SystemClock.now=100001;invalid(receipt());
  check(ProactivityClient.clients.size()==2,"invalid expired lease cannot create another host client");
  System.out.println("Phone system card/fixed diagnostic checks: "+checks);
 }
}'''
})
json_jar = module / 'out/test-deps/json-20240303.jar'
home = os.environ.get('JAVA_HOME')
javac = str(Path(home) / 'bin/javac') if home else 'javac'
java = str(Path(home) / 'bin/java') if home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-phone-progress-') as temporary:
    target = Path(temporary)
    files = []
    for name, source in stubs.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        files.append(str(path))
    files.extend(str(module / 'src/org/agentos/shopping' / name) for name in ('LiveFrameProgress.java', 'LiveFrameProgressHost.java'))
    files.extend(str(here / 'src' / name) for name in ('org/agentos/shopping/PixelProgressHost.java', 'org/agentos/proactivity/api/ObserverLinkDiagnostic.java'))
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', str(json_jar), '-d', temporary, *files], check=True)
    classpath = temporary + os.pathsep + str(json_jar)
    for name in ('LiveFrameProgressTest', 'PixelSystemProgressTest'):
        subprocess.run([java, '-cp', classpath, 'org.agentos.shopping.' + name], check=True, timeout=15)
