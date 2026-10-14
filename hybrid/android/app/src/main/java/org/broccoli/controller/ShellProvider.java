package org.broccoli.controller;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.JSONObject;
import org.broccoli.controller.proto.Command;

/** Only Android shell UID can cross this boundary; the manifest also requires DUMP. */
public class ShellProvider extends ContentProvider {
    public boolean onCreate() {return true;}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if(Binder.getCallingUid()!=2000) throw new SecurityException("Only rish/ADB shell UID 2000 is accepted");
        Bundle out=new Bundle();
        try {
            ControllerService service=ControllerService.active;
            if(service==null) throw new IllegalStateException("Open Controller and press Start first");
            switch(method) {
                case "init": out.putString("result",service.initialize());break;
                case "status": out.putString("result",service.status());break;
                case "result": out.putString("result",service.jobStatus("local:"+arg));break;
                case "submit":
                    if(arg==null || arg.length()>16384) throw new IllegalArgumentException("Invalid request size");
                    JSONObject job=new JSONObject(arg);
                    String id=job.getString("id");java.util.UUID.fromString(id);
                    long deadline=job.getLong("deadline_ms");
                    if(deadline<=System.currentTimeMillis() || deadline>System.currentTimeMillis()+86400000L) throw new IllegalArgumentException("Invalid deadline");
                    String op=job.getString("operation"),args=job.getJSONObject("arguments").toString();
                    Commands.argv(op,args);
                    if(!service.ready()) throw new IllegalStateException("Run init first");
                    service.dispatch(Command.newBuilder().setJobId("local:"+id).setOperation(op).setArgumentsJson(args).setDeadlineMs(deadline).build());
                    out.putString("result","ACCEPTED "+id);break;
                default: throw new IllegalArgumentException("Unknown method");
            }
        } catch(Exception e) {out.putString("error",e.getClass().getSimpleName()+": "+e.getMessage());}
        return out;
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String o) {throw new UnsupportedOperationException("Use call");}
    public String getType(Uri u) {return null;}
    public Uri insert(Uri u,ContentValues v) {throw new UnsupportedOperationException("Use call");}
    public int delete(Uri u,String s,String[] a) {throw new UnsupportedOperationException("Use call");}
    public int update(Uri u,ContentValues v,String s,String[] a) {throw new UnsupportedOperationException("Use call");}
}
