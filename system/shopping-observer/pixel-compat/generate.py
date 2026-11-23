#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import xml.etree.ElementTree as ET

import os
SOURCE=Path(__file__).resolve().parent
ROOT=SOURCE.parents[2]
HERE=ROOT/"out/shopping-observer-pixel-compat"
HERE.mkdir(parents=True,exist_ok=True)
ns='{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android',ns[1:-1])
tree=ET.parse(ROOT/'system/shopping-observer/AndroidManifest.xml')
tree.getroot().set(ns+'versionCode',os.environ.get('TAKU_OBSERVER_VERSION_CODE',tree.getroot().get(ns+'versionCode')))
tree.getroot().set(ns+'versionName','0.13.0-pixel-delivery-preview')
tree.getroot().find('application').set(ns+'name','org.agentos.shopping.PixelObserverApplication')
tree.write(HERE/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)

def replace_once(text,old,new):
    assert text.count(old)==1, old[:100]
    return text.replace(old,new)

client=(ROOT/'system/proactivity/client/org/agentos/proactivity/api/ProactivityClient.java').read_text()
client=replace_once(client,'remote=new Messenger(binder);listener.onConnected();','remote=new Messenger(binder);ObserverLinkDiagnostic.stage("client_connected");listener.onConnected();')
client=replace_once(client,'public void onServiceDisconnected(ComponentName name) {remote=null;listener.onDisconnected();}',
    'public void onServiceDisconnected(ComponentName name) {ObserverLinkDiagnostic.stage("client_disconnected");remote=null;listener.onDisconnected();}')
client=replace_once(client,'public void onBindingDied(ComponentName name) {close();listener.onDisconnected();}',
    'public void onBindingDied(ComponentName name) {ObserverLinkDiagnostic.stage("client_binding_died");close();listener.onDisconnected();}')
client=replace_once(client,'public void onNullBinding(ComponentName name) {close();listener.onDisconnected();}',
    'public void onNullBinding(ComponentName name) {ObserverLinkDiagnostic.stage("client_null_binding");close();listener.onDisconnected();}')
client=replace_once(client,'reply=new Messenger(new Handler(Looper.getMainLooper(),message->{',
    'reply=new Messenger(new Handler(Looper.getMainLooper(),message->{\n            ObserverLinkDiagnostic.reply(message,hostUid);')
client=replace_once(client,'}catch(Exception ignored){}return true;',
    '}catch(Exception ignored){ObserverLinkDiagnostic.failure("client_callback",ignored);}return true;')
client=replace_once(client,'main();if(bound)return;','main();ObserverLinkDiagnostic.stage("client_connect");if(bound)return;')
client=replace_once(client,'hostUid=context.getPackageManager().getApplicationInfo(ProactivityWire.PACKAGE,0).uid;',
    'hostUid=context.getPackageManager().getApplicationInfo(ProactivityWire.PACKAGE,0).uid;\n        ObserverLinkDiagnostic.host(hostUid);')
client=replace_once(client,'if(!bound)throw new IllegalStateException("Proactivity host unavailable");',
    'ObserverLinkDiagnostic.bound(bound);\n        if(!bound)throw new IllegalStateException("Proactivity host unavailable");')
client=replace_once(client,'return request("register",new JSONObject().put("spec",ProactivityWire.spec(spec)));',
    'ObserverLinkDiagnostic.stage("register_send_begin");\n        try { String result=request("register",new JSONObject().put("spec",PixelLegacyProactivity.spec(spec)));ObserverLinkDiagnostic.stage("register_send_success");return result; }\n        catch(Exception error){ObserverLinkDiagnostic.failure("register_send",error);throw error;}')
client=replace_once(client,'if(ProactivityWire.integer(receipt,"version")==ProactivityWire.VERSION)listener.onMessage(receipt);',
    'if(ProactivityWire.integer(receipt,"version")==ProactivityWire.VERSION)listener.onMessage(PixelLegacyProactivity.receipt(receipt));')
client=replace_once(client,'main();if(remote!=null)try{request("disconnect",new JSONObject());}',
    'main();ObserverLinkDiagnostic.stage("client_close");if(remote!=null)try{request("disconnect",new JSONObject());}')

reminder=(ROOT/'system/shopping-observer/src/org/agentos/shopping/ReminderConnection.java').read_text()
reminder=replace_once(reminder,'public void onConnected() {','public void onConnected() {\n                ObserverLinkDiagnostic.stage("reminder_connected");')
reminder=replace_once(reminder,'catch (Exception unavailable) { listener.state(false, "Reminder service is unavailable"); }',
    'catch (Exception unavailable) { ObserverLinkDiagnostic.failure("reminder_register",unavailable);listener.state(false, "Reminder service is unavailable"); }')
reminder=replace_once(reminder,'public void onMessage(JSONObject message) {',
    'public void onMessage(JSONObject message) {\n                ObserverLinkDiagnostic.receipt("reminder_receipt",message,true);')
reminder=replace_once(reminder,'public void onDisconnected() {',
    'public void onDisconnected() {\n                ObserverLinkDiagnostic.stage("reminder_disconnected");')
reminder=replace_once(reminder,'void connect() { try { client.connect(); } catch (Exception e) { listener.state(false, "Install the Taku Proactivity component first"); } }',
    'void connect() { ObserverLinkDiagnostic.stage("reminder_connect");try { client.connect(); } catch (Exception e) { ObserverLinkDiagnostic.failure("reminder_connect",e);listener.state(false, "Install the Taku Proactivity component first"); } }')
for relative,source in [('org/agentos/proactivity/api/ProactivityClient.java',client),('org/agentos/shopping/ReminderConnection.java',reminder)]:
    target=HERE/'src'/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(source)
