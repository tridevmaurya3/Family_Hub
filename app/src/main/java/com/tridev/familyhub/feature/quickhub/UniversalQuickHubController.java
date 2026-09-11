package com.tridev.familyhub.feature.quickhub;

import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;
import com.tridev.familyhub.feature.grocery.overlay.GroceryOverlayService;
import com.tridev.familyhub.feature.tasks.overlay.FamilyTaskOverlayService;

public final class UniversalQuickHubController {
    private UniversalQuickHubController() { }
    public static boolean isEnabled(Context c){return c.getSharedPreferences(UniversalFamilyQuickHubService.PREFS,Context.MODE_PRIVATE).getBoolean(UniversalFamilyQuickHubService.KEY_ENABLED,false);}
    public static boolean wasRequested(Context c){return c.getSharedPreferences(UniversalFamilyQuickHubService.PREFS,Context.MODE_PRIVATE).getBoolean(UniversalFamilyQuickHubService.KEY_REQUESTED,false);}
    public static void setRequested(Context c,boolean value){c.getSharedPreferences(UniversalFamilyQuickHubService.PREFS,Context.MODE_PRIVATE).edit().putBoolean(UniversalFamilyQuickHubService.KEY_REQUESTED,value).apply();}
    public static void start(Context c,boolean visible){stopLegacy(c);ContextCompat.startForegroundService(c,new Intent(c,UniversalFamilyQuickHubService.class).setAction(visible?UniversalFamilyQuickHubService.ACTION_SHOW:UniversalFamilyQuickHubService.ACTION_HIDE));}
    public static void setVisible(Context c,boolean visible){if(isEnabled(c))c.startService(new Intent(c,UniversalFamilyQuickHubService.class).setAction(visible?UniversalFamilyQuickHubService.ACTION_SHOW:UniversalFamilyQuickHubService.ACTION_HIDE));}
    public static void stop(Context c){c.startService(new Intent(c,UniversalFamilyQuickHubService.class).setAction(UniversalFamilyQuickHubService.ACTION_STOP));stopLegacy(c);}
    private static void stopLegacy(Context c){
        // Do not start stopped legacy services merely to send STOP: their onCreate
        // briefly shows the old strips before the universal icon appears.
        if(c.getSharedPreferences(GroceryOverlayService.PREFS,Context.MODE_PRIVATE)
                .getBoolean(GroceryOverlayService.KEY_ENABLED,false)){
            c.startService(new Intent(c,GroceryOverlayService.class)
                    .setAction(GroceryOverlayService.ACTION_STOP));
        }
        if(c.getSharedPreferences(FamilyTaskOverlayService.PREFS,Context.MODE_PRIVATE)
                .getBoolean(FamilyTaskOverlayService.KEY_ENABLED,false)){
            c.startService(new Intent(c,FamilyTaskOverlayService.class)
                    .setAction(FamilyTaskOverlayService.ACTION_STOP));
        }
    }
}
