package com.tridev.familyhub.core.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.AccountDataScope;
import com.tridev.familyhub.data.repository.FamilySyncOutbox;

/** Small account-wide status strip, shared by the app and floating panels. */
public final class SharedSyncStatusView extends AppCompatTextView {
    private String uid = "";
    private boolean attached, connected;
    private SharedPreferences preferences;
    private DatabaseReference connection;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> post(this::refresh);
    private final FirebaseAuth.AuthStateListener auth = ignored -> bindAccount();
    private final ValueEventListener connectionListener = new ValueEventListener() {
        @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
            connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class)); refresh();
        }
        @Override public void onCancelled(@NonNull DatabaseError error) { connected = false; refresh(); }
    };
    public SharedSyncStatusView(Context context) { this(context,null); }
    public SharedSyncStatusView(Context context, AttributeSet attrs) {
        super(context,attrs);
        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        setPadding(padding,padding / 2,padding,padding / 2);
        setTextSize(11);
        setTextColor(androidx.core.content.ContextCompat.getColor(context,R.color.fh_primary));
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        setOnClickListener(view -> {
            if (!attached || uid.isEmpty() || !uid.equals(AccountDataScope.uid())) return;
            if (!connected) { Toast.makeText(getContext(),R.string.shared_sync_offline_help,Toast.LENGTH_LONG).show(); return; }
            int[] status = FamilySyncOutbox.status(uid);
            if (status[0] > 0) {
                FamilySyncOutbox.drain(uid);
                Toast.makeText(getContext(),R.string.shared_sync_retry_help,Toast.LENGTH_SHORT).show();
            }
        });
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true;
        bindAccount();
        connection = FirebaseDatabase.getInstance().getReference(".info/connected");
        connection.addValueEventListener(connectionListener);
        FirebaseAuth.getInstance().addAuthStateListener(auth);
    }
    @Override protected void onDetachedFromWindow() {
        attached = false;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(changes);
        preferences = null;
        FirebaseAuth.getInstance().removeAuthStateListener(auth);
        if (connection != null) connection.removeEventListener(connectionListener);
        connection = null; connected = false;
        super.onDetachedFromWindow();
    }
    private void bindAccount() {
        if (!attached) return;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(changes);
        uid = AccountDataScope.uid();
        preferences = uid.isEmpty() ? null : FamilySyncOutbox.statusPreferences(uid);
        if (preferences != null) preferences.registerOnSharedPreferenceChangeListener(changes);
        refresh();
    }
    private void refresh() {
        if (!attached || !uid.equals(AccountDataScope.uid())) return;
        if (uid.isEmpty()) { setVisibility(GONE); return; }
        setVisibility(VISIBLE);
        int[] status = FamilySyncOutbox.status(uid);
        setText(getContext().getString(SharedSyncStatusPolicy.message(connected,status[0],status[1]),status[0],status[1]));
    }
}
