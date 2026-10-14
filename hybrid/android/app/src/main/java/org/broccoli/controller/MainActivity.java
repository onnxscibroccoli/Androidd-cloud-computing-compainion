package org.broccoli.controller;
import android.app.Activity;
import android.os.*;
import android.content.*;
import android.widget.*;
import rikka.shizuku.Shizuku;
import java.io.*;

public class MainActivity extends Activity {
    private TextView status;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable update=new Runnable() {public void run() {ControllerService s=ControllerService.active;status.setText(s==null?"Stopped. Import enrollment, authorize Shizuku, then Start. After starting, run broccoli-controller init in Termux.":s.status());handler.postDelayed(this,1500);}};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("Broccoli Controller");title.setTextSize(26);layout.addView(title);
        status=new TextView(this);status.setTextSize(18);layout.addView(status);
        add(layout,"Import enrollment",()->{if(ControllerService.active!=null) {toast("Stop controller before importing");return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),10);});
        add(layout,"Authorize Shizuku / Shevery",()->{try {if(Shizuku.pingBinder()) Shizuku.requestPermission(1);else toast("Start Shizuku or Shevery using wireless debugging first");}catch(Exception e){toast(e.getMessage());}});
        add(layout,"Start controller",()->{if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},2);startForegroundService(new Intent(this,ControllerService.class));});
        add(layout,"Stop all controller execution",()->stopService(new Intent(this,ControllerService.class)));
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
    }
    private void add(LinearLayout layout,String text,Runnable action) {Button b=new Button(this);b.setText(text);b.setMinHeight(64);b.setOnClickListener(v->action.run());layout.addView(b);}
    private void toast(String text) {Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume() {super.onResume();handler.post(update);}
    @Override protected void onPause() {handler.removeCallbacks(update);super.onPause();}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=10 || result!=RESULT_OK || data==null || data.getData()==null) return;
        try(InputStream in=getContentResolver().openInputStream(data.getData());ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1) {if(out.size()+n>131072) throw new IOException("Enrollment too large");out.write(b,0,n);}
            Enrollment.install(this,out.toString("UTF-8"));toast("Enrollment imported. Remove the transport bundle from Downloads.");
        } catch(Exception e) {toast("Enrollment failed: "+e.getClass().getSimpleName());}
    }
}
