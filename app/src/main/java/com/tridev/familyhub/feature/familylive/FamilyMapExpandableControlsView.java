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

/**
 * Compact bottom map menu that expands horizontally on demand.
 *
 * The menu collapses when its main button is tapped again, when an action is
 * used, or when the activity restores the map legend after an empty-map tap.
 */
public final class FamilyMapExpandableControlsView extends MaterialCardView {

    @Nullable
    private View actionsContainer;
    @Nullable
    private MaterialButton menuButton;
    @Nullable
    private View legendPanel;
    @Nullable
    private View visibilityAnchor;

    private boolean expanded;
    private int lastAnchorVisibility = Integer.MIN_VALUE;
    private int lastLegendVisibility = Integer.MIN_VALUE;

    private final ViewTreeObserver.OnGlobalLayoutListener
            anchorVisibilityListener = this::syncAnchorVisibility;
    private final ViewTreeObserver.OnGlobalLayoutListener
            legendVisibilityListener = this::syncLegendVisibility;

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
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        actionsContainer = findViewById(R.id.familyMapActionsContainer);
        menuButton = findViewById(R.id.buttonFamilyMapMenu);

        if (menuButton != null) {
            menuButton.setOnClickListener(
                    ignored -> toggleExpandedMenu()
            );
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
        post(this::installVisibilitySync);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeVisibilityListeners();
        visibilityAnchor = null;
        legendPanel = null;
        lastAnchorVisibility = Integer.MIN_VALUE;
        lastLegendVisibility = Integer.MIN_VALUE;
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && lastAnchorVisibility == VISIBLE) {
            post(() -> collapse(false));
        }
    }

    private void installVisibilitySync() {
        View root = getRootView();
        if (root == null) {
            return;
        }

        View anchor = root.findViewById(R.id.familyMapControlRail);
        View legend = root.findViewById(R.id.familyMapBottomPanel);

        if (anchor != visibilityAnchor || legend != legendPanel) {
            removeVisibilityListeners();
            visibilityAnchor = anchor;
            legendPanel = legend;
            lastAnchorVisibility = Integer.MIN_VALUE;
            lastLegendVisibility = Integer.MIN_VALUE;

            if (visibilityAnchor != null) {
                visibilityAnchor.getViewTreeObserver()
                        .addOnGlobalLayoutListener(
                                anchorVisibilityListener
                        );
            }
            if (legendPanel != null) {
                legendPanel.getViewTreeObserver()
                        .addOnGlobalLayoutListener(
                                legendVisibilityListener
                        );
            }
        }

        syncAnchorVisibility();
        syncLegendVisibility();
    }

    private void removeVisibilityListeners() {
        if (visibilityAnchor != null
                && visibilityAnchor.getViewTreeObserver().isAlive()) {
            visibilityAnchor.getViewTreeObserver()
                    .removeOnGlobalLayoutListener(anchorVisibilityListener);
        }
        if (legendPanel != null
                && legendPanel.getViewTreeObserver().isAlive()) {
            legendPanel.getViewTreeObserver()
                    .removeOnGlobalLayoutListener(legendVisibilityListener);
        }
    }

    private void syncAnchorVisibility() {
        if (visibilityAnchor == null) {
            return;
        }

        int desiredVisibility = visibilityAnchor.getVisibility();
        boolean becameVisible = lastAnchorVisibility != VISIBLE
                && desiredVisibility == VISIBLE;

        if (getVisibility() != desiredVisibility) {
            setVisibility(desiredVisibility);
        }

        lastAnchorVisibility = desiredVisibility;
        if (becameVisible) {
            collapse(false);
        }
    }

    private void syncLegendVisibility() {
        if (legendPanel == null) {
            return;
        }

        int currentVisibility = legendPanel.getVisibility();
        boolean becameVisible = lastLegendVisibility != VISIBLE
                && currentVisibility == VISIBLE;
        lastLegendVisibility = currentVisibility;

        if (expanded && becameVisible) {
            collapse(true);
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

    private void toggleExpandedMenu() {
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
}
