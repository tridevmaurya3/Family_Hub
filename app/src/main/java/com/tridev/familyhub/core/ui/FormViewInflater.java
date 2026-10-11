package com.tridev.familyhub.core.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import androidx.appcompat.widget.AppCompatEditText;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.theme.MaterialComponentsViewInflater;

/** Applies editing support to XML fields in Activity and dialog windows alike. */
public final class FormViewInflater extends MaterialComponentsViewInflater {
    @Override protected AppCompatEditText createEditText(Context context,AttributeSet attrs){
        AppCompatEditText field=super.createEditText(context,attrs);
        FormTextEditing.apply(field);return field;
    }
    @Override protected View createView(Context context,String name,AttributeSet attrs){
        if("com.google.android.material.textfield.TextInputEditText".equals(name)){
            TextInputEditText field=new TextInputEditText(context,attrs);
            FormTextEditing.apply(field);return field;
        }
        return super.createView(context,name,attrs);
    }
}
