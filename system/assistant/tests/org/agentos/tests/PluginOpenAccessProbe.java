package org.agentos.tests;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
public class PluginOpenAccessProbe extends Activity {
 public void onCreate(Bundle b) { super.onCreate(b); new Thread(()->{try {
  for(String[] cmd:new String[][]{{"list"},{"disable","taku-status-tip@taku-creator-local"},{"enable","taku-status-tip@taku-creator-local"}}) {
  String[] args=new String[cmd.length+1]; args[0]="/system_ext/bin/taku-plugin"; System.arraycopy(cmd,0,args,1,cmd.length);
  Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
  String out=new String(p.getInputStream().readAllBytes(),"UTF-8");
  Log.i("CreatorProbe","uid="+android.os.Process.myUid()+" command="+cmd[0]+" exit="+p.waitFor()+" output="+out.substring(0,Math.min(200,out.length()))); }
 }catch(Exception e){Log.i("CreatorProbe","ERROR "+e);}}).start(); }
}
