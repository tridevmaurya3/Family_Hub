package com.tridev.familyhub.feature.more;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.documents.DocumentsFragment;
import com.tridev.familyhub.feature.familylive.FamilyLiveFragment;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.passwordvault.PasswordVaultFragment;

/** Hub for secondary MVP modules and essential settings. */
public class MoreFragment extends Fragment {
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater i,@Nullable ViewGroup c,@Nullable Bundle s){
        ScrollView scroll=new ScrollView(requireContext());LinearLayout root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(24),dp(20),dp(32));root.setBackgroundColor(requireContext().getColor(R.color.fh_background));scroll.addView(root);
        root.addView(label("More",28,true));root.addView(label("Documents, passwords, family status and settings",14,false));
        root.addView(card("Document Vault","PDFs, images, certificates and expiry records",v->open(new DocumentsFragment())));
        root.addView(card("Password Vault","AES-GCM encrypted credentials",v->open(new PasswordVaultFragment())));
        root.addView(card("Family Live","Visible, permission-based family status",v->open(new FamilyLiveFragment())));
        MaterialSwitch dark=new MaterialSwitch(requireContext());dark.setText("Dark theme");dark.setTextSize(16);dark.setPadding(dp(16),dp(14),dp(16),dp(14));dark.setChecked((getResources().getConfiguration().uiMode&48)==32);dark.setOnCheckedChangeListener((b,on)->AppCompatDelegate.setDefaultNightMode(on?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_NO));root.addView(dark,margin());
        root.addView(card("Backup & Restore","Local database backup is planned for the release hardening phase",v->new MaterialAlertDialogBuilder(requireContext()).setTitle("Backup & Restore").setMessage("Your data currently remains offline on this device. Use Android system backup until encrypted export is added.").setPositiveButton("OK",null).show()));
        root.addView(card("Privacy & About","Family Hub • Version 0.3.1",v->new MaterialAlertDialogBuilder(requireContext()).setTitle("Family Hub").setMessage("Private, offline-first family organizer. Adult location and documents must only be shared with explicit consent.").setPositiveButton("OK",null).show()));return scroll;
    }
    private void open(Fragment f){if(requireActivity() instanceof MainActivity)((MainActivity)requireActivity()).openFeature(f);}
    private TextView label(String s,int z,boolean b){TextView v=new TextView(requireContext());v.setText(s);v.setTextSize(z);v.setTextColor(requireContext().getColor(R.color.fh_on_surface));if(b)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}
    private View card(String title,String detail,View.OnClickListener click){LinearLayout b=new LinearLayout(requireContext());b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(18),dp(16),dp(18),dp(16));b.setBackgroundColor(requireContext().getColor(R.color.fh_surface));TextView a=label(title,18,true);TextView d=label(detail,14,false);d.setTextColor(requireContext().getColor(R.color.fh_on_surface_variant));b.addView(a);b.addView(d);b.setOnClickListener(click);b.setClickable(true);b.setFocusable(true);b.setLayoutParams(margin());return b;}
    private LinearLayout.LayoutParams margin(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);return p;}private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
