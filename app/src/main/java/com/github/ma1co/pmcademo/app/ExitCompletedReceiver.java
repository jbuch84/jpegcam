package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * JPEG.CAM System: Exit Completed Receiver
 *
 * Declared in AndroidManifest.xml to receive Sony's EXIT_COMPLETED broadcast.
 * This fires when the Sony OS has finished its shutdown sequence after our app exits.
 *
 * Currently a stub — the class must exist to satisfy the manifest declaration
 * and prevent a missing-class runtime error if the broadcast is ever received.
 *
 * Future use: clean up any lingering temp files, reset hardware state flags,
 * or log clean exit telemetry.
 */
public class ExitCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "JPEG.CAM";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Sony OS has confirmed our app has fully exited.
        // Safe to perform any final one-shot cleanup here in a future release.
        Log.d(TAG, "ExitCompletedReceiver: Sony exit sequence confirmed.");
    }
}
