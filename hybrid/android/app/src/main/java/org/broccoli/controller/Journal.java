package org.broccoli.controller;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.broccoli.controller.proto.Result;
import java.util.*;

public class Journal extends SQLiteOpenHelper {
    public Journal(Context c) { super(c,"jobs-"+c.getSharedPreferences("controller",0).getString("enrollment","unconfigured")+".db",null,1); getWritableDatabase().execSQL("UPDATE jobs SET state='UNKNOWN',output='Controller restarted during execution; reconcile before retry' WHERE state='RUNNING'"); }
    @Override public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,payload TEXT NOT NULL,state TEXT NOT NULL,output TEXT NOT NULL,acked INTEGER NOT NULL DEFAULT 0)"); }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("Unsupported journal migration"); }
    public synchronized boolean claim(String id,String payload) {
        SQLiteDatabase db=getWritableDatabase();
        try(Cursor c=db.rawQuery("SELECT payload FROM jobs WHERE id=?",new String[]{id})) {
            if(c.moveToFirst()) { if(!payload.equals(c.getString(0))) throw new SecurityException("Job ID payload changed"); return false; }
        }
        ContentValues v=new ContentValues(); v.put("id",id);v.put("payload",payload);v.put("state","RUNNING");v.put("output","");
        db.insertOrThrow("jobs",null,v); return true;
    }
    public synchronized void finish(String id,String state,String output) {
        ContentValues v=new ContentValues();v.put("state",state);v.put("output",output);
        getWritableDatabase().update("jobs",v,"id=? AND state='RUNNING'",new String[]{id});
    }
    public synchronized List<Result> pending() {
        List<Result> results=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,state,output FROM jobs WHERE state!='RUNNING' AND acked=0 AND id NOT LIKE 'local:%' LIMIT 16",null)) {
            while(c.moveToNext()) results.add(Result.newBuilder().setJobId(c.getString(0)).setState(c.getString(1)).setOutput(c.getString(2)).build());
        } return results;
    }
    public synchronized String status(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT state,output FROM jobs WHERE id=?",new String[]{id})) {
            if(!c.moveToFirst()) return "Not found";
            return c.getString(0)+"\n"+c.getString(1);
        }
    }
    public synchronized void ack(String id) { ContentValues v=new ContentValues();v.put("acked",1);getWritableDatabase().update("jobs",v,"id=?",new String[]{id}); }
}
