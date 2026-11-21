package com.tridev.familyhub.feature.chat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.core.security.AccountDataScope;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.main.SplashActivity;

public final class ConnectionInviteActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri link = getIntent().getData();
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && link != null
                && "familyhub".equals(link.getScheme()) && "chat-invite".equals(link.getHost())
                && link.getPathSegments().size() == 1) {
            String code = link.getLastPathSegment();
            if (ConnectionInvites.validCode(code)) {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user != null && user.isEmailVerified()) {
                    startActivity(AccountDataScope.stamp(this, new Intent(this, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_CHAT_INVITE, code)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
                } else {
                    Toast.makeText(this, "Sign in, then reopen this invite or enter its code", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(this, SplashActivity.class));
                }
            }
        }
        finish();
    }
}
