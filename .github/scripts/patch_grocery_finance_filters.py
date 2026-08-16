from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 anchor, found {count}")
    return text.replace(old, new, 1)


def insert_before(text: str, marker: str, addition: str, label: str) -> str:
    count = text.count(marker)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 marker, found {count}")
    return text.replace(marker, addition + marker, 1)


# ---------------------------------------------------------------------------
# GroceryRepository: idempotently repair purchased Grocery -> Finance links.
# ---------------------------------------------------------------------------
path = Path("app/src/main/java/com/tridev/familyhub/data/repository/GroceryRepository.java")
text = path.read_text(encoding="utf-8")

constructor_marker = "    /** Starts one family-scoped realtime listener; safe to call repeatedly. */\n"
reconcile_public = """    /**
     * Repairs Grocery purchase links from another repository boundary, such as
     * the Finance screen. Call only from a background thread. Existing
     * financeEntryId/cloudId identities are reused, so the operation is idempotent.
     */
    public static void reconcileFinanceLinksNow(@NonNull Context context) {
        GroceryRepository repository = new GroceryRepository(context);
        repository.reconcileFinanceLinksInternal();
    }

"""
text = insert_before(text, constructor_marker, reconcile_public,
                     "GroceryRepository public reconcile")

text = replace_once(
    text,
    "            resetMonthlyMastersIfNeeded();\n            String trimmedQuery = query.trim();",
    "            resetMonthlyMastersIfNeeded();\n            reconcileFinanceLinksInternal();\n            String trimmedQuery = query.trim();",
    "GroceryRepository load reconcile",
)

link_marker = "    private void linkFinance(@NonNull GroceryItem item) {\n"
reconcile_internal = """    private void reconcileFinanceLinksInternal() {
        for (GroceryItem item : groceryItemDao.getAll()) {
            long previousFinanceEntryId = item.financeEntryId;
            linkFinance(item);
            if (previousFinanceEntryId != item.financeEntryId) {
                groceryItemDao.update(item);
            }
        }
    }

    @NonNull
    private String financeFamilyId(@NonNull GroceryItem item) {
        if (!activeFamilyId.isEmpty()) return activeFamilyId;
        return item.familyId == null ? "" : item.familyId.trim();
    }

"""
text = insert_before(text, link_marker, reconcile_internal,
                     "GroceryRepository internal reconcile")

text = replace_once(
    text,
    "            entry.familyId = activeFamilyId;",
    "            entry.familyId = financeFamilyId(item);",
    "GroceryRepository family id",
)

text = replace_once(
    text,
    "    private String linkedFinanceCloudId(@NonNull GroceryItem item) {\n"
    "        if (activeFamilyId.isEmpty() || item.cloudId.isEmpty()) return \"\";\n"
    "        return \"grocery_\" + item.cloudId;\n"
    "    }",
    "    private String linkedFinanceCloudId(@NonNull GroceryItem item) {\n"
    "        if (financeFamilyId(item).isEmpty() || item.cloudId.isEmpty()) return \"\";\n"
    "        return \"grocery_\" + item.cloudId;\n"
    "    }",
    "GroceryRepository linked cloud id",
)
path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# FinanceRepository: repair links before returning the Finance list.
# ---------------------------------------------------------------------------
path = Path("app/src/main/java/com/tridev/familyhub/data/repository/FinanceRepository.java")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    "    public void loadEntries(@NonNull String searchQuery, @NonNull EntriesCallback callback) {\n"
    "        DATABASE_EXECUTOR.execute(() -> {\n"
    "            List<FinanceEntry> entries = searchQuery.trim().isEmpty()",
    "    public void loadEntries(@NonNull String searchQuery, @NonNull EntriesCallback callback) {\n"
    "        DATABASE_EXECUTOR.execute(() -> {\n"
    "            GroceryRepository.reconcileFinanceLinksNow(appContext);\n"
    "            List<FinanceEntry> entries = searchQuery.trim().isEmpty()",
    "FinanceRepository Grocery reconcile",
)
path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# GroceryFragment: main-page Collapse/Expand categories control.
# ---------------------------------------------------------------------------
path = Path("app/src/main/java/com/tridev/familyhub/feature/grocery/GroceryFragment.java")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    "    @NonNull private String activeCategoryFilter = \"\";\n"
    "    @Nullable private android.widget.EditText activeDialogVoiceInput;",
    "    @NonNull private String activeCategoryFilter = \"\";\n"
    "    @Nullable private com.google.android.material.chip.Chip groceryCategoryToggleChip;\n"
    "    @Nullable private android.widget.EditText activeDialogVoiceInput;",
    "GroceryFragment grouping field",
)

text = replace_once(
    text,
    "                    @Override\n"
    "                    public void onBuying(@NonNull GroceryItem item) {\n"
    "                        repository.setBuyingStatus(item,\n"
    "                                GroceryItem.STATUS_BUYING,\n"
    "                                () -> loadItems(currentQuery()));\n"
    "                    }\n"
    "                }\n"
    "        );",
    "                    @Override\n"
    "                    public void onBuying(@NonNull GroceryItem item) {\n"
    "                        repository.setBuyingStatus(item,\n"
    "                                GroceryItem.STATUS_BUYING,\n"
    "                                () -> loadItems(currentQuery()));\n"
    "                    }\n\n"
    "                    @Override\n"
    "                    public void onGroupingChanged(boolean allCollapsed) {\n"
    "                        updateGroceryGroupingChip(allCollapsed);\n"
    "                    }\n"
    "                }\n"
    "        );",
    "GroceryFragment grouping callback",
)

text = replace_once(
    text,
    "        binding.groceryFilterGroup.clearCheck();\n"
    "        binding.filterAll.setVisibility(View.GONE);",
    "        binding.groceryFilterGroup.clearCheck();\n"
    "        binding.filterAll.setVisibility(View.GONE);\n"
    "        ensureGroceryGroupingChip();",
    "GroceryFragment grouping setup",
)

group_methods_marker = "    private void syncPrimaryFilterChips() {\n"
group_methods = """    private void ensureGroceryGroupingChip() {
        if (binding == null || groceryCategoryToggleChip != null) return;
        com.google.android.material.chip.Chip chip =
                new com.google.android.material.chip.Chip(requireContext());
        chip.setCheckable(false);
        chip.setClickable(true);
        chip.setText(R.string.grocery_collapse_categories);
        chip.setChipBackgroundColorResource(R.color.fh_info_container);
        chip.setTextColor(ContextCompat.getColor(
                requireContext(), R.color.fh_module_grocery));
        chip.setOnClickListener(v ->
                updateGroceryGroupingChip(adapter.toggleAllCategories()));
        groceryCategoryToggleChip = chip;
        binding.groceryFilterGroup.addView(chip);
    }

    private void updateGroceryGroupingChip(boolean allCollapsed) {
        if (groceryCategoryToggleChip == null) return;
        groceryCategoryToggleChip.setText(allCollapsed
                ? R.string.grocery_expand_categories
                : R.string.grocery_collapse_categories);
    }

"""
text = insert_before(text, group_methods_marker, group_methods,
                     "GroceryFragment grouping methods")

text = replace_once(
    text,
    "                adapter.submitList(visibleItems, spent, budgets);",
    "                adapter.submitList(visibleItems, spent, budgets);\n"
    "                updateGroceryGroupingChip(\n"
    "                        adapter.areAllCurrentCategoriesCollapsed());",
    "GroceryFragment grouping refresh",
)
path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# FinanceFragment: combined search + period/type + Source + Category filtering.
# ---------------------------------------------------------------------------
path = Path("app/src/main/java/com/tridev/familyhub/feature/finance/FinanceFragment.java")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    "import android.widget.EditText;\nimport android.widget.LinearLayout;",
    "import android.widget.EditText;\n"
    "import android.widget.HorizontalScrollView;\n"
    "import android.widget.LinearLayout;\n"
    "import android.widget.PopupMenu;",
    "FinanceFragment widget imports",
)

text = replace_once(
    text,
    "import com.google.android.material.dialog.MaterialAlertDialogBuilder;",
    "import com.google.android.material.chip.Chip;\n"
    "import com.google.android.material.dialog.MaterialAlertDialogBuilder;",
    "FinanceFragment Chip import",
)

text = replace_once(
    text,
    "    private int selectedFinanceFilter = R.id.finance_filter_all;\n"
    "    private boolean financeHeaderCollapsed;",
    "    private int selectedFinanceFilter = R.id.finance_filter_all;\n"
    "    @NonNull private String selectedFinanceSource =\n"
    "            FinanceEntrySourceClassifier.SOURCE_ALL;\n"
    "    @NonNull private String selectedFinanceCategory = \"\";\n"
    "    @Nullable private Chip financeSourceChip;\n"
    "    @Nullable private Chip financeCategoryChip;\n"
    "    private boolean financeHeaderCollapsed;",
    "FinanceFragment filter fields",
)

text = replace_once(
    text,
    "            public void afterTextChanged(Editable searchText) {\n"
    "                loadEntries(searchText.toString());\n"
    "            }",
    "            public void afterTextChanged(Editable searchText) {\n"
    "                applyFinanceFilters();\n"
    "            }",
    "FinanceFragment local search",
)

text = replace_once(
    text,
    "        binding.financeFilterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {\n"
    "            selectedFinanceFilter = checkedIds.isEmpty()\n"
    "                    ? R.id.finance_filter_all : checkedIds.get(0);\n"
    "            applyFinanceFilters();\n"
    "        });\n"
    "        refreshData();",
    "        binding.financeFilterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {\n"
    "            selectedFinanceFilter = checkedIds.isEmpty()\n"
    "                    ? R.id.finance_filter_all : checkedIds.get(0);\n"
    "            applyFinanceFilters();\n"
    "        });\n"
    "        setupAdvancedFinanceFilters();\n"
    "        refreshData();",
    "FinanceFragment advanced filter setup",
)

start_marker = "    private void loadEntries(String query) {\n"
end_marker = "    private void loadSummary() {\n"
start = text.find(start_marker)
end = text.find(end_marker, start + 1)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("FinanceFragment filter region markers not found")

advanced_filter_block = r'''    private void loadEntries(String query) {
        repository.loadEntries("", entries -> {
            if (binding == null) return;
            latestEntries = new ArrayList<>(entries);
            updateSmartInsights();
            updateAnalytics();
            applyFinanceFilters(entries);
        });
    }

    private void setupAdvancedFinanceFilters() {
        View parentView = (View) binding.financeFilterScroll.getParent();
        if (!(parentView instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) parentView;
        HorizontalScrollView scroll = new HorizontalScrollView(requireContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int top = getResources().getDimensionPixelSize(R.dimen.space_4);
        row.setPadding(0, top, 0, 0);

        financeSourceChip = advancedFilterChip(getString(
                R.string.finance_source_chip,
                getString(R.string.finance_source_all)));
        financeCategoryChip = advancedFilterChip(getString(
                R.string.finance_category_chip,
                getString(R.string.finance_category_all)));
        Chip reset = advancedFilterChip(getString(R.string.finance_reset_filters));

        financeSourceChip.setOnClickListener(this::showFinanceSourceMenu);
        financeCategoryChip.setOnClickListener(this::showFinanceCategoryMenu);
        reset.setOnClickListener(v -> resetFinanceFilters());

        addAdvancedFilterChip(row, financeSourceChip);
        addAdvancedFilterChip(row, financeCategoryChip);
        addAdvancedFilterChip(row, reset);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));

        int index = parent.indexOfChild(binding.financeFilterScroll);
        parent.addView(scroll, index + 1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    @NonNull
    private Chip advancedFilterChip(@NonNull String text) {
        Chip chip = new Chip(requireContext());
        chip.setCheckable(false);
        chip.setClickable(true);
        chip.setText(text);
        chip.setChipBackgroundColorResource(R.color.fh_info_container);
        return chip;
    }

    private void addAdvancedFilterChip(@NonNull LinearLayout row,
                                       @NonNull Chip chip) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(getResources().getDimensionPixelSize(R.dimen.space_6));
        row.addView(chip, params);
    }

    private void showFinanceSourceMenu(@NonNull View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        String[] keys = {
                FinanceEntrySourceClassifier.SOURCE_ALL,
                FinanceEntrySourceClassifier.SOURCE_GROCERY,
                FinanceEntrySourceClassifier.SOURCE_LOAN_MANAGER,
                FinanceEntrySourceClassifier.SOURCE_MONEY_MANAGER,
                FinanceEntrySourceClassifier.SOURCE_DIRECT,
                FinanceEntrySourceClassifier.SOURCE_OTHER
        };
        int[] labels = {
                R.string.finance_source_all,
                R.string.finance_source_grocery,
                R.string.finance_source_loan_manager,
                R.string.finance_source_money_manager,
                R.string.finance_source_direct,
                R.string.finance_source_other
        };
        popup.getMenu().setGroupCheckable(1, true, true);
        for (int i = 0; i < keys.length; i++) {
            android.view.MenuItem item = popup.getMenu().add(
                    1, 30_000 + i, i, labels[i]);
            item.setCheckable(true);
            item.setChecked(keys[i].equals(selectedFinanceSource));
        }
        popup.setOnMenuItemClickListener(item -> {
            int index = item.getItemId() - 30_000;
            if (index < 0 || index >= keys.length) return false;
            selectedFinanceSource = keys[index];
            item.setChecked(true);
            updateAdvancedFinanceFilterLabels();
            applyFinanceFilters();
            return true;
        });
        popup.show();
    }

    private void showFinanceCategoryMenu(@NonNull View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        java.util.TreeSet<String> categories =
                new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (FinanceEntry entry : latestEntries) {
            if (entry.category != null && !entry.category.trim().isEmpty()) {
                categories.add(entry.category.trim());
            }
        }
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(categories);
        popup.getMenu().setGroupCheckable(2, true, true);
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            android.view.MenuItem item = popup.getMenu().add(
                    2, 31_000 + i, i,
                    value.isEmpty()
                            ? getString(R.string.finance_category_all)
                            : value);
            item.setCheckable(true);
            item.setChecked(value.equalsIgnoreCase(selectedFinanceCategory));
        }
        popup.setOnMenuItemClickListener(item -> {
            int index = item.getItemId() - 31_000;
            if (index < 0 || index >= values.size()) return false;
            selectedFinanceCategory = values.get(index);
            item.setChecked(true);
            updateAdvancedFinanceFilterLabels();
            applyFinanceFilters();
            return true;
        });
        popup.show();
    }

    private void resetFinanceFilters() {
        selectedFinanceSource = FinanceEntrySourceClassifier.SOURCE_ALL;
        selectedFinanceCategory = "";
        selectedFinanceFilter = R.id.finance_filter_all;
        binding.financeFilterGroup.check(R.id.finance_filter_all);
        binding.financeSearchInput.setText("");
        updateAdvancedFinanceFilterLabels();
        applyFinanceFilters();
    }

    private void updateAdvancedFinanceFilterLabels() {
        if (financeSourceChip != null) {
            financeSourceChip.setText(getString(
                    R.string.finance_source_chip,
                    financeSourceLabel(selectedFinanceSource)));
        }
        if (financeCategoryChip != null) {
            financeCategoryChip.setText(getString(
                    R.string.finance_category_chip,
                    selectedFinanceCategory.isEmpty()
                            ? getString(R.string.finance_category_all)
                            : selectedFinanceCategory));
        }
    }

    @NonNull
    private String financeSourceLabel(@NonNull String source) {
        if (FinanceEntrySourceClassifier.SOURCE_GROCERY.equals(source)) {
            return getString(R.string.finance_source_grocery);
        }
        if (FinanceEntrySourceClassifier.SOURCE_LOAN_MANAGER.equals(source)) {
            return getString(R.string.finance_source_loan_manager);
        }
        if (FinanceEntrySourceClassifier.SOURCE_MONEY_MANAGER.equals(source)) {
            return getString(R.string.finance_source_money_manager);
        }
        if (FinanceEntrySourceClassifier.SOURCE_DIRECT.equals(source)) {
            return getString(R.string.finance_source_direct);
        }
        if (FinanceEntrySourceClassifier.SOURCE_OTHER.equals(source)) {
            return getString(R.string.finance_source_other);
        }
        return getString(R.string.finance_source_all);
    }

    private void applyFinanceFilters() {
        applyFinanceFilters(latestEntries);
    }

    private void applyFinanceFilters(@NonNull List<FinanceEntry> source) {
        List<FinanceEntry> filtered = new ArrayList<>();
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        if (selectedFinanceFilter == R.id.finance_filter_week) {
            start.set(Calendar.DAY_OF_WEEK, start.getFirstDayOfWeek());
        } else if (selectedFinanceFilter == R.id.finance_filter_month) {
            start.set(Calendar.DAY_OF_MONTH, 1);
        }
        String startDate = new SimpleDateFormat(ISO_DATE_PATTERN, Locale.US)
                .format(start.getTime());
        String today = todayAsIsoDate();
        String query = binding.financeSearchInput.getText() == null
                ? ""
                : binding.financeSearchInput.getText().toString().trim();

        for (FinanceEntry entry : source) {
            if (!matchesFinanceSearch(entry, query)) continue;
            boolean include;
            if (selectedFinanceFilter == R.id.finance_filter_today) {
                include = today.equals(entry.transactionDate);
            } else if (selectedFinanceFilter == R.id.finance_filter_week
                    || selectedFinanceFilter == R.id.finance_filter_month) {
                include = entry.transactionDate.compareTo(startDate) >= 0
                        && entry.transactionDate.compareTo(today) <= 0;
            } else if (selectedFinanceFilter == R.id.finance_filter_income) {
                include = FinanceEntry.TYPE_INCOME.equals(entry.entryType);
            } else if (selectedFinanceFilter == R.id.finance_filter_expense) {
                include = FinanceEntry.TYPE_EXPENSE.equals(entry.entryType);
            } else {
                include = true;
            }
            if (include && !FinanceEntrySourceClassifier.matches(
                    entry, selectedFinanceSource)) {
                include = false;
            }
            if (include && !selectedFinanceCategory.isEmpty()
                    && !selectedFinanceCategory.equalsIgnoreCase(entry.category)) {
                include = false;
            }
            if (include) filtered.add(entry);
        }
        entryAdapter.submitList(filtered);
        visibleEntries = new ArrayList<>(filtered);
        boolean isEmpty = filtered.isEmpty();
        binding.financeRecyclerView.setVisibility(
                isEmpty ? View.GONE : View.VISIBLE);
        binding.financeEmptyState.setVisibility(
                isEmpty ? View.VISIBLE : View.GONE);
    }

    private boolean matchesFinanceSearch(@NonNull FinanceEntry entry,
                                         @NonNull String query) {
        if (query.isEmpty()) return true;
        String needle = query.toLowerCase(Locale.ROOT);
        return containsFinanceText(entry.category, needle)
                || containsFinanceText(entry.note, needle)
                || containsFinanceText(entry.entryType, needle)
                || containsFinanceText(entry.accountName, needle)
                || containsFinanceText(entry.paymentMethod, needle)
                || containsFinanceText(entry.paidByName, needle)
                || containsFinanceText(entry.participantNames, needle)
                || containsFinanceText(
                        FinanceEntrySourceClassifier.displayLabel(entry), needle);
    }

    private boolean containsFinanceText(@Nullable String value,
                                        @NonNull String needle) {
        return value != null
                && value.toLowerCase(Locale.ROOT).contains(needle);
    }

'''

text = text[:start] + advanced_filter_block + text[end:]
path.write_text(text, encoding="utf-8")


# Guard against accidental broad changes inside this script.
expected = {
    "app/src/main/java/com/tridev/familyhub/data/repository/GroceryRepository.java",
    "app/src/main/java/com/tridev/familyhub/data/repository/FinanceRepository.java",
    "app/src/main/java/com/tridev/familyhub/feature/grocery/GroceryFragment.java",
    "app/src/main/java/com/tridev/familyhub/feature/finance/FinanceFragment.java",
}
for file_name in expected:
    if not Path(file_name).is_file():
        raise SystemExit(f"Expected patched file missing: {file_name}")

print("Guarded Grocery/Finance patch applied to 4 source files")
