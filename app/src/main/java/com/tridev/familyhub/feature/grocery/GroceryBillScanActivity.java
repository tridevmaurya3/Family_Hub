package com.tridev.familyhub.feature.grocery;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.repository.GroceryRepository;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a bill image locally and asks before adding any detected row. */
public class GroceryBillScanActivity extends AppCompatActivity {
    private static final Pattern ROW = Pattern.compile(
            "^(.+?)\\s+(?:₹|Rs\\.?\\s*)?(\\d+(?:\\.\\d{1,2})?)$");
    private final ArrayList<Draft> drafts = new ArrayList<>();
    private final ActivityResultLauncher<String> picker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::scan);

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state); picker.launch("image/*");
    }

    private void scan(@Nullable Uri uri) {
        if (uri == null) { finish(); return; }
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image).addOnSuccessListener(this::confirm)
                    .addOnFailureListener(error -> fail());
        } catch (Exception error) { fail(); }
    }

    private void confirm(Text text) {
        drafts.clear();
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            Matcher match = ROW.matcher(line.getText().trim());
            if (!match.matches()) continue;
            String name = match.group(1).replaceAll("[^\\p{L}0-9 &'()-]", "").trim();
            if (name.length() < 2 || name.toLowerCase(Locale.ENGLISH).contains("total")) continue;
            try { drafts.add(new Draft(name, Double.parseDouble(match.group(2)))); }
            catch (NumberFormatException ignored) { }
        }
        if (drafts.isEmpty()) { fail(); return; }
        String[] labels = new String[drafts.size()]; boolean[] checked = new boolean[drafts.size()];
        for (int i=0;i<drafts.size();i++) { checked[i]=true; labels[i]=drafts.get(i).name+"  •  ₹"+drafts.get(i).price; }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.grocery_bill_confirm)
                .setMultiChoiceItems(labels, checked, (d,w,c)->checked[w]=c)
                .setNegativeButton(R.string.cancel,(d,w)->finish())
                .setPositiveButton(R.string.grocery_bill_add,(d,w)->save(checked)).show();
    }

    private void save(boolean[] checked) {
        GroceryRepository repository = new GroceryRepository(this); int[] remaining={0};
        for (boolean value:checked) if(value) remaining[0]++;
        if (remaining[0]==0) { finish(); return; }
        for (int i=0;i<drafts.size();i++) if(checked[i]) {
            Draft draft=drafts.get(i); GroceryItem item=new GroceryItem();
            item.name=draft.name; item.category=getString(R.string.grocery_uncategorized);
            item.estimatedCost=draft.price;
            repository.save(item,()->{ if(--remaining[0]==0){ Toast.makeText(this,R.string.grocery_bill_added,Toast.LENGTH_SHORT).show(); finish(); }});
        }
    }
    private void fail(){ Toast.makeText(this,R.string.grocery_bill_no_items,Toast.LENGTH_LONG).show(); finish(); }
    private static final class Draft { final String name; final double price; Draft(String n,double p){name=n;price=p;} }
}
