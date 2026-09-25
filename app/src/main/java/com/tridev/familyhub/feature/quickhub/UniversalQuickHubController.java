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
    public static void start(Context c,boolean visible){stopLegacy(c);c.getSharedPreferences(UniversalFamilyQuickHubService.PREFS,Context.MODE_PRIVATE).edit().putBoolean(UniversalFamilyQuickHubService.KEY_ENABLED,true).apply();ContextCompat.startForegroundService(c,new Intent(c,UniversalFamilyQuickHubService.class).setAction(visible?UniversalFamilyQuickHubService.ACTION_SHOW:UniversalFamilyQuickHubService.ACTION_HIDE));}
    public static void setVisible(Context c,boolean visible){if(isEnabled(c))c.startService(new Intent(c,UniversalFamilyQuickHubService.class).setAction(visible?UniversalFamilyQuickHubService.ACTION_SHOW:UniversalFamilyQuickHubService.ACTION_HIDE));}
    public static void stop(Context c){c.getSharedPreferences(UniversalFamilyQuickHubService.PREFS,Context.MODE_PRIVATE).edit().putBoolean(UniversalFamilyQuickHubService.KEY_ENABLED,false).apply();c.stopService(new Intent(c,UniversalFamilyQuickHubService.class));stopLegacy(c);}
    private static void stopLegacy(Context c){
        c.stopService(new Intent(c,GroceryOverlayService.class));
        c.stopService(new Intent(c,FamilyTaskOverlayService.class));
        c.getSharedPreferences(GroceryOverlayService.PREFS,Context.MODE_PRIVATE)
                .edit().putBoolean(GroceryOverlayService.KEY_ENABLED,false).apply();
        c.getSharedPreferences(FamilyTaskOverlayService.PREFS,Context.MODE_PRIVATE)
                .edit().putBoolean(FamilyTaskOverlayService.KEY_ENABLED,false).apply();
    }
}
