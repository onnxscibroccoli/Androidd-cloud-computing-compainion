package org.broccoli.controller;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        c.getSharedPreferences("controller",0).edit().putBoolean("boot_requires_init",true).apply();
        // No background foreground-service launch. The user starts an assistive session explicitly.
    }
}
