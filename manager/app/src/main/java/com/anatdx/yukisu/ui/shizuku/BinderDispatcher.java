package com.anatdx.yukisu.ui.shizuku;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import rikka.shizuku.SystemServiceHelper;
import java.lang.reflect.Method;
import java.util.List;

public class BinderDispatcher {
    private static final String TAG = "YukiSU_Dispatcher";
    private static final String SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23";
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String SERVICE_NAME = "moe.shizuku.server.IShizukuService";

    public static void main(String[] args) {
        try {
            Log.i(TAG, "Starting Binder Dispatcher...");
            
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }

            // Acquire System Context via reflection
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getMethod("systemMain");
            Object activityThread = systemMain.invoke(null);
            Method getSystemContext = atClass.getMethod("getSystemContext");
            Context context = (Context) getSystemContext.invoke(activityThread);

            if (context == null) {
                Log.e(TAG, "Failed to get system context");
                return;
            }

            // Get Shizuku Binder
            IBinder binder = SystemServiceHelper.getSystemService(SERVICE_NAME);
            if (binder == null) {
                Log.e(TAG, "Failed to get Shizuku Binder! Service not found.");
                return;
            }

            // Find Shizuku Clients
            PackageManager pm = context.getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS);
            
            int count = 0;
            for (PackageInfo pi : packages) {
                if (pi.providers == null) continue;
                for (ProviderInfo prov : pi.providers) {
                     if (SHIZUKU_PERMISSION.equals(prov.readPermission)) {
                         // This is a Shizuku client provider
                         if (prov.authority == null) continue;

                         Log.i(TAG, "Dispatching binder to " + pi.packageName + " (auth=" + prov.authority + ")");
                         count++;
                         
                         try {
                             Bundle extra = new Bundle();
                             extra.putBinder(EXTRA_BINDER, binder);
                             
                             context.getContentResolver().call(
                                 android.net.Uri.parse("content://" + prov.authority),
                                 "sendBinder", 
                                 null, 
                                 extra
                             );
                         } catch (Throwable t) {
                             Log.e(TAG, "Failed to dispatch to " + pi.packageName + ": " + t.getMessage());
                         }
                     }
                }
            }
            Log.i(TAG, "Dispatch complete. Sent to " + count + " apps.");

        } catch (Throwable t) {
            Log.e(TAG, "Dispatcher crashed", t);
        } finally {
            // Force exit as Looper.loop() might block if we don't
            System.exit(0);
        }
    }
}
