package com.tridev.familyhub.feature.main;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.familyaccount.FamilySetupActivity;

/** Uses the Android 12 splash-screen API while keeping compatibility with older devices. */
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        Class<?> destination = user != null && user.isEmailVerified()
                ? FamilySetupActivity.class
                : AuthActivity.class;

        startActivity(new Intent(this, destination));
        finish();
    }
}
