package com.tridev.familyhub.feature.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account-scoped profile sync using existing users and memberships paths. */
public final class AccountProfileSync {
    public interface Listener { void onProfileChanged(); }
    public interface Result { void complete(boolean success, String message); }
    private static AccountProfileSync instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new HashSet<>();
    private DatabaseReference reference;
    private ValueEventListener cloudListener, connectionListener;
    private DatabaseReference connectionRef;
    private boolean profileReady, connected;
    private int pendingWrites;
    private String uid = "", name = "", appliedPhoto;
    private String state = "Syncing profile…";
    private long generation, photoOperation;
    private boolean migrationStarted;
    private String familyId = "", lastMembershipAttempt = "";
    private boolean membershipRetry;
    private AccountProfileSync(Context context) {
        this.context = context.getApplicationContext();
        FirebaseAuth.getInstance().addAuthStateListener(auth -> bind(auth.getCurrentUser()));
    }
    public static synchronized AccountProfileSync get(Context context) {
        if (instance == null) instance = new AccountProfileSync(context);
        return instance;
    }
    public void addListener(Listener listener) { listeners.add(listener); listener.onProfileChanged(); if(membershipRetry)lastMembershipAttempt=""; reconcileMembership(); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public String displayName() { return name; }
    public String familyId() { return familyId; }
    public String status() { return state; }
    private void updateConnectionStatus() {
        state=pendingWrites>0?(connected?"Syncing profile changes…":"Offline · changes waiting to sync"):
            connected?(profileReady?"Profile sync connected":"Syncing profile…"):"Offline · cached profile";
    }
    private boolean current(String account, long token) { return uid.equals(account) && generation == token; }
    private void notifyChanged() { for (Listener l : new ArrayList<>(listeners)) l.onProfileChanged(); }
    private void bind(FirebaseUser user) {
        String next = user == null ? "" : user.getUid();
        if (next.equals(uid) && reference != null) return;
        if (reference != null && cloudListener != null) reference.removeEventListener(cloudListener);
        if (connectionRef != null && connectionListener != null) connectionRef.removeEventListener(connectionListener);
        profileReady = false; connected = false; pendingWrites=0;
        uid = next; generation++; lastMembershipAttempt="";membershipRetry=false; appliedPhoto = null; migrationStarted = false; familyId = "";
        reference = null; cloudListener = null;
        name = next.isEmpty() ? "" : context.getSharedPreferences("account_profile_cache",0).getString(next+"_name", user.getDisplayName()==null?"":user.getDisplayName());
        state = next.isEmpty() ? "Sign in to sync profile" : "Syncing profile…";
        notifyChanged();
        if (next.isEmpty()) return;
        final String account = next; final long token = generation;
        reference = FirebaseDatabase.getInstance().getReference("users").child(account);
        cloudListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!current(account, token)) return;
                String remoteName = snapshot.child("displayName").getValue(String.class);
                if (remoteName != null && !remoteName.trim().isEmpty()) {
                    name = remoteName.trim();
                    FirebaseUser signedIn=FirebaseAuth.getInstance().getCurrentUser();
                    if(snapshot.child("profileSyncVersion").exists() && signedIn!=null && account.equals(signedIn.getUid()) && !name.equals(signedIn.getDisplayName()))
                        signedIn.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(name).build());
                    context.getSharedPreferences("account_profile_cache",0).edit().putString(account+"_name",name).apply();
                }
                String family = snapshot.child("familyId").getValue(String.class);
                familyId = family == null ? "" : family;
                String photo = snapshot.child("accountPhoto").getValue(String.class);
                if (photo != null && !photo.equals(appliedPhoto)) {
                    appliedPhoto = photo;
                    io.execute(() -> {
                        boolean ok = ProfilePhotoStore.applyCloud(context, account, photo);
                        main.post(() -> { if (current(account, token)) { if (!ok) state="Profile photo could not be loaded"; notifyChanged(); } });
                    });
                } else if (photo == null && !migrationStarted) {
                    migrationStarted = true;
                    migratePhoto(account, token);
                }
                if(snapshot.child("profileSyncVersion").exists()) reconcileMembership();
                profileReady = true;
                updateConnectionStatus();
                notifyChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (current(account,token)) { state="Profile sync unavailable · cached details"; notifyChanged(); }
            }
        };
        reference.addValueEventListener(cloudListener);
        // Auth refresh also restores changes made by previous app versions.
        user.reload().addOnSuccessListener(unused -> {
            if (!current(account,token)) return;
            FirebaseUser refreshed=FirebaseAuth.getInstance().getCurrentUser();
            if (refreshed == null || !account.equals(refreshed.getUid())) return;
            // One-time upgrade from Auth, which older versions used as the name source.
            String authName=refreshed.getDisplayName();
            if (authName != null && !authName.trim().isEmpty()) reference.runTransaction(new Transaction.Handler() {
                @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData data) {
                    if (data.child("profileSyncVersion").getValue()!=null) return Transaction.abort();
                    data.child("displayName").setValue(authName.trim());
                    data.child("profileSyncVersion").setValue(1); return Transaction.success(data);
                }
                @Override public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) { if(current(account,token))notifyChanged(); }
            }, false);
        });
        connectionRef=FirebaseDatabase.getInstance().getReference(".info/connected");
        connectionListener=new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if(current(account,token)){connected=Boolean.TRUE.equals(snapshot.getValue(Boolean.class));updateConnectionStatus();if(connected&&membershipRetry){lastMembershipAttempt="";reconcileMembership();}notifyChanged();}
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        connectionRef.addValueEventListener(connectionListener);
    }
    private void reconcileMembership() {
        if(uid.isEmpty() || familyId.isEmpty() || name.isEmpty())return;
        String key=familyId+"|"+name;
        if(key.equals(lastMembershipAttempt))return;
        lastMembershipAttempt=key;
        final String account=uid, family=familyId, desired=name;final long token=generation;
        DatabaseReference member=FirebaseDatabase.getInstance().getReference("memberships").child(family).child(account).child("displayName");
        member.get().addOnSuccessListener(snapshot -> {
            if(!current(account,token)||!family.equals(familyId)||!desired.equals(name))return;
            if(desired.equals(snapshot.getValue(String.class))){membershipRetry=false;return;}
            member.setValue(desired).addOnCompleteListener(task -> {
                if(!current(account,token)||!desired.equals(name))return;
                membershipRetry=!task.isSuccessful();
                if(membershipRetry)state="Account synced · Family name update unavailable";
                notifyChanged();
            });
        }).addOnFailureListener(error -> {if(current(account,token)){membershipRetry=true;}});
    }
    private void migratePhoto(String account,long token) {
        io.execute(() -> {
            Bitmap old=ProfilePhotoStore.load(context, account);
            String data=old==null?null:encode(old);
            main.post(() -> {
                if (!current(account,token) || data==null) return;
                reference.child("accountPhoto").runTransaction(new Transaction.Handler() {
                    @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData value) {
                        // An empty string is an explicit removal: old devices must not resurrect it.
                        if(value.getValue()!=null)return Transaction.abort();
                        value.setValue(data);return Transaction.success(value);
                    }
                    @Override public void onComplete(DatabaseError error,boolean committed,DataSnapshot snapshot) {
                        if(current(account,token)&&error!=null){state="Existing photo upload failed · retry on next sign-in";notifyChanged();}
                    }
                }, false);
            });
        });
    }
    public void saveName(String value, Result callback) {
        FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();
        if(user==null || !user.getUid().equals(uid)){callback.complete(false,"Sign in again");return;}
        final String account=uid;final long token=generation;
        pendingWrites++;state="Saving profile…";notifyChanged();
        Map<String,Object> changes=new HashMap<>();changes.put("displayName",value);changes.put("profileSyncVersion",1);changes.put("profileUpdatedAt",ServerValue.TIMESTAMP);
        reference.updateChildren(changes).addOnCompleteListener(task -> {
            if(!current(account,token))return;
            if(!task.isSuccessful()){pendingWrites=Math.max(0,pendingWrites-1);state="Profile save failed";notifyChanged();callback.complete(false,"Profile could not be synced");return;}
            if(!value.equals(name)){finishName(callback,true,true);return;}
            user.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(value).build()).addOnCompleteListener(authTask -> {
                if(!current(account,token))return;
                if(!value.equals(name)){
                    user.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(name).build());
                    finishName(callback,true,true);return;
                }
                if(familyId.isEmpty()){finishName(callback,authTask.isSuccessful(),true);return;}
                FirebaseDatabase.getInstance().getReference("memberships").child(familyId).child(account).child("displayName").setValue(value)
                    .addOnCompleteListener(memberTask -> {if(current(account,token))finishName(callback,authTask.isSuccessful(),memberTask.isSuccessful());});
            });
        });
    }
    private void finishName(Result callback, boolean authSaved,boolean membershipSaved) {
        pendingWrites=Math.max(0,pendingWrites-1);
        state=authSaved&&membershipSaved?"Profile synced":"Account synced · Family name update incomplete";
        notifyChanged(); callback.complete(true,authSaved&&membershipSaved?"Profile synced across devices":"Account name saved. Family name sync needs updated database rules or a retry.");
    }
    public void savePhoto(Uri uri, Result callback) {
        final String account=uid;final long token=generation, operation=++photoOperation;
        if(account.isEmpty()){callback.complete(false,"Sign in again");return;}
        state="Preparing profile photo…";notifyChanged();
        io.execute(() -> {
            String data=null;
            try {
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
                if(bounds.outWidth>0&&bounds.outHeight>0){BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/opts.inSampleSize>640)opts.inSampleSize*=2;
                    try(InputStream in=context.getContentResolver().openInputStream(uri)){Bitmap bitmap=BitmapFactory.decodeStream(in,null,opts);if(bitmap!=null)data=encode(bitmap);}}
            }catch(Exception ignored){ }
            final String encoded=data;
            main.post(() -> {if(!current(account,token))return;if(operation!=photoOperation){callback.complete(false,"A newer photo choice was kept");return;}if(encoded==null){state="Photo could not be read";notifyChanged();callback.complete(false,state);return;}writePhoto(encoded,callback);});
        });
    }
    public void removePhoto(Result callback) { photoOperation++;if(uid.isEmpty()){callback.complete(false,"Sign in again");return;}writePhoto("",callback); }
    private void writePhoto(String encoded,Result callback) {
        final String account=uid;final long token=generation;
        pendingWrites++;state="Syncing profile photo…";notifyChanged();
        Map<String,Object> changes=new HashMap<>();changes.put("accountPhoto",encoded);changes.put("profileUpdatedAt",ServerValue.TIMESTAMP);
        reference.updateChildren(changes).addOnCompleteListener(task -> {if(current(account,token)){pendingWrites=Math.max(0,pendingWrites-1);state=task.isSuccessful()?"Profile photo synced":"Photo sync failed";notifyChanged();callback.complete(task.isSuccessful(),state);}});
    }
    private static String encode(Bitmap original) {
        int edge=Math.max(original.getWidth(),original.getHeight());
        Bitmap small=edge>320?Bitmap.createScaledBitmap(original,Math.max(1,original.getWidth()*320/edge),Math.max(1,original.getHeight()*320/edge),true):original;
        try { for(int q=85;q>=35;q-=10){ByteArrayOutputStream bytes=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,q,bytes);if(bytes.size()<=48000)return Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);}return null; }
        finally {if(small!=original)small.recycle();original.recycle();}
    }
}
