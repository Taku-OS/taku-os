#!/usr/bin/env python3
"""Exercise production credential-pipe methods with deterministic FD/time/controller doubles.

Descriptor duplication models Binder transfer; native Android/Binder acceptance is separate.
No device, SDK runtime, credential store, or model is invoked.
"""
from pathlib import Path
import os,re,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[4]
SRC=ROOT/'system/tryon/app/src/org/taku/tryon/api'
source=(SRC/'TryOnApiProvider.java').read_text()
def method(name):
    match=re.search(r'^    (?:@Override )?(?:private|public) [^\n]+\b'+name+r'\([^\n]*\)[^\n]*\{',source,re.M)
    if not match:raise AssertionError(name)
    end=source.index('{',match.start())+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[match.start():end]
fields=source[source.index('    private final CredentialWriteGate'):source.index('    private void enforce()')]
fixture=r'''package org.taku.tryon.api;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public final class CredentialPipeTest extends ProviderBase {
'''+fields+'\n'+'\n'.join(method(n) for n in ['openCredentialPipe','readCredentialPipe','closeCredentialPipe','shutdown'])+r'''
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 interface Condition {boolean ok();}static void until(Condition condition,String message)throws Exception{long deadline=System.nanoTime()+2_000_000_000L;while(!condition.ok()&&System.nanoTime()<deadline)Thread.sleep(1);check(condition.ok(),message);}
 static CredentialPipeTest fresh(){SystemClock.now=1000;Handler.reset();ParcelFileDescriptor.failCreate=false;TryOnController.calls.set(0);TryOnController.key="previous-key";return new CredentialPipeTest();}
 static ParcelFileDescriptor client(ParcelFileDescriptor server)throws IOException{ParcelFileDescriptor transferred=server.duplicate();server.close();return transferred;}
 static void expectCode(String code,Throwing action)throws Exception{try{action.run();throw new AssertionError("expected "+code);}catch(FileNotFoundException error){check(code.equals(error.getMessage()),"fixed error: "+code);}}
 interface Throwing {void run()throws Exception;}
 static void writeAndClose(ParcelFileDescriptor client,String text)throws IOException{client.write(text.getBytes(StandardCharsets.UTF_8));client.close();}
 public static void main(String[] args)throws Exception{
  CredentialPipeTest f=fresh();try{
   ParcelFileDescriptor local=f.openCredentialPipe("lucy"),read=ParcelFileDescriptor.lastRead,remote=client(local);
   check(local.closed&&!read.closed,"Binder transfer closes only local writer, preserving caller input");
   expectCode("credential_write_busy",()->f.openCredentialPipe("xmax"));
   writeAndClose(remote,"  sample-key-one  ");until(()->read.closed,"normal EOF closes read descriptor");
   until(()->f.credentialWorker.getActiveCount()==0,"normal reader terminates");
   check(TryOnController.calls.get()==1&&TryOnController.key.equals("sample-key-one"),"normal transferred EOF preserves trimmed authoritative credential write");
   check(f.credentialPipe.get()==null&&Handler.size()==0,"normal completion clears gate owner and timer");
  }finally{f.shutdown();}
  CredentialPipeTest timeout=fresh();try{
   ParcelFileDescriptor remote=client(timeout.openCredentialPipe("lucy")),read=ParcelFileDescriptor.lastRead;
   remote.write("partial".getBytes(StandardCharsets.UTF_8));Handler.fireAt(31000);
   until(()->read.closed,"timeout closes abandoned input");check("credential_write_timeout".equals(read.state.error),"timeout has a fixed pipe error");
   until(()->timeout.credentialWorker.getActiveCount()==0,"timeout releases reader worker");
   check(TryOnController.calls.get()==0&&TryOnController.key.equals("previous-key"),"partial timed-out input never replaces existing key");
   remote.close();ParcelFileDescriptor replacement=client(timeout.openCredentialPipe("xmax"));writeAndClose(replacement,"replacement-key");
   until(()->TryOnController.calls.get()==1&&timeout.credentialWorker.getActiveCount()==0,"timed-out gate permits a fresh bounded input");
  }finally{timeout.shutdown();}
  CredentialPipeTest create=fresh();try{
   ParcelFileDescriptor.failCreate=true;expectCode("credential_pipe_failed",()->create.openCredentialPipe("lucy"));ParcelFileDescriptor.failCreate=false;
   ParcelFileDescriptor remote=client(create.openCredentialPipe("lucy"));writeAndClose(remote,"after-create-error");
   until(()->TryOnController.calls.get()==1&&create.credentialWorker.getActiveCount()==0,"create-pipe failure releases gate for next input");
  }finally{create.shutdown();}
  CredentialPipeTest reject=fresh();try{
   ParcelFileDescriptor oldRemote=client(reject.openCredentialPipe("lucy")),oldRead=ParcelFileDescriptor.lastRead;
   oldRead.state.holdReadOnClose=true;until(()->oldRead.state.entered,"first reader entered blocking read");Handler.fireAt(31000);
   check(oldRead.closed&&"credential_write_timeout".equals(oldRead.state.error),"timeout invalidates old generation even if native read exits slowly");
   expectCode("credential_write_busy",()->reject.openCredentialPipe("xmax"));ParcelFileDescriptor rejectedRead=ParcelFileDescriptor.lastRead;
   check(rejectedRead.closed&&rejectedRead.state.writers==0,"rejected worker closes both new descriptors");
   check(reject.credentialWorker.getPoolSize()<=1&&reject.credentialWorker.getQueue().isEmpty(),"expired native reader cannot accumulate queued threads");
   oldRead.state.release();oldRemote.close();until(()->reject.credentialWorker.getActiveCount()==0,"slow old reader finally exits");
   ParcelFileDescriptor fresh=client(reject.openCredentialPipe("xmax"));writeAndClose(fresh,"after-worker-rejection");
   until(()->TryOnController.calls.get()==1&&reject.credentialWorker.getActiveCount()==0,"worker rejection and old finalizer leave next generation usable");
   check(TryOnController.key.equals("after-worker-rejection"),"expired old reader cannot overwrite newer key");
  }finally{reject.shutdown();}
  CredentialPipeTest destroy=fresh();ParcelFileDescriptor remote=client(destroy.openCredentialPipe("lucy")),read=ParcelFileDescriptor.lastRead;
  destroy.shutdown();until(()->destroy.credentialWorker.getActiveCount()==0,"shutdown closes active reader and worker");
  check(read.closed&&Handler.size()==0&&TryOnController.calls.get()==0,"shutdown releases descriptor, timer and pending mutation");remote.close();
  expectCode("credential_write_busy",()->destroy.openCredentialPipe("lucy"));
  System.out.println("Credential provider production-method checks: "+checks);
 }
}
class ProviderBase {Object getContext(){return this;}public void shutdown(){}}
class Looper {static Looper getMainLooper(){return new Looper();}}
class SystemClock {static volatile long now;static long elapsedRealtime(){return now;}}
class Handler {
 private static final List<Runnable> jobs=new ArrayList<>();Handler(Looper ignored){}
 boolean postDelayed(Runnable task,long delay){synchronized(jobs){jobs.add(task);}return true;}
 void removeCallbacks(Runnable task){synchronized(jobs){jobs.removeIf(value->value==task);}}
 static void reset(){synchronized(jobs){jobs.clear();}}static int size(){synchronized(jobs){return jobs.size();}}
 static void fireAt(long now){SystemClock.now=now;List<Runnable> run;synchronized(jobs){run=new ArrayList<>(jobs);jobs.clear();}for(Runnable r:run)r.run();}
}
class Bundle {boolean ok=true;String code="configured";boolean getBoolean(String name){return ok;}String getString(String name,String fallback){return code==null?fallback:code;}}
class TryOnController {static AtomicInteger calls=new AtomicInteger();static volatile String key;static Bundle credential(Object context,String provider,String value,boolean clear){calls.incrementAndGet();key=value;return new Bundle();}}
class ParcelFileDescriptor implements AutoCloseable {
 static volatile boolean failCreate;static volatile ParcelFileDescriptor lastRead;final State state;final boolean reader;volatile boolean closed;
 private ParcelFileDescriptor(State state,boolean reader){this.state=state;this.reader=reader;if(!reader)synchronized(state){state.writers++;}}
 static ParcelFileDescriptor[] createReliablePipe()throws IOException{if(failCreate)throw new IOException("create failed");State state=new State();ParcelFileDescriptor read=new ParcelFileDescriptor(state,true),write=new ParcelFileDescriptor(state,false);lastRead=read;return new ParcelFileDescriptor[]{read,write};}
 ParcelFileDescriptor duplicate()throws IOException{if(closed)throw new IOException("closed");return new ParcelFileDescriptor(state,reader);}
 void write(byte[] bytes)throws IOException{synchronized(state){if(closed||state.readClosed)throw new IOException("closed");state.data.write(bytes);state.notifyAll();}}
 void closeWithError(String error)throws IOException{synchronized(state){if(!closed&&state.error==null)state.error=error;}close();}
 public void close()throws IOException{synchronized(state){if(closed)return;closed=true;if(reader)state.readClosed=true;else state.writers--;state.notifyAll();}}
 static final class State {
  final ByteArrayOutputStream data=new ByteArrayOutputStream();int consumed,writers;volatile boolean entered,holdReadOnClose;boolean readClosed,released;String error;
  synchronized int read(byte[] bytes,int offset,int length)throws IOException{
   entered=true;
   while(true){
    if(readClosed){if(holdReadOnClose&&!released){waitInput();continue;}throw new IOException("read closed");}
    byte[] ready=data.toByteArray();int available=ready.length-consumed;
    if(available>0){int amount=Math.min(length,available);System.arraycopy(ready,consumed,bytes,offset,amount);consumed+=amount;return amount;}
    if(writers==0)return -1;waitInput();
   }
  }
  private void waitInput()throws IOException{try{wait();}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException(stopped);}}
  synchronized void release(){released=true;notifyAll();}
 }
 static class AutoCloseInputStream extends InputStream {final ParcelFileDescriptor descriptor;AutoCloseInputStream(ParcelFileDescriptor d){descriptor=d;}public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}public int read(byte[] b,int off,int len)throws IOException{return descriptor.state.read(b,off,len);}public void close()throws IOException{descriptor.close();}}
}
'''
jdk=os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@23/libexec/openjdk.jdk/Contents/Home')
with tempfile.TemporaryDirectory(prefix='taku-credential-pipes-') as tmp:
    target=Path(tmp)/'CredentialPipeTest.java';target.write_text(fixture)
    subprocess.run([jdk+'/bin/javac','--release','17','-encoding','UTF-8','-d',tmp,str(SRC/'CredentialWriteGate.java'),str(target)],check=True)
    subprocess.run([jdk+'/bin/java','-cp',tmp,'org.taku.tryon.api.CredentialPipeTest'],check=True,timeout=30)
