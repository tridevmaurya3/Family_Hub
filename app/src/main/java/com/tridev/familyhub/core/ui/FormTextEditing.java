package com.tridev.familyhub.core.ui;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import java.text.BreakIterator;
import java.util.*;

/** Editing-only support, including service windows which cannot host a native floating toolbar. */
public final class FormTextEditing {
    private static final Set<View> fields=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> roots=Collections.newSetFromMap(new WeakHashMap<>());
    private FormTextEditing(){}
    public static void register(Application app){app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){}
        public void onActivityStarted(Activity a){observe(a.getWindow().getDecorView());}
        public void onActivityResumed(Activity a){}
        public void onActivityPaused(Activity a){}
        public void onActivityStopped(Activity a){}
        public void onActivitySaveInstanceState(Activity a,Bundle b){}
        public void onActivityDestroyed(Activity a){}
    });}
    /** Track native fields created for dialog forms, including rows added after opening. */
    public static <T extends EditText> T track(T field){
        field.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){observe(view.getRootView());}
            public void onViewDetachedFromWindow(View view){}
        });
        return field;
    }
    public static void observe(View root){
        apply(root);
        if(!roots.add(root))return;
        // New checklist rows and fragment forms are configured as soon as they enter this window.
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->apply(root));
    }
    public static void apply(View view){
        if(view instanceof EditText)configure((EditText)view);
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)apply(g.getChildAt(i));}
    }
    private static void configure(EditText input){
        if(input.getKeyListener()==null || !input.isFocusable() || input.getInputType()==InputType.TYPE_NULL)return;
        int type=input.getInputType(),variation=type&InputType.TYPE_MASK_VARIATION;
        if(input.getTransformationMethod() instanceof PasswordTransformationMethod
                || ((type&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_TEXT
                && (variation==InputType.TYPE_TEXT_VARIATION_PASSWORD || variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || ((type&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_NUMBER && variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD))return;
        if(!fields.add(input))return;
        input.setLongClickable(true);
        ActionMode.Callback callback=new ActionMode.Callback(){
            public boolean onCreateActionMode(ActionMode mode,Menu menu){populate(input,menu);return true;}
            public boolean onPrepareActionMode(ActionMode mode,Menu menu){populate(input,menu);return true;}
            public boolean onActionItemClicked(ActionMode mode,MenuItem item){return false;}
            public void onDestroyActionMode(ActionMode mode){}
        };
        // Keep platform selection handles, keyboard shortcuts and Android's text processing actions.
        if(input.getCustomSelectionActionModeCallback()==null)input.setCustomSelectionActionModeCallback(callback);
        if(input.getCustomInsertionActionModeCallback()==null)input.setCustomInsertionActionModeCallback(callback);
        // Use the same anchored editing menu on phones whose floating ActionMode is suppressed.
        input.setOnLongClickListener(v->{selectWord(input);show(input);return true;});
    }
    private static void populate(EditText field,Menu menu){
        if(!field.isEnabled())return;
        if(field.length()>0)add(menu,android.R.id.selectAll,android.R.string.selectAll);
        int a=field.getSelectionStart(),b=field.getSelectionEnd();
        if(a>=0&&b>=0&&a!=b){add(menu,android.R.id.copy,android.R.string.copy);add(menu,android.R.id.cut,android.R.string.cut);}
        ClipboardManager clipboard=(ClipboardManager)field.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard!=null&&clipboard.hasPrimaryClip())add(menu,android.R.id.paste,android.R.string.paste);
    }
    private static void add(Menu menu,int id,int label){if(menu.findItem(id)==null)menu.add(Menu.NONE,id,Menu.NONE,label).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);}
    private static void selectWord(EditText field){
        field.requestFocus();int a=Math.max(0,field.getSelectionStart()),b=Math.max(0,field.getSelectionEnd());
        if(a!=b||field.length()==0)return;
        String text=field.getText().toString();int cursor=Math.min(a,text.length()-1);
        BreakIterator words=BreakIterator.getWordInstance();words.setText(text);
        int start=words.preceding(cursor+1),end=words.following(cursor);
        if(start<0)start=0;if(end<0)end=text.length();field.setSelection(start,end);
    }
    private static void show(EditText field){
        if(!field.isAttachedToWindow()||!field.isEnabled())return;
        PopupMenu popup=new PopupMenu(field.getContext(),field);populate(field,popup.getMenu());
        popup.setOnMenuItemClickListener(item->{
            boolean handled=field.onTextContextMenuItem(item.getItemId());
            if(item.getItemId()==android.R.id.selectAll)field.post(()->show(field));
            return handled;
        });
        if(popup.getMenu().size()>0)try{popup.show();}catch(WindowManager.BadTokenException ignored){}
    }
}
