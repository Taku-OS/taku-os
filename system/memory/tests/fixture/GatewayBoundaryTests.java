package org.agentos.memory;
import android.app.Instrumentation;
import android.os.Bundle;
import android.content.pm.PackageManager;
import java.net.*;
import java.io.IOException;
/** A real untrusted application UID with INTERNET must not bypass system AI policy over loopback. */
public final class GatewayBoundaryTests extends Instrumentation {
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle out=new Bundle();try{
  if(android.os.Process.myUid()<10000||getTargetContext().checkSelfPermission("android.permission.INTERNET")!=PackageManager.PERMISSION_GRANTED)throw new AssertionError("requires a real untrusted INTERNET-enabled UID");
  boolean denied=false;try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",3425),1500);}catch(IOException expected){denied=true;}
  if(!denied)throw new AssertionError("untrusted app reached Magpie directly");
  out.putString("result","{\"passed\":true,\"checks\":2,\"untrustedUidGatewayDenied\":true}");finish(-1,out);
 }catch(Throwable e){out.putString("failure",e.toString());finish(0,out);}}
}
