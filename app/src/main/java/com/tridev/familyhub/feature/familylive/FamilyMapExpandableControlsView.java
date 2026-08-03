package com.tridev.familyhub.feature.familylive;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Compact bottom map menu that expands horizontally on demand.
 *
 * The view collapses when the menu button is tapped again, when any action is
 * used, or when the map surface is touched. Returning false from the map touch
 * listeners keeps every Google Maps gesture working normally.
 */
public final class FamilyMapExpandableControlsView extends MaterialCardView {

    private final Set<View> mapTouchTargets = Collections.newSetFromMap(
            new WeakHashMap<>()
    );

    @Nullable
    private View actionsContainer;
    @Nullable
    private MaterialButton menuButton;
    @Nullable
    private View legendPanel;
    @Nullable
    private View visibilityAnchor;

    private boolean expanded;

    private final ViewTreeObserver.OnGlobalLayoutListener
            anchorVisibilityListener = this::syncAnchorVisibility;

    public FamilyMapExpandableControlsView(@NonNull Context context) {
        this(context, null);
    }

    public FamilyMapExpandableControlsView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        this(context, attrs, 0);
    }

    public FamilyMapExpandableControlsView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        setClickable(false);
        setFocusable(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        actionsContainer = findViewById(R.id.familyMapActionsContainer);
        menuButton = findViewById(R.id.buttonFamilyMapMenu);

        if (menuButton != null) {
            menuButton.setOnClickListener(ignored -> toggle());
        }

        bindCollapseAfterAction(R.id.buttonFamilyMapFit);
        bindCollapseAfterAction(R.id.buttonFamilyMapRecenter);
        bindCollapseAfterAction(R.id.buttonFamilyMapType);
        bindCollapseAfterAction(R.id.buttonFamilyMapTraffic);

        collapse(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(() -> {
            installAnchorVisibilitySync();
            installMapTouchCollapse();
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        if (visibilityAnchor != null
                && visibilityAnchor.getViewTreeObserver().isAlive()) {
            visibilityAnchor.getViewTreeObserver()
                    .removeOnGlobalLayoutListener(anchorVisibilityListener);
        }
        visibilityAnchor = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) {
            post(() -> collapse(false));
        }
    }

    private void installAnchorVisibilitySync() {
        View root = getRootView();
        if (root == null) {
            return;
        }

        View anchor = root.findViewById(R.id.familyMapControlRail);
        if (anchor == null || anchor == visibilityAnchor) {
            syncAnchorVisibility();
            return;
        }

        if (visibilityAnchor != null
                && visibilityAnchor.getViewTreeObserver().isAlive()) {
            visibilityAnchor.getViewTreeObserver()
                    .removeOnGlobalLayoutListener(anchorVisibilityListener);
        }

        visibilityAnchor = anchor;
        anchor.getViewTreeObserver()
                .addOnGlobalLayoutListener(anchorVisibilityListener);
        syncAnchorVisibility();
    }

    private void syncAnchorVisibility() {
        if (visibilityAnchor == null) {
            return;
        }
        int desiredVisibility = visibilityAnchor.getVisibility();
        if (getVisibility() != desiredVisibility) {
            setVisibility(desiredVisibility);
        }
        if (desiredVisibility == VISIBLE) {
            collapse(false);
        }
    }

    private void bindCollapseAfterAction(int viewId) {
        View action = findViewById(viewId);
        if (action == null) {
            return;
        }
        action.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                post(() -> collapse(true));
            }
            return false;
        });
    }

    private void toggle() {
        if (expanded) {
            collapse(true);
        } else {
            expand();
        }
    }

    private void expand() {
        if (expanded || actionsContainer == null || menuButton == null) {
            return;
        }

        expanded = true;
        updateOwnWidth(R.dimen.family_map_controls_expanded_width);

        actionsContainer.setVisibility(VISIBLE);
        actionsContainer.setAlpha(0F);
        actionsContainer.setTranslationX(18F);
        actionsContainer.animate()
                .alpha(1F)
                .translationX(0F)
                .setDuration(170L)
                .start();

        menuButton.setIconResource(R.drawable.ic_family_map_close);
        menuButton.setContentDescription(
                getContext().getString(
                        R.string.family_map_menu_collapse_description
                )
        );

        View legend = legendPanel();
        if (legend != null) {
            legend.setVisibility(GONE);
        }
    }

    private void collapse(boolean animate) {
        expanded = false;

        if (actionsContainer != null) {
            actionsContainer.animate().cancel();
            if (animate && actionsContainer.getVisibility() == VISIBLE) {
                actionsContainer.animate()
                        .alpha(0F)
                        .translationX(18F)
                        .setDuration(120L)
                        .withEndAction(() -> {
                            actionsContainer.setVisibility(GONE);
                            actionsContainer.setAlpha(1F);
                            actionsContainer.setTranslationX(0F);
                            updateOwnWidth(
                                    R.dimen.family_map_controls_collapsed_width
                            );
                        })
                        .start();
            } else {
                actionsContainer.setVisibility(GONE);
                actionsContainer.setAlpha(1F);
                actionsContainer.setTranslationX(0F);
                updateOwnWidth(
                        R.dimen.family_map_controls_collapsed_width
                );
            }
        } else {
            updateOwnWidth(R.dimen.family_map_controls_collapsed_width);
        }

        if (menuButton != null) {
            menuButton.setIconResource(R.drawable.ic_family_map_menu);
            menuButton.setContentDescription(
                    getContext().getString(
                            R.string.family_map_menu_expand_description
                    )
            );
        }

        View legend = legendPanel();
        if (legend != null && isShown()) {
            legend.setVisibility(VISIBLE);
        }
    }

    private void updateOwnWidth(int dimensionResource) {
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params == null) {
            return;
        }
        int width = getResources().getDimensionPixelSize(dimensionResource);
        if (params.width != width) {
            params.width = width;
            setLayoutParams(params);
        }
    }

    @Nullable
    private View legendPanel() {
        if (legendPanel == null && getRootView() != null) {
            legendPanel = getRootView().findViewById(
                    R.id.familyMapBottomPanel
            );
        }
        return legendPanel;
    }

    private void installMapTouchCollapse() {
        View root = getRootView();
        if (root == null) {
            return;
        }

        View mapHost = root.findViewById(R.id.familyMapHost);
        if (mapHost == null) {
            postDelayed(this::installMapTouchCollapse, 250L);
            return;
        }

        attachCollapseTouchRecursively(mapHost);
        if (mapHost instanceof ViewGroup) {
            ((ViewGroup) mapHost).setOnHierarchyChangeListener(
                    new ViewGroup.OnHierarchyChangeListener() {
                        @Override
                        public void onChildViewAdded(
                                View parent,
                                View child
                        ) {
                            attachCollapseTouchRecursively(child);
                        }

                        @Override
                        public void onChildViewRemoved(
                                View parent,
                                View child
                        ) {
                            mapTouchTargets.remove(child);
                        }
                    }
            );
        }
    }

    private void attachCollapseTouchRecursively(@NonNull View view) {
        if (mapTouchTargets.add(view)) {
            view.setOnTouchListener((target, event) -> {
                if (expanded
                        && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    collapse(true);
                }
                return false;
            });
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                attachCollapseTouchRecursively(group.getChildAt(index));
            }
        }
    }
}
