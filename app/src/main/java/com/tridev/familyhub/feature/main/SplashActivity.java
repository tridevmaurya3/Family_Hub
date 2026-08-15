package com.tridev.familyhub.feature.main;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.core.security.AppSecurityStore;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.familyaccount.FamilySetupActivity;
import com.tridev.familyhub.feature.security.AppLockActivity;

/** Uses the Android 12 splash-screen API while keeping compatibility with older devices. */
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }

        if (AppSecurityStore.isProtectionEnabled(this)) {
            startActivity(AppLockActivity.intentFromSplash(this));
        } else {
            startActivity(new Intent(this, FamilySetupActivity.class));
        }
        finish();
    }
}
