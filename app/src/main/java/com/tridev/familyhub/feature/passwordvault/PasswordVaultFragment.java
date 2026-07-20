package com.tridev.familyhub.feature.passwordvault;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.VaultCipher;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.PasswordEntryDao;
import com.tridev.familyhub.data.local.entity.PasswordEntry;
import com.tridev.familyhub.feature.main.AddActionHost;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local credential vault. Secret values are AES-GCM encrypted with Android Keystore. */
public class PasswordVaultFragment extends Fragment implements AddActionHost {
    private final ExecutorService executor=Executors.newSingleThreadExecutor(); private final Adapter adapter=new Adapter(); private PasswordEntryDao dao; private TextView empty;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater i,@Nullable ViewGroup c,@Nullable Bundle s){LinearLayout root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(24),dp(20),0);root.setBackgroundColor(requireContext().getColor(R.color.fh_background));TextView title=t("Password Vault",28,true);root.addView(title);TextView sub=t("Encrypted on this device with Android Keystore",14,false);sub.setTextColor(requireContext().getColor(R.color.fh_on_surface_variant));root.addView(sub);RecyclerView list=new RecyclerView(requireContext());list.setLayoutManager(new LinearLayoutManager(requireContext()));list.setAdapter(adapter);list.setPadding(0,dp(20),0,dp(104));list.setClipToPadding(false);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));empty=t("No passwords yet\nTap + to save a credential",16,false);empty.setGravity(17);root.addView(empty,new LinearLayout.LayoutParams(-1,0,1));return root;}
    @Override public void onViewCreated(@NonNull View v,@Nullable Bundle s){dao=FamilyHubDatabase.getInstance(requireContext()).passwordEntryDao();adapter.open=this::showDetails;adapter.delete=this::delete;load();}
    @Override public void onResume(){super.onResume();requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    @Override public void onPause(){requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);super.onPause();}
    @Override public void onAddRequested(){showEditor(null);}
    private EditText field(String hint){EditText e=new EditText(requireContext());e.setHint(hint);e.setSingleLine(hint.equals("Notes")?false:true);return e;}
    private void showEditor(@Nullable PasswordEntry existing){LinearLayout f=new LinearLayout(requireContext());f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(24),0,dp(24),0);EditText title=field("Title");EditText site=field("Website");EditText user=field("Username");EditText pass=field("Password");pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);EditText notes=field("Notes");f.addView(title);f.addView(site);f.addView(user);f.addView(pass);f.addView(notes);if(existing!=null){title.setText(existing.title);site.setText(existing.website);user.setText(VaultCipher.decrypt(existing.usernameEncrypted));pass.setText(VaultCipher.decrypt(existing.passwordEncrypted));notes.setText(VaultCipher.decrypt(existing.notesEncrypted));}new MaterialAlertDialogBuilder(requireContext()).setTitle(existing==null?"Add credential":"Edit credential").setView(f).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{if(title.getText().toString().trim().isEmpty()||pass.getText().toString().isEmpty()){Toast.makeText(requireContext(),"Title and password are required",Toast.LENGTH_LONG).show();return;}PasswordEntry e=existing==null?new PasswordEntry():existing;e.title=title.getText().toString().trim();e.website=site.getText().toString().trim();e.usernameEncrypted=VaultCipher.encrypt(user.getText().toString());e.passwordEncrypted=VaultCipher.encrypt(pass.getText().toString());e.notesEncrypted=VaultCipher.encrypt(notes.getText().toString());if(e.createdAt==0)e.createdAt=System.currentTimeMillis();executor.execute(()->{if(e.id==0)e.id=dao.insert(e);else dao.update(e);if(getActivity()!=null)requireActivity().runOnUiThread(this::load);});}).show();}
    private void showDetails(PasswordEntry e){String message="Website: "+e.website+"\nUsername: "+VaultCipher.decrypt(e.usernameEncrypted)+"\nPassword: "+VaultCipher.decrypt(e.passwordEncrypted)+"\n\n"+VaultCipher.decrypt(e.notesEncrypted);new MaterialAlertDialogBuilder(requireContext()).setTitle(e.title).setMessage(message).setNegativeButton("Close",null).setNeutralButton("Edit",(d,w)->showEditor(e)).show();}
    private void delete(PasswordEntry e){new MaterialAlertDialogBuilder(requireContext()).setTitle("Delete credential?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->executor.execute(()->{dao.delete(e);if(getActivity()!=null)requireActivity().runOnUiThread(this::load);})).show();}
    private void load(){executor.execute(()->{List<PasswordEntry> rows=dao.getAll();if(getActivity()!=null)requireActivity().runOnUiThread(()->{adapter.set(rows);empty.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);});});}
    private TextView t(String s,int z,boolean b){TextView v=new TextView(requireContext());v.setText(s);v.setTextSize(z);v.setTextColor(requireContext().getColor(R.color.fh_on_surface));if(b)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private static class Adapter extends RecyclerView.Adapter<Adapter.H>{interface A{void go(PasswordEntry e);}List<PasswordEntry> rows=new ArrayList<>();A open,delete;void set(List<PasswordEntry> x){rows.clear();rows.addAll(x);notifyDataSetChanged();}@NonNull public H onCreateViewHolder(@NonNull ViewGroup p,int t){LinearLayout b=new LinearLayout(p.getContext());b.setOrientation(LinearLayout.VERTICAL);b.setPadding(24,22,24,22);b.setBackgroundColor(p.getContext().getColor(R.color.fh_surface));TextView a=new TextView(p.getContext());a.setTextSize(17);a.setTypeface(a.getTypeface(),android.graphics.Typeface.BOLD);TextView c=new TextView(p.getContext());b.addView(a);b.addView(c);RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,-2);lp.bottomMargin=12;b.setLayoutParams(lp);return new H(b,a,c);}public void onBindViewHolder(@NonNull H h,int p){PasswordEntry e=rows.get(p);h.a.setText(e.title);h.b.setText(e.website+"  •  Tap to view  •  Hold to delete");h.itemView.setOnClickListener(v->open.go(e));h.itemView.setOnLongClickListener(v->{delete.go(e);return true;});}public int getItemCount(){return rows.size();}static class H extends RecyclerView.ViewHolder{TextView a,b;H(View v,TextView x,TextView y){super(v);a=x;b=y;}}}
}
