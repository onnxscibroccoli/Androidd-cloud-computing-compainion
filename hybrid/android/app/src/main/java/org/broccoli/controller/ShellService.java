package org.broccoli.controller;

import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Shizuku UserService. Shell UID only, fixed argv, bounded execution and output. */
public class ShellService extends IShell.Stub {
    public ShellService() {}
    @Override public int uid() { return Process.myUid(); }
    @Override public void destroy() { Process.killProcess(Process.myPid()); }
    @Override public synchronized String execute(String operation, String arguments) {
        java.lang.Process child=null;
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try {
            if(uid()!=2000) throw new SecurityException("Non-root shell UID 2000 required");
            child=new ProcessBuilder(Commands.argv(operation,arguments)).redirectErrorStream(true).start();
            final java.lang.Process running=child;
            Future<byte[]> output=reader.submit(()->{
                try(InputStream in=running.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[4096]; int n;
                    while((n=in.read(buffer))!=-1) {
                        if(out.size()+n>65536) { running.destroyForcibly(); throw new IOException("Output limit exceeded"); }
                        out.write(buffer,0,n);
                    }
                    return out.toByteArray();
                }
            });
            if(!child.waitFor(60,TimeUnit.SECONDS)) { child.destroyForcibly(); throw new TimeoutException("Execution timed out; outcome may be unknown"); }
            String text=new String(output.get(2,TimeUnit.SECONDS),StandardCharsets.UTF_8);
            if(operation.equals("snapshot") && child.exitValue()==0) {
                Path path=java.nio.file.Paths.get("/data/local/tmp/broccoli-controller.xml");
                try(InputStream in=Files.newInputStream(path)) {
                    ByteArrayOutputStream snapshot=new ByteArrayOutputStream();
                    byte[] chunk=new byte[4096];int count;
                    while((count=in.read(chunk))!=-1) { snapshot.write(chunk,0,count);if(snapshot.size()>65536) break; }
                    byte[] bytes=snapshot.toByteArray();
                    if(bytes.length>65536) throw new IOException("Snapshot exceeds output limit");
                    text=new String(bytes,StandardCharsets.UTF_8);
                } finally { Files.deleteIfExists(path); }
            }
            return new JSONObject().put("state",child.exitValue()==0?"SUCCEEDED":"FAILED").put("output",text).toString();
        } catch(Exception e) {
            return "{\"state\":\"UNKNOWN\",\"output\":"+JSONObject.quote(e.getClass().getSimpleName()+": "+e.getMessage())+"}";
        } finally { if(child!=null && child.isAlive()) child.destroyForcibly(); reader.shutdownNow(); }
    }
}
