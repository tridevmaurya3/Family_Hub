package com.tridev.familyhub.core.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Keeps form controls reachable without letting them consume the item-list viewport. */
public class ScrollableWorkspaceLayout extends LinearLayout {
    private ControlsScroll controls;
    public ScrollableWorkspaceLayout(Context context) { super(context); setOrientation(VERTICAL); }

    public ScrollableWorkspaceLayout(Context context, android.util.AttributeSet attrs) { super(context, attrs); setOrientation(VERTICAL); }

    /** Notes-style single scroll stream; callers opt in after attaching their item adapter. */
    public void makeWorkspaceScrollable(int first, android.view.ViewGroup listHost,
            androidx.recyclerview.widget.RecyclerView list, View emptyState) {
        LinearLayout fields = new LinearLayout(getContext());
        fields.setOrientation(VERTICAL);
        while (indexOfChild(listHost) > first) {
            View child = getChildAt(first);
            android.view.ViewGroup.LayoutParams params = child.getLayoutParams();
            removeViewAt(first);
            fields.addView(child, params);
        }
        // The empty message belongs to the same stream so an empty/filtered list
        // never hides the quick-add controls or blocks scrolling back to them.
        listHost.removeView(emptyState);
        fields.addView(emptyState, new LinearLayout.LayoutParams(-1, -2));
        androidx.recyclerview.widget.RecyclerView.Adapter<?> items = list.getAdapter();
        if (items == null) throw new IllegalStateException("Attach the item adapter first");
        androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder> header =
                new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @Override public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(
                    android.view.ViewGroup parent, int viewType) {
                fields.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2));
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(fields) {};
            }
            @Override public void onBindViewHolder(
                    androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) { }
            @Override public int getItemCount() { return 1; }
        };
        list.setAdapter(new androidx.recyclerview.widget.ConcatAdapter(header, items));
    }

    /** Wrap only controls; keep the header and weighted item list outside this scroll region. */
    public void makeControlsScrollable(int first, int end) {
        LinearLayout fields = new LinearLayout(getContext());
        fields.setOrientation(VERTICAL);
        int count = end - first;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(first);
            android.view.ViewGroup.LayoutParams params = child.getLayoutParams();
            removeViewAt(first);
            fields.addView(child, params);
        }
        // Inflate scrollbar attributes with the View constructor. Enabling bars
        // later can leave ScrollBarDrawable null in a programmatic NestedScrollView.
        controls = (ControlsScroll) android.view.LayoutInflater.from(getContext())
                .inflate(com.tridev.familyhub.R.layout.workspace_controls_scroll, this, false);
        controls.addView(fields, new ScrollView.LayoutParams(-1, -2));
        addView(controls, first, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (controls != null && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            int available = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
            // Reserve room for the fixed header before splitting controls and items.
            for (int i = 0; i < indexOfChild(controls); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() != GONE && child.getLayoutParams().height > 0)
                    available -= child.getLayoutParams().height;
            }
            controls.limit = Math.max(0, available / 2);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    public static final class ControlsScroll extends androidx.core.widget.NestedScrollView {
        int limit = Integer.MAX_VALUE;
        public ControlsScroll(Context context, android.util.AttributeSet attrs) {
            super(context, attrs);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                    ? limit : Math.min(limit, MeasureSpec.getSize(heightSpec));
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
        }
    }
}
