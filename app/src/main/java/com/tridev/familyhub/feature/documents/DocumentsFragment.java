package com.tridev.familyhub.feature.documents;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.DocumentDao;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.feature.main.AddActionHost;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline document vault backed by persisted Storage Access Framework permissions. */
public class DocumentsFragment extends Fragment implements AddActionHost {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DocumentAdapter adapter = new DocumentAdapter();
    private DocumentDao dao;
    private TextView emptyView;
    private String pendingTitle;
    private String pendingCategory;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onDocumentPicked);

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(24), pad, 0);
        root.setBackgroundColor(requireContext().getColor(R.color.fh_background));

        TextView title = text("Document Vault", 28, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = text("Secure links to your PDFs, images and certificates", 14, false);
        subtitle.setTextColor(requireContext().getColor(R.color.fh_on_surface_variant));
        root.addView(subtitle, marginTop(4));

        TextInputLayout searchBox = new TextInputLayout(requireContext());
        searchBox.setHint("Search documents or categories");
        searchBox.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        TextInputEditText search = new TextInputEditText(requireContext());
        searchBox.addView(search);
        root.addView(searchBox, marginTop(20));

        RecyclerView list = new RecyclerView(requireContext());
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, dp(12), 0, dp(104));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        emptyView = text("No documents yet\nTap + to add a PDF or image", 16, false);
        emptyView.setGravity(android.view.Gravity.CENTER);
        root.addView(emptyView, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){load(s.toString());} public void afterTextChanged(android.text.Editable e){}
        });
        return root;
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        dao = FamilyHubDatabase.getInstance(requireContext()).documentDao();
        adapter.onOpen = this::openDocument;
        adapter.onDelete = this::deleteDocument;
        load("");
    }

    @Override public void onAddRequested() {
        LinearLayout form = new LinearLayout(requireContext()); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), 0, dp(24), 0);
        EditText title = new EditText(requireContext()); title.setHint("Document title"); form.addView(title);
        EditText category = new EditText(requireContext()); category.setHint("Category (Aadhaar, PAN, Insurance…)"); form.addView(category);
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Add document").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Choose file", (d,w) -> {
                    pendingTitle = title.getText().toString().trim(); pendingCategory = category.getText().toString().trim();
                    if (pendingTitle.isEmpty()) pendingTitle = "Untitled document";
                    if (pendingCategory.isEmpty()) pendingCategory = "Other";
                    picker.launch(new String[]{"application/pdf", "image/*"});
                }).show();
    }

    private void onDocumentPicked(Uri uri) {
        if (uri == null || pendingTitle == null) return;
        try { requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
        DocumentEntry entry = new DocumentEntry(); entry.title = pendingTitle; entry.category = pendingCategory;
        entry.contentUri = uri.toString(); entry.mimeType = requireContext().getContentResolver().getType(uri) == null ? "" : requireContext().getContentResolver().getType(uri); entry.createdAt = System.currentTimeMillis();
        executor.execute(() -> { entry.id = dao.insert(entry); requireActivity().runOnUiThread(() -> { Toast.makeText(requireContext(), "Document added", Toast.LENGTH_SHORT).show(); load(""); }); });
        pendingTitle = null; pendingCategory = null;
    }

    private void load(String query) { if (dao == null) return; executor.execute(() -> { List<DocumentEntry> rows = query.trim().isEmpty() ? dao.getAll() : dao.search(query.trim()); if (getActivity()!=null) requireActivity().runOnUiThread(() -> { adapter.set(rows); emptyView.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE); }); }); }
    private void openDocument(DocumentEntry entry) { try { Intent i=new Intent(Intent.ACTION_VIEW, Uri.parse(entry.contentUri)); i.setDataAndType(Uri.parse(entry.contentUri), entry.mimeType.isEmpty()?"*/*":entry.mimeType); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(i); } catch(Exception e){Toast.makeText(requireContext(),"File is no longer available",Toast.LENGTH_LONG).show();} }
    private void deleteDocument(DocumentEntry entry) { new MaterialAlertDialogBuilder(requireContext()).setTitle("Remove document?").setMessage("The original file will not be deleted.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->executor.execute(()->{dao.delete(entry); if(getActivity()!=null)requireActivity().runOnUiThread(()->load(""));})).show(); }
    private TextView text(String value,int sp,boolean bold){TextView v=new TextView(requireContext());v.setText(value);v.setTextSize(sp);v.setTextColor(requireContext().getColor(R.color.fh_on_surface));if(bold)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}
    private LinearLayout.LayoutParams marginTop(int dp){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(dp);return p;} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private static class DocumentAdapter extends RecyclerView.Adapter<DocumentAdapter.Holder>{
        interface Action{void run(DocumentEntry e);} final List<DocumentEntry> items=new ArrayList<>(); Action onOpen,onDelete;
        void set(List<DocumentEntry> rows){items.clear();items.addAll(rows);notifyDataSetChanged();}
        @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup p,int t){LinearLayout box=new LinearLayout(p.getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,22,24,22);TextView a=new TextView(p.getContext());a.setTextSize(17);a.setTypeface(a.getTypeface(),android.graphics.Typeface.BOLD);TextView b=new TextView(p.getContext());b.setTextSize(13);box.addView(a);box.addView(b);RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,-2);lp.bottomMargin=12;box.setLayoutParams(lp);box.setBackgroundColor(p.getContext().getColor(R.color.fh_surface));return new Holder(box,a,b);}
        public void onBindViewHolder(@NonNull Holder h,int pos){DocumentEntry e=items.get(pos);h.title.setText(e.title);h.detail.setText(e.category+"  •  Tap to open  •  Hold to remove");h.itemView.setOnClickListener(v->onOpen.run(e));h.itemView.setOnLongClickListener(v->{onDelete.run(e);return true;});} public int getItemCount(){return items.size();}
        static class Holder extends RecyclerView.ViewHolder{TextView title,detail;Holder(View v,TextView a,TextView b){super(v);title=a;detail=b;}}
    }
}
