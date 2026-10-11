package com.tridev.familyhub.feature.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only, UID-based account avatars for the currently authorised family. */
public final class FamilyAvatarStore {
    public interface Listener { void onAvatarsChanged(); }
    private static FamilyAvatarStore instance;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners=new LinkedHashSet<>();
    private final Set<String> allowed=new HashSet<>(),records=new HashSet<>();
    private final Map<String,DatabaseReference> refs=new HashMap<>();
    private final Map<String,ValueEventListener> feeds=new HashMap<>();
    private final Map<String,Long> versions=new HashMap<>();
    private final LruCache<String,Bitmap> photos=new LruCache<String,Bitmap>(8192){protected int sizeOf(String key,Bitmap value){return Math.max(1,value.getByteCount()/1024);}};
    private DatabaseReference memberships;
    private ValueEventListener memberFeed;
    private String account="",family="";
    private long generation;
    private final AccountProfileSync.Listener accountFeed=this::rebind;
    private FamilyAvatarStore(Context c){context=c.getApplicationContext();}
    public static synchronized FamilyAvatarStore get(Context c){if(instance==null)instance=new FamilyAvatarStore(c);return instance;}
    public void addListener(Listener l){boolean start=listeners.isEmpty();listeners.add(l);if(start)AccountProfileSync.get(context).addListener(accountFeed);l.onAvatarsChanged();}
    public void removeListener(Listener l){listeners.remove(l);if(listeners.isEmpty()){AccountProfileSync.get(context).removeListener(accountFeed);detach();account="";family="";}}
    public Bitmap photo(String uid){return allowed.contains(uid)?photos.get(uid):null;}
    public boolean hasRecord(String uid){return allowed.contains(uid)&&records.contains(uid);}
    public Bitmap ownPhoto(){return photo(account);}
    private void changed(){for(Listener l:new ArrayList<>(listeners))l.onAvatarsChanged();}
    private void detach(){generation++;if(memberships!=null&&memberFeed!=null)memberships.removeEventListener(memberFeed);memberships=null;memberFeed=null;for(String uid:new ArrayList<>(refs.keySet()))unwatch(uid);allowed.clear();records.clear();photos.evictAll();}
    private void unwatch(String uid){DatabaseReference ref=refs.remove(uid);ValueEventListener listener=feeds.remove(uid);if(ref!=null&&listener!=null)ref.removeEventListener(listener);versions.remove(uid);records.remove(uid);photos.remove(uid);}
    private void rebind(){
        FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();String next=user==null?"":user.getUid();
        String nextFamily=AccountProfileSync.get(context).familyId();
        if(next.equals(account)&&nextFamily.equals(family))return;
        detach();account=next;family=nextFamily;changed();if(next.isEmpty())return;
        final long token=generation;allowed.add(account);watch(account,token);
        final String own=account;
        io.execute(()->{Bitmap image=null;try{image=ProfilePhotoStore.load(context,own);}catch(Exception|OutOfMemoryError ignored){}final Bitmap cached=image;main.post(()->{if(token==generation&&!records.contains(own)&&!versions.containsKey(own)&&cached!=null){photos.put(own,cached);changed();}});});
        if(family.isEmpty())return;
        memberships=FirebaseDatabase.getInstance().getReference("memberships").child(family);
        memberFeed=new ValueEventListener(){
            public void onDataChange(DataSnapshot snapshot){if(token!=generation)return;Set<String> active=new HashSet<>();active.add(account);
                // The viewer must remain ACTIVE; revocation immediately clears other people's avatars.
                if("ACTIVE".equals(snapshot.child(account).child("status").getValue())){
                    for(DataSnapshot member:snapshot.getChildren())if("ACTIVE".equals(member.child("status").getValue())&&member.getKey()!=null)active.add(member.getKey());
                }
                for(String uid:new ArrayList<>(allowed))if(!active.contains(uid)){allowed.remove(uid);unwatch(uid);}
                allowed.addAll(active);for(String uid:active)watch(uid,token);changed();
            }
            public void onCancelled(DatabaseError error){if(token!=generation)return;for(String uid:new ArrayList<>(allowed))if(!uid.equals(account)){allowed.remove(uid);unwatch(uid);}changed();}
        };memberships.addValueEventListener(memberFeed);
    }
    private void watch(String uid,long token){
        if(refs.containsKey(uid))return;
        DatabaseReference ref=FirebaseDatabase.getInstance().getReference("users").child(uid).child("accountPhoto");
        ValueEventListener feed=new ValueEventListener(){
            public void onDataChange(DataSnapshot snapshot){if(token!=generation||!allowed.contains(uid))return;
                long version=versions.getOrDefault(uid,0L)+1;versions.put(uid,version);
                if(snapshot.exists())records.add(uid);else records.remove(uid);
                Object raw=snapshot.getValue();String data=raw instanceof String?(String)raw:"";
                if(data.isEmpty()||data.length()>64000){photos.remove(uid);changed();return;}
                io.execute(()->{Bitmap bitmap=null;try{byte[] bytes=Base64.decode(data,Base64.DEFAULT);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth<=640&&bounds.outHeight<=640){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>160)options.inSampleSize*=2;bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);}}catch(Exception|OutOfMemoryError ignored){}
                    final Bitmap decoded=bitmap;main.post(()->{if(token!=generation||!allowed.contains(uid)||!Objects.equals(versions.get(uid),version))return;if(decoded==null)photos.remove(uid);else photos.put(uid,decoded);changed();});});
            }
            public void onCancelled(DatabaseError error){if(token!=generation)return;versions.put(uid,versions.getOrDefault(uid,0L)+1);photos.remove(uid);changed();}
        };refs.put(uid,ref);feeds.put(uid,feed);ref.addValueEventListener(feed);
    }
}
