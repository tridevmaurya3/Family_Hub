package com.tridev.familyhub.feature.profile;

import android.app.*;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.*;
import android.widget.ImageView;
import androidx.core.widget.ImageViewCompat;
import com.tridev.familyhub.R;
import java.util.*;

/** Changes only existing profile header images; their IDs, click handlers and layout stay intact. */
public final class ProfileHeaderAvatarBinder implements Application.ActivityLifecycleCallbacks {
    private final Map<Activity,Binding> bindings=new HashMap<>();
    public static void register(Application app){app.registerActivityLifecycleCallbacks(new ProfileHeaderAvatarBinder());}
    public void onActivityStarted(Activity a){Binding b=new Binding(a);bindings.put(a,b);b.start();}
    public void onActivityStopped(Activity a){Binding b=bindings.remove(a);if(b!=null)b.stop();}
    public void onActivityCreated(Activity a,Bundle s){} public void onActivityResumed(Activity a){} public void onActivityPaused(Activity a){}
    public void onActivitySaveInstanceState(Activity a,Bundle s){} public void onActivityDestroyed(Activity a){onActivityStopped(a);}
    private static final class Binding implements FamilyAvatarStore.Listener,android.view.ViewTreeObserver.OnGlobalLayoutListener {
        final Activity activity;final View root;final FamilyAvatarStore store;
        final Map<ImageView,State> states=new WeakHashMap<>();
        Binding(Activity a){activity=a;root=a.getWindow().getDecorView();store=FamilyAvatarStore.get(a);}
        boolean subscribed;
        void start(){root.getViewTreeObserver().addOnGlobalLayoutListener(this);render(root);}
        void stop(){if(subscribed)store.removeListener(this);if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(this);states.clear();}
        public void onAvatarsChanged(){render(root);}public void onGlobalLayout(){render(root);}
        void render(View v){if(v instanceof ImageView){ImageView image=(ImageView)v;CharSequence desc=image.getContentDescription();
            if(image.getId()==R.id.dashboard_profile_avatar || (desc!=null&&desc.toString().equals(activity.getString(R.string.more_profile_title)))){
                if(!subscribed){subscribed=true;store.addListener(this);}
                State state=states.get(image);if(state==null){state=new State(image);states.put(image,state);}Bitmap photo=store.ownPhoto();
                if(photo!=state.applied || !state.rendered){state.rendered=true;state.applied=photo;
                    if(photo==null){image.setPadding(state.left,state.top,state.right,state.bottom);image.setScaleType(state.scale);ImageViewCompat.setImageTintList(image,state.tint);image.setImageResource(R.drawable.ic_profile_person);image.setOutlineProvider(state.outline);image.setClipToOutline(state.clip);}
                    else{ImageViewCompat.setImageTintList(image,null);image.clearColorFilter();image.setPadding(0,0,0,0);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View view,Outline outline){outline.setOval(0,0,view.getWidth(),view.getHeight());}});image.setClipToOutline(true);image.setImageBitmap(photo);}
                }
            }}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)render(g.getChildAt(i));}}
    }
    private static final class State {
        final int left,top,right,bottom;final ImageView.ScaleType scale;final android.content.res.ColorStateList tint;final ViewOutlineProvider outline;final boolean clip;
        Bitmap applied;boolean rendered;
        State(ImageView v){left=v.getPaddingLeft();top=v.getPaddingTop();right=v.getPaddingRight();bottom=v.getPaddingBottom();scale=v.getScaleType();tint=ImageViewCompat.getImageTintList(v);outline=v.getOutlineProvider();clip=v.getClipToOutline();}
    }
}
