package org.broccoli.controller;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.*;
import org.broccoli.controller.proto.*;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;
import java.util.concurrent.*;

public class ControllerService extends Service {
    static volatile ControllerService active;
    private volatile IShell shell;
    private volatile boolean initialized=false,closed=false;
    private volatile String message="Waiting for Shizuku authorization";
    private ManagedChannel channel;
    private ClientCallStreamObserver<DeviceEvent> stream;
    private final ScheduledExecutorService network=Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor jobs=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private Journal journal;
    private String device,boot;
    private long nextConnect=0;
    private int backoff=1;
    private Shizuku.UserServiceArgs args;
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder binder) {shell=IShell.Stub.asInterface(binder);initialized=false;message="Run broccoli-controller init from Termux after each service restart";}
        public void onServiceDisconnected(ComponentName name) {shell=null;initialized=false;message="Shell disconnected; initialize again";}
    };
    private final Shizuku.OnBinderReceivedListener received=()->new Handler(Looper.getMainLooper()).post(this::bind);
    private final Shizuku.OnBinderDeadListener dead=()->{shell=null;initialized=false;message="Shizuku stopped; restart and initialize rish";};
    private final Shizuku.OnRequestPermissionResultListener permission=(request,result)->{if(result==PackageManager.PERMISSION_GRANTED) bind();};
    @Override public void onCreate() {
        super.onCreate();active=this;journal=new Journal(this);
        boot=Integer.toString(Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT,-1));
        device=getSharedPreferences("controller",0).getString("device","");
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("controller","Assistive controller",NotificationManager.IMPORTANCE_LOW));
        Intent stop=new Intent(this,ControllerService.class).setAction("STOP");
        PendingIntent action=PendingIntent.getService(this,0,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(1,new Notification.Builder(this,"controller").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Broccoli controller active").setContentText("Remote control session; tap Stop to disconnect").setContentIntent(open).addAction(new Notification.Action.Builder(null,"Stop",action).build()).build());
        args=new Shizuku.UserServiceArgs(new ComponentName(this,ShellService.class)).daemon(false).processNameSuffix("shell").version(BuildConfig.VERSION_CODE);
        Shizuku.addBinderReceivedListenerSticky(received);Shizuku.addBinderDeadListener(dead);Shizuku.addRequestPermissionResultListener(permission);
        network.scheduleWithFixedDelay(this::tick,0,5,TimeUnit.SECONDS);
    }
    private void bind() {
        try {if(Shizuku.pingBinder() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED && Shizuku.getUid()==2000 && shell==null) Shizuku.bindUserService(args,connection);}
        catch(Exception e) {message="Shizuku unavailable: "+e.getClass().getSimpleName();}
    }
    synchronized String initialize() throws Exception {
        if(closed || shell==null || shell.uid()!=2000 || !Shizuku.pingBinder() || Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) throw new SecurityException("Start and authorize Shizuku/Shevery, then retry init");
        initialized=true;message="READY";return "READY boot="+boot;
    }
    boolean ready() {try {return !closed && initialized && shell!=null && Shizuku.pingBinder() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;} catch(Exception e) {initialized=false;return false;}}
    String status() {return message+"; boot="+boot+"; ready="+ready();}
    String jobStatus(String id) {return journal.status(id);}
    void dispatch(Command command) {
        if(closed) return;
        try {jobs.execute(()->execute(command));} catch(RejectedExecutionException e) {message="Execution queue full; awaiting redelivery";}
    }
    private void execute(Command c) {
        try {
            Commands.argv(c.getOperation(),c.getArgumentsJson());
            if(!journal.claim(c.getJobId(),c.getOperation()+"\n"+c.getArgumentsJson()+"\n"+c.getDeadlineMs())) return;
            if(System.currentTimeMillis()>=c.getDeadlineMs()) {journal.finish(c.getJobId(),"EXPIRED","Deadline elapsed");return;}
            if(!ready()) {journal.finish(c.getJobId(),"REJECTED","Fresh rish initialization required");return;}
            JSONObject result=new JSONObject(shell.execute(c.getOperation(),c.getArgumentsJson()));
            journal.finish(c.getJobId(),result.getString("state"),result.getString("output"));
        } catch(Exception error) {
            // If validation failed before a claim, persist a rejection under the received ID.
            try {journal.claim(c.getJobId(),c.getOperation()+"\n"+c.getArgumentsJson()+"\n"+c.getDeadlineMs());journal.finish(c.getJobId(),"UNKNOWN",error.getClass().getSimpleName());}
            catch(Exception ignored) {message="Conflicting job payload rejected";}
        }
    }
    private void tick() {
        if(closed) return;
        try {
            if(stream==null) {
                if(System.currentTimeMillis()<nextConnect) return;
                connect();
            }
            if(stream!=null && stream.isReady()) {
                DeviceEvent.Builder event=DeviceEvent.newBuilder().setDeviceId(device).setBootId(boot).setReadiness(ready()?"READY":"WAITING_RISH").setTimeMs(System.currentTimeMillis());
                stream.onNext(event.build());
                for(Result r:journal.pending()) {if(!stream.isReady()) break;stream.onNext(event.setResult(r).build());}
            }
        } catch(Exception e) {disconnect();message="Connection unavailable: "+e.getClass().getSimpleName();}
    }
    private void connect() throws Exception {
        SharedPreferences prefs=getSharedPreferences("controller",0);
        String host=prefs.getString("host","");
        if(host.isEmpty() || device.isEmpty()) throw new IllegalStateException("Import enrollment first");
        channel=OkHttpChannelBuilder.forAddress(host,prefs.getInt("port",8444)).sslSocketFactory(Enrollment.sockets(this)).maxInboundMessageSize(131072).keepAliveTime(30,TimeUnit.SECONDS).build();
        ControlGrpc.newStub(channel).connect(new ClientResponseObserver<DeviceEvent,CloudEvent>() {
            public void beforeStart(ClientCallStreamObserver<DeviceEvent> request) {stream=request;}
            public void onNext(CloudEvent event) {network.execute(()->{backoff=1;if(!event.getAckJobId().isEmpty()) journal.ack(event.getAckJobId());if(event.hasCommand()) dispatch(event.getCommand());});}
            public void onError(Throwable error) {if(!closed) network.execute(()->{disconnect();message="Reconnecting after transport failure";});}
            public void onCompleted() {if(!closed) network.execute(ControllerService.this::disconnect);}
        });
    }
    private void disconnect() {
        stream=null;if(channel!=null) {channel.shutdownNow();channel=null;}
        nextConnect=System.currentTimeMillis()+backoff*1000L+ThreadLocalRandom.current().nextInt(1000);
        backoff=Math.min(60,backoff*2);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {if(intent!=null && "STOP".equals(intent.getAction())) stopSelf();return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public void onDestroy() {
        closed=true;initialized=false;active=null;
        Shizuku.removeBinderReceivedListener(received);Shizuku.removeBinderDeadListener(dead);Shizuku.removeRequestPermissionResultListener(permission);
        try {Shizuku.unbindUserService(args,connection,true);} catch(Exception ignored) { }
        network.shutdownNow();jobs.shutdownNow();disconnect();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
