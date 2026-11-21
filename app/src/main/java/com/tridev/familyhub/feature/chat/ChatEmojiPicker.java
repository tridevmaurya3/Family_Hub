package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

final class ChatEmojiPicker {
    private static Map<String, String[]> categories;

    private ChatEmojiPicker() {}

    static void show(Context context, Consumer<String> onEmoji) {
        try {
            if (categories == null) categories = readCategories(context);
        } catch (IOException error) {
            Toast.makeText(context, "Unable to load emojis", Toast.LENGTH_SHORT).show();
            return;
        }
        ChatActionDialog design = new ChatActionDialog(context, "Emoji", 0);
        HorizontalScrollView tabs = new HorizontalScrollView(context);
        LinearLayout categoryRow = new LinearLayout(context);
        tabs.addView(categoryRow);
        design.add(design.content, tabs, 6);

        GridView grid = new GridView(context);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(design.dp(43));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        design.content.addView(grid, new LinearLayout.LayoutParams(-1, design.dp(196)));

        androidx.appcompat.app.AlertDialog dialog = new ChatFormDialog(context)
                .setView(design.content).setNegativeButton("Close", null).create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            onEmoji.accept((String) parent.getItemAtPosition(position));
            dialog.dismiss();
        });
        for (Map.Entry<String, String[]> category : categories.entrySet()) {
            MaterialButton tab = design.button(category.getKey(), false);
            tab.setCheckable(true);
            categoryRow.addView(tab, new LinearLayout.LayoutParams(-2, -2));
            tab.setOnClickListener(view -> {
                for (int i = 0; i < categoryRow.getChildCount(); i++) {
                    MaterialButton item = (MaterialButton) categoryRow.getChildAt(i);
                    item.setChecked(item == tab);
                }
                grid.setAdapter(new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, category.getValue()) {
                    @Override public android.view.View getView(int position, android.view.View recycled,
                                                               android.view.ViewGroup parent) {
                        TextView cell = (TextView) super.getView(position, recycled, parent);
                        cell.setGravity(Gravity.CENTER);
                        cell.setTextSize(24);
                        cell.setPadding(0, 0, 0, 0);
                        cell.setMinHeight(design.dp(48));
                        return cell;
                    }
                });
                grid.setSelection(0);
            });
        }
        if (categoryRow.getChildCount() > 0) categoryRow.getChildAt(0).performClick();
        dialog.show();
    }

    private static Map<String, String[]> readCategories(Context context) throws IOException {
        Map<String, String[]> groups = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("chat/emoji-15.1.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                int split = line.indexOf('\t');
                if (split >= 0) groups.put(line.substring(0, split), line.substring(split + 1).split(" "));
            }
        }
        return groups;
    }
}
