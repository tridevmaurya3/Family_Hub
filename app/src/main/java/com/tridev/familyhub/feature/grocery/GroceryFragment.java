package com.tridev.familyhub.feature.grocery;

import android.os.Bundle;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.speech.RecognizerIntent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.transition.TransitionManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.gms.location.LocationServices;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.databinding.DialogGroceryBinding;
import com.tridev.familyhub.databinding.FragmentGroceryBinding;
import com.tridev.familyhub.feature.main.AddActionHost;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.grocery.overlay.GroceryOverlayService;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.File;
import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline-first family Grocery and Shopping List screen. */
public class GroceryFragment extends Fragment implements AddActionHost {

    private static final String[] PRIORITIES = {
            GroceryItem.PRIORITY_NORMAL,
            GroceryItem.PRIORITY_HIGH,
            GroceryItem.PRIORITY_URGENT
    };

    private static final int MENU_CYCLE_DAILY = 21_001;
    private static final int MENU_CYCLE_MONTHLY = 21_002;
    private static final int MENU_CYCLE_TWO_MONTH = 21_003;
    private static final int MENU_CYCLE_THREE_MONTH = 21_004;

    private FragmentGroceryBinding binding;
    private GroceryRepository repository;
    private FamilyMemberRepository memberRepository;
    private GroceryAdapter adapter;
    private final List<FamilyMember> familyMembers = new ArrayList<>();
    @NonNull private String activeCycleFilter = GroceryItem.LIST_DAILY;
    private int activeStatusFilterId = R.id.filter_pending;
    @NonNull private String activeCategoryFilter = "";
    @Nullable private MaterialButton groceryCategoryToggleChip;
    @Nullable private MaterialButton groceryCycleDropdown;
    @Nullable private MaterialButton groceryStatusDropdown;
    @Nullable private android.widget.EditText activeDialogVoiceInput;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
    private final ActivityResultLauncher<Intent> voiceLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != android.app.Activity.RESULT_OK
                                || result.getData() == null) {
                            return;
                        }
                        ArrayList<String> matches = result.getData()
                                .getStringArrayListExtra(
                                        RecognizerIntent.EXTRA_RESULTS);
                        if (matches != null && !matches.isEmpty()) {
                            if (activeDialogVoiceInput != null) {
                                activeDialogVoiceInput.setText(matches.get(0).trim());
                                activeDialogVoiceInput.setSelection(
                                        activeDialogVoiceInput.length());
                            } else {
                                addFromVoice(matches.get(0));
                            }
                        }
                    }
            );
    private boolean groceryHeaderCollapsed;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentGroceryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);
        repository = new GroceryRepository(requireContext());
        memberRepository = new FamilyMemberRepository(requireContext());
        adapter = new GroceryAdapter(
                new GroceryAdapter.ItemActionListener() {
                    @Override
                    public void onPurchasedChanged(
                            @NonNull GroceryItem item,
                            boolean purchased
                    ) {
                        if (purchased) {
                            loadItems(currentQuery());
                            showEditor(item, true);
                        } else {
                            repository.setPurchased(item, false,
                                    () -> loadItems(currentQuery()));
                        }
                    }

                    @Override
                    public void onEdit(@NonNull GroceryItem item) {
                        showEditor(item);
                    }

                    @Override
                    public void onDelete(@NonNull GroceryItem item) {
                        confirmDelete(item);
                    }

                    @Override
                    public void onBuying(@NonNull GroceryItem item) {
                        repository.setBuyingStatus(item,
                                GroceryItem.STATUS_BUYING,
                                () -> loadItems(currentQuery()));
                    }

                    @Override
                    public void onGroupingChanged(boolean allCollapsed) {
                        updateGroceryGroupingChip(allCollapsed);
                    }
                }
        );
        binding.groceryRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.groceryRecyclerView.setAdapter(adapter);
        binding.groceryOverview.setNavigationAction(
                R.drawable.ic_arrow_back,
                R.string.back,
                v -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).openHome();
            } else {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        });
        binding.groceryRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 6 && !groceryHeaderCollapsed) {
                    setGroceryHeaderCollapsed(true);
                } else if (!recyclerView.canScrollVertically(-1) && groceryHeaderCollapsed) {
                    setGroceryHeaderCollapsed(false);
                }
            }
        });
        binding.emptyAddGroceryButton.setOnClickListener(
                clickedView -> showEditor(null)
        );
        binding.clearPurchasedButton.setOnClickListener(
                clickedView -> confirmClearPurchased()
        );
        binding.floatingGroceryButton.setOnClickListener(
                clickedView -> toggleFloatingStrip()
        );
        binding.groceryVoiceButton.setOnClickListener(v -> startVoiceAdd());
        binding.groceryVoiceButton.setOnLongClickListener(v -> {
            showRecurringSuggestions();
            return true;
        });
        binding.groceryBudgetButton.setOnClickListener(v -> showBudgetEditor());
        binding.grocerySuggestionsButton.setOnClickListener(
                v -> showRecurringSuggestions());
        binding.groceryShoppingModeButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ShoppingModeActivity.class)));
        binding.groceryScanBillButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), GroceryBillScanActivity.class)));
        binding.groceryStoreAnalyticsButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(),
                        GroceryStoreAnalyticsActivity.class)));
        binding.groceryPdfButton.setOnClickListener(v -> exportMonthly(true, false));
        binding.groceryExcelButton.setOnClickListener(v -> exportMonthly(false, false));
        binding.groceryShareButton.setOnClickListener(v -> exportMonthly(true, true));
        binding.groceryFilterButton.setOnClickListener(this::showFilterMenu);
        binding.groceryExportButton.setOnClickListener(this::showExportMenu);
        binding.grocerySearchInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence text, int start, int count, int after
                    ) {
                        // No action required.
                    }

                    @Override
                    public void onTextChanged(
                            CharSequence text, int start, int before, int count
                    ) {
                        loadItems(text == null ? "" : text.toString());
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );
        setupPrimaryGroceryFilters();
        memberRepository.loadMembers("", members -> {
            familyMembers.clear();
            familyMembers.addAll(members);
        });
        loadItems("");
        repository.startRealtimeSync(() -> {
            if (binding != null) {
                loadItems(currentQuery());
            }
        });
    }

    private void setupPrimaryGroceryFilters() {
        binding.groceryFilterScroll.setVisibility(View.VISIBLE);
        binding.groceryFilterGroup.setSelectionRequired(false);
        binding.groceryFilterGroup.setSingleSelection(false);
        binding.groceryFilterGroup.clearCheck();
        binding.filterAll.setVisibility(View.GONE);
        binding.filterDaily.setVisibility(View.GONE);
        binding.filterMonthly.setVisibility(View.GONE);
        binding.filterPending.setVisibility(View.GONE);
        binding.filterPurchased.setVisibility(View.GONE);

        binding.filterDaily.setOnClickListener(v -> {
            activeCycleFilter = GroceryItem.LIST_DAILY;
            syncPrimaryFilterChips();
            loadItems(currentQuery());
        });
        binding.filterMonthly.setOnClickListener(v -> {
            activeCycleFilter = GroceryItem.LIST_MONTHLY;
            syncPrimaryFilterChips();
            loadItems(currentQuery());
        });
        binding.filterPending.setOnClickListener(v -> {
            activeStatusFilterId = R.id.filter_pending;
            syncPrimaryFilterChips();
            loadItems(currentQuery());
        });
        binding.filterPurchased.setOnClickListener(v -> {
            activeStatusFilterId = R.id.filter_purchased;
            syncPrimaryFilterChips();
            loadItems(currentQuery());
        });

        ensurePrimaryFilterDropdowns();
        ensureGroceryGroupingChip();
        syncPrimaryFilterChips();
    }

    private void ensurePrimaryFilterDropdowns() {
        if (binding == null || groceryCycleDropdown != null || groceryStatusDropdown != null) {
            return;
        }
        binding.groceryFilterScroll.setFillViewport(true);
        ViewGroup.LayoutParams groupParams = binding.groceryFilterGroup.getLayoutParams();
        groupParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        binding.groceryFilterGroup.setLayoutParams(groupParams);

        groceryCycleDropdown = createFilterDropdown(
                getString(R.string.grocery_filter_daily),
                R.color.fh_success_container,
                R.color.fh_success,
                R.color.fh_on_success_container,
                96
        );
        groceryStatusDropdown = createFilterDropdown(
                getString(R.string.grocery_filter_pending),
                R.color.fh_warning_container,
                R.color.fh_warning,
                R.color.fh_on_warning_container,
                94
        );
        groceryCycleDropdown.setOnClickListener(this::showCycleDropdown);
        groceryStatusDropdown.setOnClickListener(this::showStatusDropdown);

        binding.groceryFilterGroup.addView(groceryCycleDropdown, 0,
                new ViewGroup.MarginLayoutParams(dp(96), dp(38)));
        binding.groceryFilterGroup.addView(groceryStatusDropdown, 1,
                new ViewGroup.MarginLayoutParams(dp(94), dp(38)));
    }

    @NonNull
    private MaterialButton createFilterDropdown(
            @NonNull String label,
            int backgroundColor,
            int strokeColor,
            int textColor,
            int widthDp
    ) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setAllCaps(false);
        button.setText(label + "  ▾");
        button.setTextSize(11f);
        button.setSingleLine(true);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(8), 0);
        button.setCornerRadius(dp(15));
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(
                requireContext(), strokeColor)));
        button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                requireContext(), backgroundColor)));
        button.setTextColor(ContextCompat.getColor(requireContext(), textColor));
        button.setElevation(dp(1));
        button.setContentDescription(label);
        button.setLayoutParams(new ViewGroup.MarginLayoutParams(dp(widthDp), dp(38)));
        return button;
    }

    private interface FilterChoiceListener {
        void onChoice(int index);
    }

    private void showCycleDropdown(@NonNull View anchor) {
        String[] labels = new String[]{
                getString(R.string.grocery_filter_daily),
                getString(R.string.grocery_filter_monthly),
                "2 Monthly",
                "3 Monthly"
        };
        String[] values = new String[]{
                GroceryItem.LIST_DAILY,
                GroceryItem.LIST_MONTHLY,
                GroceryItem.LIST_TWO_MONTH,
                GroceryItem.LIST_THREE_MONTH
        };
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(GroceryRecurrenceEngine.normalizeCycle(activeCycleFilter))) {
                selectedIndex = i;
                break;
            }
        }
        showPremiumFilterPopup(anchor, labels, selectedIndex,
                ContextCompat.getColor(requireContext(), R.color.fh_success), index -> {
                    activeCycleFilter = values[index];
                    syncPrimaryFilterChips();
                    loadItems(currentQuery());
                });
    }

    private void showStatusDropdown(@NonNull View anchor) {
        String[] labels = new String[]{
                getString(R.string.grocery_filter_pending),
                getString(R.string.grocery_filter_purchased)
        };
        int selectedIndex = activeStatusFilterId == R.id.filter_purchased ? 1 : 0;
        showPremiumFilterPopup(anchor, labels, selectedIndex,
                ContextCompat.getColor(requireContext(), R.color.fh_warning), index -> {
                    activeStatusFilterId = index == 1
                            ? R.id.filter_purchased : R.id.filter_pending;
                    syncPrimaryFilterChips();
                    loadItems(currentQuery());
                });
    }

    private void showPremiumFilterPopup(
            @NonNull View anchor,
            @NonNull String[] labels,
            int selectedIndex,
            int accentColor,
            @NonNull FilterChoiceListener listener
    ) {
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(5), dp(5), dp(5), dp(5));
        root.setBackground(premiumFilterPopupBackground());
        root.setElevation(dp(10));

        final android.widget.PopupWindow popup = new android.widget.PopupWindow(requireContext());
        popup.setContentView(root);
        int popupWidth = Math.max(anchor.getWidth(), dp(132));
        int maxWidth = getResources().getDisplayMetrics().widthPixels - dp(28);
        popup.setWidth(Math.min(popupWidth, maxWidth));
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        popup.setElevation(dp(12));
        popup.setOverlapAnchor(false);

        for (int index = 0; index < labels.length; index++) {
            final int selectedChoice = index;
            boolean selected = index == selectedIndex;
            android.widget.TextView row = new android.widget.TextView(requireContext());
            row.setText((selected ? "✓  " : "   ") + labels[index]);
            row.setTextSize(12.5f);
            row.setSingleLine(true);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), 0, dp(10), 0);
            row.setTextColor(selected ? accentColor
                    : ContextCompat.getColor(requireContext(), R.color.fh_text_primary));
            if (selected) {
                row.setTypeface(row.getTypeface(), android.graphics.Typeface.BOLD);
            }
            row.setBackground(premiumFilterRowBackground(selected, accentColor));
            row.setOnClickListener(v -> {
                listener.onChoice(selectedChoice);
                popup.dismiss();
            });
            android.widget.LinearLayout.LayoutParams rowParams =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            if (index < labels.length - 1) rowParams.bottomMargin = dp(4);
            root.addView(row, rowParams);
        }
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    @NonNull
    private android.graphics.drawable.GradientDrawable premiumFilterPopupBackground() {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{
                                android.graphics.Color.argb(253, 255, 255, 255),
                                android.graphics.Color.argb(249, 243, 249, 252)
                        });
        drawable.setCornerRadius(dp(15));
        drawable.setStroke(dp(1), android.graphics.Color.argb(210, 199, 211, 221));
        drawable.setPadding(dp(5), dp(5), dp(5), dp(5));
        return drawable;
    }

    @NonNull
    private android.graphics.drawable.GradientDrawable premiumFilterRowBackground(
            boolean selected,
            int accentColor
    ) {
        int fill = selected
                ? android.graphics.Color.argb(38,
                        android.graphics.Color.red(accentColor),
                        android.graphics.Color.green(accentColor),
                        android.graphics.Color.blue(accentColor))
                : android.graphics.Color.argb(248, 255, 255, 255);
        int stroke = selected
                ? android.graphics.Color.argb(135,
                        android.graphics.Color.red(accentColor),
                        android.graphics.Color.green(accentColor),
                        android.graphics.Color.blue(accentColor))
                : android.graphics.Color.argb(125, 212, 222, 226);
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void ensureGroceryGroupingChip() {
        if (binding == null || groceryCategoryToggleChip != null) return;
        MaterialButton button = createFilterDropdown(
                "Collapse",
                R.color.fh_info_container,
                R.color.fh_module_grocery,
                R.color.fh_module_grocery,
                118
        );
        button.setText("Collapse  ▴");
        button.setContentDescription(getString(R.string.grocery_collapse_categories));
        button.setOnClickListener(v ->
                updateGroceryGroupingChip(adapter.toggleAllCategories()));
        groceryCategoryToggleChip = button;
        binding.groceryFilterGroup.addView(button, 2,
                new ViewGroup.MarginLayoutParams(dp(118), dp(38)));
        fitPrimaryFilterControls();
    }

    private void fitPrimaryFilterControls() {
        if (binding == null) return;
        binding.groceryFilterScroll.post(() -> {
            if (binding == null || groceryCycleDropdown == null
                    || groceryStatusDropdown == null || groceryCategoryToggleChip == null) {
                return;
            }
            int available = binding.groceryFilterScroll.getWidth();
            if (available <= 0) return;
            int usable = Math.max(0, available - dp(16));
            int cycleWidth = Math.round(usable * 0.30f);
            int statusWidth = Math.round(usable * 0.29f);
            int categoryWidth = usable - cycleWidth - statusWidth;
            if (cycleWidth < dp(82) || statusWidth < dp(82) || categoryWidth < dp(108)) {
                cycleWidth = dp(82);
                statusWidth = dp(82);
                categoryWidth = Math.max(dp(108), usable - cycleWidth - statusWidth);
            }
            setFilterControlWidth(groceryCycleDropdown, cycleWidth);
            setFilterControlWidth(groceryStatusDropdown, statusWidth);
            setFilterControlWidth(groceryCategoryToggleChip, categoryWidth);
        });
    }

    private void setFilterControlWidth(@NonNull View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = width;
        params.height = dp(38);
        view.setLayoutParams(params);
    }

    private void updateGroceryGroupingChip(boolean allCollapsed) {
        if (groceryCategoryToggleChip == null) return;
        groceryCategoryToggleChip.setText(allCollapsed ? "Expand  ▾" : "Collapse  ▴");
        groceryCategoryToggleChip.setContentDescription(getString(allCollapsed
                ? R.string.grocery_expand_categories
                : R.string.grocery_collapse_categories));
    }

    private void syncPrimaryFilterChips() {
        if (binding == null) return;
        binding.filterDaily.setChecked(GroceryItem.LIST_DAILY.equals(activeCycleFilter));
        binding.filterMonthly.setChecked(GroceryItem.LIST_MONTHLY.equals(activeCycleFilter));
        binding.filterPending.setChecked(activeStatusFilterId == R.id.filter_pending);
        binding.filterPurchased.setChecked(activeStatusFilterId == R.id.filter_purchased);
        if (groceryCycleDropdown != null) {
            String label = cycleLabel(activeCycleFilter);
            groceryCycleDropdown.setText(label + "  ▾");
            groceryCycleDropdown.setContentDescription(label);
        }
        if (groceryStatusDropdown != null) {
            String label = getString(activeStatusFilterId == R.id.filter_purchased
                    ? R.string.grocery_filter_purchased
                    : R.string.grocery_filter_pending);
            groceryStatusDropdown.setText(label + "  ▾");
            groceryStatusDropdown.setContentDescription(label);
        }
    }

    private void showFilterMenu(@NonNull View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, View.generateViewId(), 0,
                R.string.grocery_filter_category_heading).setEnabled(false);
        popup.getMenu().setGroupCheckable(2, true, true);
        android.view.MenuItem allCategories = popup.getMenu().add(
                2, 10_000, 1, R.string.grocery_filter_all_categories);
        allCategories.setChecked(activeCategoryFilter.isEmpty());
        String[] categories = GroceryOptionCatalog.categoryLabels(requireContext());
        for (int index = 1; index < categories.length; index++) {
            android.view.MenuItem categoryItem = popup.getMenu().add(
                    2, 10_000 + index, 1 + index, categories[index]);
            categoryItem.setChecked(categories[index]
                    .equalsIgnoreCase(activeCategoryFilter));
        }
        popup.setOnMenuItemClickListener(item -> {
            if (item.getGroupId() != 2) return false;
            int categoryIndex = item.getItemId() - 10_000;
            activeCategoryFilter = categoryIndex <= 0
                    ? "" : categories[categoryIndex];
            item.setChecked(true);
            syncPrimaryFilterChips();
            loadItems(currentQuery());
            return true;
        });
        popup.show();
    }

    @NonNull
    private String cycleFromMenuId(int menuId) {
        if (menuId == MENU_CYCLE_THREE_MONTH) return GroceryItem.LIST_THREE_MONTH;
        if (menuId == MENU_CYCLE_TWO_MONTH) return GroceryItem.LIST_TWO_MONTH;
        if (menuId == MENU_CYCLE_MONTHLY) return GroceryItem.LIST_MONTHLY;
        return GroceryItem.LIST_DAILY;
    }

    private int menuIdForCycle(@Nullable String cycle) {
        String normalized = GroceryRecurrenceEngine.normalizeCycle(cycle);
        if (GroceryItem.LIST_THREE_MONTH.equals(normalized)) return MENU_CYCLE_THREE_MONTH;
        if (GroceryItem.LIST_TWO_MONTH.equals(normalized)) return MENU_CYCLE_TWO_MONTH;
        if (GroceryItem.LIST_MONTHLY.equals(normalized)) return MENU_CYCLE_MONTHLY;
        return MENU_CYCLE_DAILY;
    }

    @NonNull
    private String cycleLabel(@Nullable String cycle) {
        String normalized = GroceryRecurrenceEngine.normalizeCycle(cycle);
        if (GroceryItem.LIST_THREE_MONTH.equals(normalized)) return "3 Monthly";
        if (GroceryItem.LIST_TWO_MONTH.equals(normalized)) return "2 Monthly";
        if (GroceryItem.LIST_MONTHLY.equals(normalized)) {
            return getString(R.string.grocery_filter_monthly);
        }
        return getString(R.string.grocery_filter_daily);
    }

    @NonNull
    private String listTypeFromLabel(@NonNull String label) {
        if ("3 Monthly".equalsIgnoreCase(label)
                || "3 Month".equalsIgnoreCase(label)) return GroceryItem.LIST_THREE_MONTH;
        if ("2 Monthly".equalsIgnoreCase(label)
                || "2 Month".equalsIgnoreCase(label)) return GroceryItem.LIST_TWO_MONTH;
        if (getString(R.string.grocery_filter_monthly).equalsIgnoreCase(label)
                || "Monthly".equalsIgnoreCase(label)) {
            return GroceryItem.LIST_MONTHLY;
        }
        return GroceryItem.LIST_DAILY;
    }

    private void showExportMenu(@NonNull View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, 1, 0, R.string.grocery_export_pdf);
        popup.getMenu().add(0, 2, 1, R.string.grocery_export_excel);
        popup.getMenu().add(0, 3, 2, R.string.grocery_share_report);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) exportMonthly(true, false);
            else if (item.getItemId() == 2) exportMonthly(false, false);
            else exportMonthly(true, true);
            return true;
        });
        popup.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) {
            return;
        }
        boolean requested = requireContext().getSharedPreferences(
                GroceryOverlayService.PREFS, android.content.Context.MODE_PRIVATE
        ).getBoolean(GroceryOverlayService.KEY_REQUESTED, false);
        if (requested && Settings.canDrawOverlays(requireContext())) {
            requireContext().getSharedPreferences(
                    GroceryOverlayService.PREFS,
                    android.content.Context.MODE_PRIVATE
            ).edit().putBoolean(GroceryOverlayService.KEY_REQUESTED, false).apply();
            startFloatingStrip();
        }
        setFloatingStripVisible(false);
        updateFloatingButton();
    }

    @Override
    public void onPause() {
        setFloatingStripVisible(true);
        super.onPause();
    }

    private void setFloatingStripVisible(boolean visible) {
        boolean enabled = requireContext().getSharedPreferences(
                GroceryOverlayService.PREFS, android.content.Context.MODE_PRIVATE
        ).getBoolean(GroceryOverlayService.KEY_ENABLED, false);
        if (!enabled) return;
        Intent intent = new Intent(requireContext(), GroceryOverlayService.class);
        intent.setAction(visible
                ? GroceryOverlayService.ACTION_SHOW
                : GroceryOverlayService.ACTION_HIDE);
        requireContext().startService(intent);
    }

    private void toggleFloatingStrip() {
        boolean enabled = requireContext().getSharedPreferences(
                GroceryOverlayService.PREFS, android.content.Context.MODE_PRIVATE
        ).getBoolean(GroceryOverlayService.KEY_ENABLED, false);
        if (enabled) {
            Intent stop = new Intent(requireContext(), GroceryOverlayService.class);
            stop.setAction(GroceryOverlayService.ACTION_STOP);
            requireContext().startService(stop);
            binding.floatingGroceryButton.postDelayed(
                    this::updateFloatingButton, 250L);
            return;
        }
        continueFloatingStripSetup();
    }

    private void setGroceryHeaderCollapsed(boolean collapsed) {
        if (binding == null || groceryHeaderCollapsed == collapsed) return;
        groceryHeaderCollapsed = collapsed;
        TransitionManager.beginDelayedTransition(binding.getRoot());
        int visibility = collapsed ? View.GONE : View.VISIBLE;
        binding.groceryOverview.setVisibility(visibility);
        binding.grocerySummary.setVisibility(visibility);
        ConstraintSet constraints = new ConstraintSet();
        constraints.clone(binding.getRoot());
        constraints.clear(R.id.grocery_search_layout, ConstraintSet.TOP);
        constraints.connect(R.id.grocery_search_layout, ConstraintSet.TOP,
                collapsed ? ConstraintSet.PARENT_ID : R.id.grocery_summary,
                collapsed ? ConstraintSet.TOP : ConstraintSet.BOTTOM,
                getResources().getDimensionPixelSize(collapsed
                        ? R.dimen.space_4 : R.dimen.space_12));
        constraints.applyTo(binding.getRoot());
    }

    private void continueFloatingStripSetup() {
        if (!Settings.canDrawOverlays(requireContext())) {
            requireContext().getSharedPreferences(
                    GroceryOverlayService.PREFS,
                    android.content.Context.MODE_PRIVATE
            ).edit().putBoolean(GroceryOverlayService.KEY_REQUESTED, true).apply();
            Snackbar.make(binding.getRoot(),
                    R.string.grocery_overlay_permission,
                    Snackbar.LENGTH_LONG).show();
            Intent permission = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName())
            );
            startActivity(permission);
            return;
        }
        startFloatingStrip();
    }

    private void startFloatingStrip() {
        Intent intent = new Intent(requireContext(), GroceryOverlayService.class);
        intent.setAction(GroceryOverlayService.ACTION_HIDE);
        ContextCompat.startForegroundService(requireContext(), intent);
        binding.floatingGroceryButton.postDelayed(
                this::updateFloatingButton, 250L);
    }

    private void updateFloatingButton() {
        if (binding == null) {
            return;
        }
        boolean enabled = requireContext().getSharedPreferences(
                GroceryOverlayService.PREFS, android.content.Context.MODE_PRIVATE
        ).getBoolean(GroceryOverlayService.KEY_ENABLED, false);
        binding.floatingGroceryButton.setText(enabled
                ? R.string.grocery_floating_disable
                : R.string.grocery_floating_enable);
    }

    @Override
    public void onAddRequested() {
        showEditor(null);
    }

    private void showEditor(@Nullable GroceryItem existing) {
        showEditor(existing, false);
    }

    private void showEditor(@Nullable GroceryItem existing,
                            boolean completeAfterSave) {
        DialogGroceryBinding form =
                DialogGroceryBinding.inflate(getLayoutInflater());
        GroceryItem item = existing == null
                ? new GroceryItem()
                : existing;
        final long[] selectedPurchaseAt = {System.currentTimeMillis()};
        form.groceryMoneyCatalogStatus.bindItem(item);
        String[] priorityLabels = getResources().getStringArray(
                R.array.grocery_priority_labels
        );
        String[] categoryLabels = GroceryOptionCatalog.categoryLabels(requireContext());
        String[] listTypeLabels = new String[]{
                getString(R.string.grocery_filter_daily),
                getString(R.string.grocery_filter_monthly),
                "2 Monthly",
                "3 Monthly"
        };
        String[] quantityUnits = getResources().getStringArray(
                R.array.grocery_quantity_units
        );
        List<String> assigneeLabels = new ArrayList<>();
        assigneeLabels.add(getString(R.string.grocery_whole_family));
        for (FamilyMember member : familyMembers) {
            assigneeLabels.add(member.name);
        }
        form.groceryCategoryInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                categoryLabels
        ));
        form.groceryPriorityInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                priorityLabels
        ));
        form.groceryListTypeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                listTypeLabels
        ));
        form.groceryAssigneeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                assigneeLabels
        ));
        form.groceryQuantityUnitInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                quantityUnits
        ));
        String[] storePresets = GroceryOptionCatalog.storePresets(requireContext());
        form.groceryStoreInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                storePresets
        ));
        configurePremiumDropdowns(
                form.groceryCategoryInput,
                form.groceryPriorityInput,
                form.groceryListTypeInput,
                form.groceryAssigneeInput,
                form.groceryQuantityUnitInput,
                form.groceryStoreInput,
                form.groceryMoneyAccountInput,
                form.groceryMoneyCategoryInput
        );
        form.groceryStoreInput.setThreshold(0);

        form.groceryPurchaseDateCard.setVisibility(
                completeAfterSave ? View.VISIBLE : View.GONE);
        form.groceryPurchaseDateInput.setText(formatPurchaseDate(
                selectedPurchaseAt[0]));
        Runnable openPurchaseDatePicker = () -> showPurchaseDatePicker(
                selectedPurchaseAt[0], selected -> {
                    selectedPurchaseAt[0] = selected;
                    form.groceryPurchaseDateInput.setText(
                            formatPurchaseDate(selected));
                });
        form.groceryPurchaseDateInput.setOnClickListener(v ->
                openPurchaseDatePicker.run());
        form.groceryPurchaseDateLayout.setEndIconOnClickListener(v ->
                openPurchaseDatePicker.run());
        form.groceryNameLayout.setEndIconOnClickListener(v -> {
            activeDialogVoiceInput = form.groceryNameInput;
            startVoiceAdd();
        });

        if (existing == null) {
            form.groceryCategoryInput.setText(categoryLabels[0], false);
            form.groceryPriorityInput.setText(priorityLabels[0], false);
            form.groceryListTypeInput.setText(listTypeLabels[0], false);
            form.groceryAssigneeInput.setText(assigneeLabels.get(0), false);
            form.groceryQuantityUnitInput.setText(quantityUnits[0], false);
        } else {
            form.groceryDialogTitle.setText(R.string.grocery_edit_item);
            form.groceryNameInput.setText(item.name);
            form.groceryCategoryInput.setText(item.category, false);
            String[] parsedQuantity = splitQuantity(item.quantity, quantityUnits);
            form.groceryQuantityInput.setText(parsedQuantity[0]);
            form.groceryQuantityUnitInput.setText(parsedQuantity[1], false);
            if (item.estimatedCost > 0) {
                form.groceryCostInput.setText(String.valueOf(
                        item.estimatedCost
                ));
            }
            form.groceryPriorityInput.setText(
                    displayPriority(item.priority),
                    false
            );
            form.groceryNotesInput.setText(item.notes);
            if (item.actualCost > 0D) {
                form.groceryActualCostInput.setText(String.valueOf(item.actualCost));
            }
            form.groceryStoreInput.setText(item.storeName);
            form.groceryAutoPriceSwitch.setChecked(item.autoPriceEnabled);
            form.groceryMonthlyMasterSwitch.setChecked(item.isMonthlyMaster);
            form.groceryListTypeInput.setText(cycleLabel(item.listType), false);
            form.groceryAssigneeInput.setText(
                    item.assignedMemberName.isEmpty()
                            ? assigneeLabels.get(0)
                            : item.assignedMemberName,
                    false
            );
            repository.loadStoreComparison(item.name, item.quantity,
                    (history, cheapest) -> {
                if (history == null || !isAdded()) return;
                applyPurchaseHistory(form, item, history, cheapest,
                        quantityUnits, categoryLabels, completeAfterSave);
            });
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(form.getRoot())
                .create();
        dialog.setOnDismissListener(ignored -> activeDialogVoiceInput = null);
        form.cancelGroceryButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        form.skipGroceryButton.setVisibility(
                completeAfterSave ? View.VISIBLE : View.GONE);
        form.skipGroceryButton.setOnClickListener(clickedView ->
                completeWithUndo(item, selectedPurchaseAt[0], dialog));
        form.saveGroceryButton.setOnClickListener(clickedView -> {
            String name = textOf(form.groceryNameInput);
            int priorityIndex = findPriorityIndex(
                    priorityLabels,
                    textOf(form.groceryPriorityInput)
            );
            if (name.isEmpty()) {
                form.groceryNameLayout.setError(
                        getString(R.string.grocery_name_required)
                );
                return;
            }
            form.groceryNameLayout.setError(null);
            if (priorityIndex < 0) {
                form.groceryPriorityLayout.setError(
                        getString(R.string.grocery_priority_required)
                );
                return;
            }
            form.groceryPriorityLayout.setError(null);

            String selectedCategory = textOf(form.groceryCategoryInput);
            if (selectedCategory.isEmpty()
                    || categoryLabels[0].equalsIgnoreCase(selectedCategory)) {
                ((com.google.android.material.textfield.TextInputLayout)
                        form.groceryCategoryInput.getParent().getParent())
                        .setError(getString(R.string.grocery_category_required));
                return;
            }

            item.name = name;
            item.category = selectedCategory;
            String quantityAmount = textOf(form.groceryQuantityInput);
            String quantityUnit = textOf(form.groceryQuantityUnitInput);
            item.quantity = quantityAmount.isEmpty() ? ""
                    : quantityAmount + " " + (quantityUnit.isEmpty()
                    ? quantityUnits[0] : quantityUnit);
            item.estimatedCost = parseAmount(textOf(form.groceryCostInput));
            item.priority = PRIORITIES[priorityIndex];
            item.notes = textOf(form.groceryNotesInput);
            item.actualCost = parseAmount(textOf(form.groceryActualCostInput));
            item.storeName = textOf(form.groceryStoreInput);
            item.autoPriceEnabled = form.groceryAutoPriceSwitch.isChecked();
            item.listType = listTypeFromLabel(textOf(form.groceryListTypeInput));
            item.isMonthlyMaster = GroceryRecurrenceEngine.isRecurringType(item.listType)
                    && form.groceryMonthlyMasterSwitch.isChecked();
            int assigneeIndex = assigneeLabels.indexOf(
                    textOf(form.groceryAssigneeInput)
            );
            if (assigneeIndex > 0 && assigneeIndex <= familyMembers.size()) {
                FamilyMember member = familyMembers.get(assigneeIndex - 1);
                item.assignedMemberId = member.cloudProfileId.isEmpty()
                        ? String.valueOf(member.id)
                        : member.cloudProfileId;
                item.assignedMemberName = member.name;
            } else {
                item.assignedMemberId = "";
                item.assignedMemberName = "";
            }

            Runnable saveComplete = () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                if (completeAfterSave) {
                    completeWithUndo(item, selectedPurchaseAt[0], dialog);
                } else {
                    loadItems(currentQuery());
                    Snackbar.make(binding.getRoot(),
                            item.duplicateMerged
                                    ? R.string.grocery_duplicate_reused
                                    : existing == null
                                            ? R.string.grocery_item_added
                                            : R.string.grocery_item_updated,
                            Snackbar.LENGTH_SHORT).show();
                }
            };
            estimateAndSave(item, saveComplete);
        });
        dialog.show();
    }

    private void completeWithUndo(@NonNull GroceryItem item,
                                  long purchasedAt,
                                  @NonNull AlertDialog dialog) {
        repository.setPurchased(item, true, purchasedAt, () -> {
            dialog.dismiss();
            if (binding == null) return;
            loadItems(currentQuery());
            Snackbar.make(binding.getRoot(),
                            getString(R.string.grocery_purchase_completed, item.name),
                            Snackbar.LENGTH_LONG)
                    .setAction(R.string.grocery_undo, v ->
                            repository.undoPurchase(item, () -> {
                                if (binding != null) {
                                    loadItems(currentQuery());
                                    Snackbar.make(binding.getRoot(),
                                            R.string.grocery_purchase_restored,
                                            Snackbar.LENGTH_SHORT).show();
                                }
                            })).show();
        });
    }

    private void configurePremiumDropdowns(
            com.google.android.material.textfield.MaterialAutoCompleteTextView... fields) {
        for (com.google.android.material.textfield.MaterialAutoCompleteTextView field : fields) {
            field.setSingleLine(true);
            field.setTextSize(12.5f);
            field.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            field.setDropDownVerticalOffset(dp(6));
            android.graphics.drawable.GradientDrawable popup =
                    new android.graphics.drawable.GradientDrawable();
            popup.setColor(ContextCompat.getColor(requireContext(), R.color.fh_surface));
            popup.setCornerRadius(dp(16));
            popup.setStroke(dp(1), ContextCompat.getColor(
                    requireContext(), R.color.fh_form_outline));
            field.setDropDownBackgroundDrawable(popup);
            field.setOnClickListener(v -> field.showDropDown());
        }
    }

    private interface PurchaseDateSelectionListener {
        void onSelected(long timestamp);
    }

    private void showPurchaseDatePicker(
            long currentSelection,
            @NonNull PurchaseDateSelectionListener onSelected) {
        com.google.android.material.datepicker.CalendarConstraints constraints =
                new com.google.android.material.datepicker.CalendarConstraints.Builder()
                        .setEnd(com.google.android.material.datepicker.MaterialDatePicker
                                .todayInUtcMilliseconds())
                        .build();
        com.google.android.material.datepicker.MaterialDatePicker<Long> picker =
                com.google.android.material.datepicker.MaterialDatePicker.Builder
                        .datePicker()
                        .setTitleText(R.string.grocery_choose_purchase_date)
                        .setSelection(toUtcDateSelection(currentSelection))
                        .setCalendarConstraints(constraints)
                        .build();
        picker.addOnPositiveButtonClickListener(selection ->
                onSelected.onSelected(fromUtcDateSelection(selection)));
        picker.show(getParentFragmentManager(), "grocery_purchase_date");
    }

    private static long toUtcDateSelection(long timestamp) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(timestamp);
        Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH),
                local.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis();
    }

    private static long fromUtcDateSelection(long selection) {
        Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(selection);
        Calendar local = Calendar.getInstance();
        int hour = local.get(Calendar.HOUR_OF_DAY);
        int minute = local.get(Calendar.MINUTE);
        local.clear();
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH),
                utc.get(Calendar.DAY_OF_MONTH), hour, minute, 0);
        return Math.min(local.getTimeInMillis(), System.currentTimeMillis());
    }

    @NonNull
    private static String formatPurchaseDate(long timestamp) {
        return new SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(new java.util.Date(timestamp));
    }

    @NonNull
    private String[] splitQuantity(@NonNull String stored,
            @NonNull String[] supportedUnits) {
        String clean = stored.trim();
        if (clean.isEmpty()) return new String[]{"", supportedUnits[0]};
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([0-9]+(?:[.,][0-9]+)?)\\s*([A-Za-z]+)?$"
        ).matcher(clean);
        if (!matcher.matches()) return new String[]{"", supportedUnits[0]};
        String amount = matcher.group(1) == null ? ""
                : matcher.group(1).replace(',', '.');
        String storedUnit = matcher.group(2) == null ? supportedUnits[0]
                : matcher.group(2);
        for (String unit : supportedUnits) {
            if (unit.equalsIgnoreCase(storedUnit)) {
                return new String[]{amount, unit};
            }
        }
        return new String[]{amount, supportedUnits[0]};
    }

    private void applyPurchaseHistory(
            @NonNull DialogGroceryBinding form,
            @NonNull GroceryItem item,
            @NonNull GroceryPurchase history,
            @Nullable GroceryPurchase cheapest,
            @NonNull String[] units,
            @NonNull String[] categories,
            boolean autoFill
    ) {
        if (autoFill) {
            String[] parsed = splitQuantity(history.quantity, units);
            form.groceryQuantityInput.setText(parsed[0]);
            form.groceryQuantityUnitInput.setText(parsed[1], false);
            if (!history.category.isEmpty()) {
                form.groceryCategoryInput.setText(history.category, false);
            }
            if (history.actualCost > 0D) {
                form.groceryActualCostInput.setText(
                        String.valueOf(history.actualCost));
            }
            if (!history.storeName.isEmpty()) {
                form.groceryStoreInput.setText(history.storeName);
            }
        }
        form.groceryPriceComparison.setVisibility(View.VISIBLE);
        form.groceryPriceComparison.setText(storeComparisonText(history,
                cheapest, history.actualCost));
        form.groceryActualCostInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) { }
                    @Override public void onTextChanged(
                            CharSequence s, int start, int before, int count) {
                        double current = parseAmount(s == null ? "" : s.toString());
                        form.groceryPriceComparison.setText(storeComparisonText(
                                history, cheapest, current));
                    }
                    @Override public void afterTextChanged(
                            android.text.Editable s) { }
                });
    }

    @NonNull
    private String storeComparisonText(
            @NonNull GroceryPurchase history,
            @Nullable GroceryPurchase cheapest,
            double current
    ) {
        StringBuilder text = new StringBuilder(getString(
                R.string.grocery_previous_purchase,
                history.quantity.isEmpty()
                        ? getString(R.string.grocery_quantity_not_added)
                        : history.quantity,
                history.category.isEmpty()
                        ? getString(R.string.grocery_uncategorized)
                        : history.category,
                currencyFormat.format(history.actualCost)));
        if (!history.storeName.isEmpty()) text.append('\n').append(getString(
                R.string.grocery_previous_store, history.storeName));
        if (cheapest != null && !cheapest.storeName.isEmpty()) {
            text.append('\n').append(getString(R.string.grocery_cheapest_store,
                    cheapest.storeName, cheapest.actualCost));
            if (current > cheapest.actualCost) {
                text.append('\n').append(getString(R.string.grocery_possible_saving,
                        current - cheapest.actualCost));
            }
        }
        if (current > 0D && history.actualCost > 0D) {
            double percent = (current - history.actualCost)
                    / history.actualCost * 100D;
            text.append('\n').append(Math.abs(percent) < 0.05D
                    ? getString(R.string.grocery_price_same)
                    : getString(R.string.grocery_price_change,
                            history.actualCost, current, percent));
        }
        return text.toString();
    }

    private int findPriorityIndex(
            @NonNull String[] labels,
            @NonNull String selected
    ) {
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equalsIgnoreCase(selected)) {
                return index;
            }
        }
        return -1;
    }

    @NonNull
    private String displayPriority(@NonNull String stored) {
        for (int index = 0; index < PRIORITIES.length; index++) {
            if (PRIORITIES[index].equals(stored)) {
                return getResources().getStringArray(
                        R.array.grocery_priority_labels
                )[index];
            }
        }
        return getString(R.string.grocery_priority_normal);
    }

    private double parseAmount(@NonNull String value) {
        try {
            return value.isEmpty() ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void confirmDelete(@NonNull GroceryItem item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.grocery_delete_title)
                .setMessage(getString(
                        R.string.grocery_delete_message,
                        item.name
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(item, () -> {
                            if (binding != null) {
                                loadItems(currentQuery());
                            }
                        })
                )
                .show();
    }

    private void confirmClearPurchased() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.grocery_clear_title)
                .setMessage(R.string.grocery_clear_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.clearPurchased(() -> {
                            if (binding != null) {
                                loadItems(currentQuery());
                            }
                        })
                )
                .show();
    }

    private void exportMonthly(boolean pdf, boolean share) {
        android.content.Context context = requireContext().getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Calendar start = Calendar.getInstance();
                start.set(Calendar.DAY_OF_MONTH, 1);
                start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0);
                start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
                Calendar end = (Calendar) start.clone(); end.add(Calendar.MONTH, 1);
                List<com.tridev.familyhub.data.local.entity.GroceryPurchase> rows =
                        com.tridev.familyhub.data.local.FamilyHubDatabase
                                .getInstance(context).groceryPurchaseDao()
                                .getForPeriod(start.getTimeInMillis(), end.getTimeInMillis());
                File folder = new File(context.getCacheDir(), "grocery_reports");
                if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException();
                File file = new File(folder, "Grocery_Report_"
                        + new java.text.SimpleDateFormat("yyyy_MM", Locale.ENGLISH)
                                .format(new java.util.Date())
                        + (pdf ? ".pdf" : ".xls"));
                if (pdf) GroceryReportExporter.pdf(file, rows);
                else GroceryReportExporter.excel(file, rows);
                requireActivity().runOnUiThread(() -> shareReport(file,
                        pdf ? "application/pdf" : "application/vnd.ms-excel", share));
            } catch (Exception error) {
                if (isAdded()) requireActivity().runOnUiThread(() ->
                        Snackbar.make(binding.getRoot(), R.string.grocery_export_error,
                                Snackbar.LENGTH_SHORT).show());
            }
        });
    }

    private void shareReport(File file, String mime, boolean directShare) {
        Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".backupfiles", file);
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent,
                getString(directShare ? R.string.grocery_share_report
                        : R.string.grocery_export_success)));
    }

    private void loadItems(@NonNull String query) {
        repository.loadItems(query, items -> {
            if (binding == null) {
                return;
            }
            List<GroceryItem> visibleItems = applyFilter(items);
            repository.loadCurrentMonthPurchases(purchases -> {
                if (binding == null) return;
                Map<String, Double> spent = new LinkedHashMap<>();
                for (GroceryPurchase purchase : purchases) {
                    String key = purchase.category.toLowerCase(Locale.ENGLISH);
                    spent.put(key, spent.getOrDefault(key, 0D)
                            + Math.max(0D, purchase.actualCost));
                }
                Map<String, Double> budgets = new LinkedHashMap<>();
                for (GroceryItem grocery : visibleItems) {
                    String key = grocery.category.toLowerCase(Locale.ENGLISH);
                    budgets.put(key, repository.getCategoryBudget(grocery.category));
                }
                adapter.submitList(visibleItems, spent, budgets);
                updateGroceryGroupingChip(
                        adapter.areAllCurrentCategoriesCollapsed());
            });
            renderSummary(items);
            boolean isEmpty = visibleItems.isEmpty();
            binding.groceryRecyclerView.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.groceryEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        });
    }

    @NonNull
    private List<GroceryItem> applyFilter(@NonNull List<GroceryItem> items) {
        List<GroceryItem> filtered = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (GroceryItem item : items) {
            boolean listMatches = GroceryRecurrenceEngine.matchesCycle(
                    item, activeCycleFilter, now);
            boolean statusMatches = activeStatusFilterId == R.id.filter_purchased
                    ? item.isPurchased
                    : !item.isPurchased;
            boolean include = listMatches && statusMatches;
            if (include && !activeCategoryFilter.isEmpty()) {
                include = activeCategoryFilter.equalsIgnoreCase(
                        item.category == null ? "" : item.category.trim());
            }
            if (include) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private void renderSummary(@NonNull List<GroceryItem> items) {
        int pending = 0;
        int purchased = 0;
        double total = 0;
        double actualTotal = 0;
        for (GroceryItem item : items) {
            actualTotal += Math.max(0D, item.actualCost);
            if (item.isPurchased) {
                purchased++;
            } else {
                pending++;
                total += item.estimatedCost;
            }
        }
        binding.groceryPendingValue.setText(String.valueOf(pending));
        binding.groceryTotalValue.setText(currencyFormat.format(total));
        binding.groceryActualValue.setText(currencyFormat.format(actualTotal));
        double budget = repository.getMonthlyBudget();
        binding.groceryBudgetValue.setText(budget <= 0D
                ? getString(R.string.grocery_set_budget)
                : currencyFormat.format(Math.max(0D, budget - total)));
        binding.clearPurchasedButton.setEnabled(purchased > 0);
    }

    private void showBudgetEditor() {
        String[] categories = GroceryOptionCatalog.categoryLabels(requireContext());
        String[] choices = new String[categories.length];
        choices[0] = getString(R.string.grocery_overall_budget);
        System.arraycopy(categories, 1, choices, 1, categories.length - 1);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.grocery_budget_choose)
                .setItems(choices, (dialog, which) ->
                        showBudgetAmount(which == 0 ? null : categories[which]))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void showBudgetAmount(@Nullable String category) {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(R.string.grocery_budget_hint);
        double current = category == null ? repository.getMonthlyBudget()
                : repository.getCategoryBudget(category);
        if (current > 0D) input.setText(String.valueOf(current));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(category == null ? getString(R.string.grocery_budget)
                        : getString(R.string.grocery_category_budget, category))
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.grocery_save_item, (dialog, which) -> {
                    double value = parseAmount(textOf(input));
                    if (category == null) repository.setMonthlyBudget(value);
                    else repository.setCategoryBudget(category, value);
                    loadItems(currentQuery());
                }).show();
    }

    private void startVoiceAdd() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.grocery_voice_add));
        voiceLauncher.launch(intent);
    }

    private void addFromVoice(@NonNull String spoken) {
        String normalized = spoken.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        GroceryItem item = new GroceryItem();
        if (lower.contains("3 month") || lower.contains("three month")
                || normalized.contains("3 मंथ") || normalized.contains("3 महीने")) {
            item.listType = GroceryItem.LIST_THREE_MONTH;
        } else if (lower.contains("2 month") || lower.contains("two month")
                || normalized.contains("2 मंथ") || normalized.contains("2 महीने")) {
            item.listType = GroceryItem.LIST_TWO_MONTH;
        } else if (lower.contains("monthly") || normalized.contains("मंथली")
                || normalized.contains("मासिक")) {
            item.listType = GroceryItem.LIST_MONTHLY;
        } else {
            item.listType = GroceryItem.LIST_DAILY;
        }
        item.isMonthlyMaster = GroceryRecurrenceEngine.isRecurringType(item.listType);
        normalized = normalized.replaceAll(
                "(?i)three\\s*month|two\\s*month|3\\s*month|2\\s*month|monthly|daily|list|add|item|3\\s*मंथ|2\\s*मंथ|3\\s*महीने|2\\s*महीने|मंथली|मासिक|डेली|लिस्ट|जोड़ो|ऐड",
                " ").replaceAll("\\s+", " ").trim();
        item.name = normalized.isEmpty() ? spoken.trim() : normalized;
        estimateAndSave(item, () -> {
            if (binding != null) {
                loadItems(currentQuery());
                Snackbar.make(binding.getRoot(),
                        R.string.grocery_item_added,
                        Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    private void showRecurringSuggestions() {
        repository.loadSuggestions(items -> {
            if (items.isEmpty() || binding == null) {
                Snackbar.make(binding.getRoot(),
                        R.string.grocery_no_suggestions,
                        Snackbar.LENGTH_SHORT).show();
                return;
            }
            String[] labels = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                labels[i] = items.get(i).name;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.grocery_suggestions)
                    .setItems(labels, (dialog, which) -> {
                        GroceryItem source = items.get(which);
                        GroceryItem suggested = new GroceryItem();
                        suggested.name = source.name;
                        suggested.category = source.category;
                        suggested.quantity = source.quantity;
                        suggested.estimatedCost = source.actualCost > 0D
                                ? source.actualCost : source.estimatedCost;
                        suggested.listType = source.listType;
                        suggested.isMonthlyMaster = GroceryRecurrenceEngine
                                .isRecurringType(source.listType);
                        repository.save(suggested,
                                () -> loadItems(currentQuery()));
                    }).show();
        });
    }

    private void estimateAndSave(
            @NonNull GroceryItem item,
            @NonNull Runnable complete
    ) {
        if (item.autoPriceEnabled && item.actualCost > 0D
                && item.priceLocationKey.isEmpty()
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(requireContext())
                    .getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            item.priceLocationKey = String.format(Locale.US,
                                    "%.2f,%.2f", location.getLatitude(),
                                    location.getLongitude());
                            item.priceConfidence = 100;
                        }
                        repository.save(item, complete::run);
                    })
                    .addOnFailureListener(error ->
                            repository.save(item, complete::run));
            return;
        }
        if (!item.autoPriceEnabled || item.estimatedCost > 0D
                || item.name.trim().isEmpty()) {
            repository.save(item, complete::run);
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            estimateWithKey(item, "", complete);
            return;
        }
        LocationServices.getFusedLocationProviderClient(requireContext())
                .getLastLocation()
                .addOnSuccessListener(location -> {
                    String key = location == null ? "" : String.format(
                            Locale.US, "%.2f,%.2f",
                            location.getLatitude(), location.getLongitude());
                    estimateWithKey(item, key, complete);
                })
                .addOnFailureListener(error ->
                        estimateWithKey(item, "", complete));
    }

    private void estimateWithKey(
            @NonNull GroceryItem item,
            @NonNull String key,
            @NonNull Runnable complete
    ) {
        repository.estimatePrice(item.name, key, (amount, confidence) -> {
            if (amount > 0D) {
                item.estimatedCost = amount;
                item.priceLocationKey = key;
                item.priceConfidence = confidence;
            }
            repository.save(item, complete::run);
        });
    }

    @NonNull
    private String currentQuery() {
        return textOf(binding.grocerySearchInput);
    }

    @NonNull
    private String textOf(@NonNull android.widget.EditText input) {
        return input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        repository.stopRealtimeSync();
        binding.groceryRecyclerView.setAdapter(null);
        groceryCategoryToggleChip = null;
        groceryCycleDropdown = null;
        groceryStatusDropdown = null;
        binding = null;
        super.onDestroyView();
    }
}
