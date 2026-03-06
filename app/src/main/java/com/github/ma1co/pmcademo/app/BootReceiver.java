package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("RTL_PREFS", Context.MODE_PRIVATE);
        
        // Check if the app was running when the camera was turned off
        if (prefs.getBoolean("auto_resume", false)) {
            
            // ANTI-BRICK FAILSAFE: Instantly disarm the auto-resume. 
            // If the app crashes right now, the next reboot will be safe!
            prefs.edit().putBoolean("auto_resume", false).commit();
            
            // Relaunch JPG Cookbook
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }
}