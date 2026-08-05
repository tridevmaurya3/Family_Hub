package com.tridev.familyhub.feature.dashboard;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.cards.ActionCardModel;
import com.tridev.familyhub.core.ui.cards.HeroCardModel;
import com.tridev.familyhub.core.ui.cards.StatusCardModel;
import com.tridev.familyhub.core.ui.cards.StatusCardView;
import com.tridev.familyhub.core.ui.search.SearchBarModel;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.model.DashboardData;
import com.tridev.familyhub.data.model.DashboardStats;
import com.tridev.familyhub.data.repository.DashboardRepository;
import com.tridev.familyhub.databinding.FragmentDashboardBinding;
import com.tridev.familyhub.feature.automation.FamilyAutomationActivity;
import com.tridev.familyhub.feature.documents.DocumentsFragment;
import com.tridev.familyhub.feature.familylive.FamilyLiveFragment;
import com.tridev.familyhub.feature.grocery.GroceryFragment;
import com.tridev.familyhub.feature.health.HealthFragment;
import com.tridev.familyhub.feature.insights.FamilyLocationReportsActivity;
import com.tridev.familyhub.feature.journey.FamilyJourneyActivity;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.notes.NotesFragment;
import com.tridev.familyhub.feature.passwordvault.PasswordVaultFragment;
import com.tridev.familyhub.feature.planner.PlannerFragment;
import com.tridev.familyhub.feature.profile.ProfilePhotoStore;
import com.tridev.familyhub.feature.property.PropertyFragment;
import com.tridev.familyhub.feature.safety.FamilySafetyCenterActivity;
import com.tridev.familyhub.feature.sos.FamilySosActivity;
import com.tridev.familyhub.feature.search.GlobalSearchActivity;
import com.tridev.familyhub.feature.vehicle.VehicleFragment;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Main Family Hub dashboard with organized unique feature shortcuts. */
public class DashboardFragment extends Fragment {

    private FragmentDashboardBinding binding;
    private DashboardRepository dashboardRepository;

    private StatusCardView financeStatusCard;
    private StatusCardView healthStatusCard;
    private StatusCardView familyStatusCard;
    private StatusCardView documentStatusCard;
    @Nullable private ImageView profileAvatar;

    private final NumberFormat currencyFormatter =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

    private final SimpleDateFormat reminderDateFormat =
            new SimpleDateFormat("dd MMM", Locale.getDefault());

    private final SimpleDateFormat reminderTimeFormat =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentDashboardBinding.inflate(
                inflater,
                container,
                false
        );
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        dashboardRepository = new DashboardRepository(requireContext());

        bindStatusCards();
        setupHeaderActions();
        setupSearchBar();
        setupHeroCard();
        setupStatusCards();
        setupActionCards();
        setupNotificationAction();
        setupDashboardHighlights();
        renderHeader();
        loadDashboardData();
    }

    /** Binds the stable Fluent header: menu, notification, then profile. */
    private void setupHeaderActions() {
        binding.dashboardMenuButton.setOnClickListener(v -> showFeatureMenu());
        binding.dashboardProfileCard.setOnClickListener(v -> openProfile());
        profileAvatar = binding.dashboardProfileAvatar;
        renderProfileAvatar();
    }

    private void renderProfileAvatar() {
        ImageView avatar = profileAvatar;
        if (avatar == null) {
            return;
        }
        avatar.setImageTintList(null);
        Bitmap bitmap = ProfilePhotoStore.load(requireContext());
        if (bitmap == null) {
            avatar.setPadding(dp(10), dp(10), dp(10), dp(10));
            avatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            avatar.setImageResource(R.drawable.ic_profile_person);
            return;
        }
        avatar.setPadding(0, 0, 0, 0);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageBitmap(bitmap);
    }

    /** Renders a time-aware greeting, account name and current date. */
    private void renderHeader() {
        if (binding == null) {
            return;
        }

        int hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int greetingResId;
        if (hourOfDay < 12) {
            greetingResId = R.string.dashboard_greeting_morning;
        } else if (hourOfDay < 17) {
            greetingResId = R.string.dashboard_greeting_afternoon;
        } else {
            greetingResId = R.string.dashboard_greeting_evening;
        }

        String greeting = getString(greetingResId);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String name = user == null ? null : user.getDisplayName();
        if (name != null && !name.trim().isEmpty()) {
            String firstName = name.trim().split("\\s+")[0];
            greeting = greeting + ", " + firstName;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(
                getString(R.string.dashboard_date_format),
                Locale.getDefault()
        );
        binding.dashboardGreeting.setText(greeting);
        binding.dashboardCurrentDate.setText(dateFormat.format(new Date()));
        renderProfileAvatar();
    }

    private void bindStatusCards() {
        financeStatusCard = binding.cardFinanceStatus;
        healthStatusCard = binding.cardHealthStatus;
        familyStatusCard = binding.cardFamilyStatus;
        documentStatusCard = binding.cardDocumentStatus;
    }

    private void setupSearchBar() {
        SearchBarModel searchBarModel = new SearchBarModel(
                getString(R.string.search_hint_dashboard),
                "",
                false,
                false
        );
        binding.dashboardSearchBar.setModel(searchBarModel);
        binding.dashboardSearchBar.setOnSearchActionListener(
                this::handleDashboardSearch
        );
    }

    private void handleDashboardSearch(@NonNull String query) {
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            Snackbar.make(
                    binding.getRoot(),
                    R.string.dashboard_search_empty,
                    Snackbar.LENGTH_SHORT
            ).show();
            return;
        }

        binding.dashboardSearchBar.clearSearchFocus();
        startActivity(new Intent(requireContext(), GlobalSearchActivity.class)
                .putExtra(GlobalSearchActivity.EXTRA_INITIAL_QUERY, query.trim()));
        return;

        /* Legacy keyword routing is intentionally retained below for source
           compatibility, but Global Search now handles every non-empty query. */
        /* if (normalizedQuery.contains("sos")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openActivity(FamilySosActivity.class);
            return;
        }
        if (normalizedQuery.contains("safety")
                || normalizedQuery.contains("alert")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openActivity(FamilySafetyCenterActivity.class);
            return;
        }
        if (normalizedQuery.contains("journey")
                || normalizedQuery.contains("route")
                || normalizedQuery.contains("history")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openActivity(FamilyJourneyActivity.class);
            return;
        }
        if (normalizedQuery.contains("report")
                || normalizedQuery.contains("insight")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openActivity(FamilyLocationReportsActivity.class);
            return;
        }
        if (normalizedQuery.contains("routine")
                || normalizedQuery.contains("automation")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openActivity(FamilyAutomationActivity.class);
            return;
        }
        if (normalizedQuery.contains("family live")
                || normalizedQuery.contains("location")
                || normalizedQuery.contains("live")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFamilyLive();
            return;
        }
        if (normalizedQuery.contains("family")
                || normalizedQuery.contains("member")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openTab(R.id.nav_family);
            return;
        }
        if (normalizedQuery.contains("reminder")
                || normalizedQuery.contains("schedule")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openTab(R.id.nav_reminders);
            return;
        }
        if (normalizedQuery.contains("finance")
                || normalizedQuery.contains("expense")
                || normalizedQuery.contains("income")
                || normalizedQuery.contains("money")
                || normalizedQuery.contains("balance")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openTab(R.id.nav_finance);
            return;
        }
        if (normalizedQuery.contains("document")
                || normalizedQuery.contains("pdf")
                || normalizedQuery.contains("certificate")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new DocumentsFragment());
            return;
        }
        if (normalizedQuery.contains("password")
                || normalizedQuery.contains("credential")
                || normalizedQuery.contains("login")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new PasswordVaultFragment());
            return;
        }
        if (normalizedQuery.contains("health")
                || normalizedQuery.contains("medicine")
                || normalizedQuery.contains("allergy")
                || normalizedQuery.contains("appointment")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new HealthFragment());
            return;
        }
        if (normalizedQuery.contains("vehicle")
                || normalizedQuery.contains("car")
                || normalizedQuery.contains("bike")
                || normalizedQuery.contains("insurance")
                || normalizedQuery.contains("puc")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new VehicleFragment());
            return;
        }
        if (normalizedQuery.contains("property")
                || normalizedQuery.contains("house")
                || normalizedQuery.contains("land")
                || normalizedQuery.contains("flat")
                || normalizedQuery.contains("shop")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new PropertyFragment());
            return;
        }
        if (normalizedQuery.contains("grocery")
                || normalizedQuery.contains("shopping")
                || normalizedQuery.contains("market")
                || normalizedQuery.contains("list")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new GroceryFragment());
            return;
        }
        if (normalizedQuery.contains("note")
                || normalizedQuery.contains("checklist")
                || normalizedQuery.contains("memo")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new NotesFragment());
            return;
        }
        if (normalizedQuery.contains("planner")
                || normalizedQuery.contains("calendar")
                || normalizedQuery.contains("event")
                || normalizedQuery.contains("task")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new PlannerFragment());
            return;
        }

        Snackbar.make(
                binding.getRoot(),
                getString(R.string.dashboard_search_no_result, query),
                Snackbar.LENGTH_LONG
        ).show(); */
    }

    private void setupHeroCard() {
        binding.dashboardHeroCard.setModel(new HeroCardModel(
                getString(R.string.family_status),
                getString(R.string.family_status_detail),
                R.drawable.ic_family_hub_mark,
                getString(R.string.family_live)
        ));
        binding.dashboardHeroCard.setOnActionClickListener(
                this::openFamilyLive
        );
    }

    private void setupStatusCards() {
        financeStatusCard.setModel(new StatusCardModel(
                getString(R.string.status_finance),
                currencyFormatter.format(0),
                getString(R.string.status_balance_available),
                R.drawable.ic_wallet
        ));
        healthStatusCard.setModel(new StatusCardModel(
                getString(R.string.status_health),
                getString(R.string.status_no_health_data),
                getString(R.string.status_health_update),
                R.drawable.ic_health
        ));
        familyStatusCard.setModel(new StatusCardModel(
                getString(R.string.status_family),
                getString(R.string.status_zero_members),
                getString(R.string.status_family_ready),
                R.drawable.ic_family
        ));
        documentStatusCard.setModel(new StatusCardModel(
                getString(R.string.status_documents),
                getString(R.string.status_zero_files),
                getString(R.string.status_documents_ready),
                R.drawable.ic_document
        ));

        financeStatusCard.setOnClickListener(v -> openTab(R.id.nav_finance));
        familyStatusCard.setOnClickListener(v -> openTab(R.id.nav_family));
        healthStatusCard.setOnClickListener(
                v -> openFeature(new HealthFragment()));
        documentStatusCard.setOnClickListener(
                v -> openFeature(new DocumentsFragment()));
    }

    /** Uses six unique dashboard shortcuts without repeating hero/status cards. */
    private void setupActionCards() {
        binding.actionPlanner.setOnClickListener(
                v -> openFeature(new PlannerFragment()));
        binding.actionGrocery.setOnClickListener(
                v -> openFeature(new GroceryFragment()));
        binding.actionDocuments.setOnClickListener(
                v -> openActivity(FamilySafetyCenterActivity.class));
        binding.actionVehicles.setOnClickListener(
                v -> openActivity(FamilyJourneyActivity.class));
        binding.actionNotes.setOnClickListener(
                v -> openActivity(FamilyLocationReportsActivity.class));
        binding.actionFamilyLive.setOnClickListener(
                v -> openActivity(FamilyAutomationActivity.class));

        binding.actionDocuments.setModel(new ActionCardModel(
                getString(R.string.dashboard_shortcut_safety),
                getString(R.string.dashboard_shortcut_safety_value),
                getString(R.string.dashboard_shortcut_safety_detail),
                R.drawable.ic_safe_place_shield,
                R.color.fh_primary,
                R.color.fh_primary_container
        ));
        binding.actionVehicles.setModel(new ActionCardModel(
                getString(R.string.dashboard_shortcut_journey),
                getString(R.string.dashboard_shortcut_journey_value),
                getString(R.string.dashboard_shortcut_journey_detail),
                R.drawable.ic_family_map_route,
                R.color.fh_secondary,
                R.color.fh_secondary_container
        ));
        binding.actionNotes.setModel(new ActionCardModel(
                getString(R.string.dashboard_shortcut_reports),
                getString(R.string.dashboard_shortcut_reports_value),
                getString(R.string.dashboard_shortcut_reports_detail),
                R.drawable.ic_family_map_route,
                R.color.fh_info,
                R.color.fh_info_container
        ));
        binding.actionFamilyLive.setModel(new ActionCardModel(
                getString(R.string.dashboard_shortcut_routines),
                getString(R.string.dashboard_shortcut_routines_value),
                getString(R.string.dashboard_shortcut_routines_detail),
                R.drawable.ic_family_automation,
                R.color.fh_warning,
                R.color.fh_warning_container
        ));
    }

    private void setupNotificationAction() {
        binding.notificationButton.setOnClickListener(
                v -> openTab(R.id.nav_reminders));
    }

    private void setupDashboardHighlights() {
        binding.dashboardBirthdayCard.setOnClickListener(
                v -> openTab(R.id.nav_family));
        binding.dashboardBillCard.setOnClickListener(
                v -> openTab(R.id.nav_reminders));
        binding.dashboardViewAll.setOnClickListener(
                v -> openTab(R.id.nav_reminders));
        binding.dashboardRetry.setOnClickListener(v -> loadDashboardData());
        binding.dashboardRefresh.setOnClickListener(v -> loadDashboardData());
        binding.dashboardSosButton.setOnClickListener(
                v -> showEmergencyDialerConfirmation());
    }

    private void showEmergencyDialerConfirmation() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dashboard_sos_confirm_title)
                .setMessage(R.string.dashboard_sos_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(
                        R.string.dashboard_sos_action,
                        (dialog, which) -> openEmergencyDialer()
                )
                .show();
    }

    private void openEmergencyDialer() {
        Intent dialIntent = new Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:112")
        );
        try {
            startActivity(dialIntent);
        } catch (ActivityNotFoundException error) {
            Snackbar.make(
                    binding.getRoot(),
                    R.string.dashboard_sos_unavailable,
                    Snackbar.LENGTH_LONG
            ).show();
        }
    }

    private void showFeatureMenu() {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).showFeatureMenu();
        }
    }

    private void openProfile() {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openProfile();
        }
    }

    private void openFamilyLive() {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openFeature(
                    new FamilyLiveFragment()
            );
        }
    }

    private void openFeature(@NonNull Fragment fragment) {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openFeature(fragment);
        }
    }

    private void openActivity(@NonNull Class<?> activityClass) {
        startActivity(new Intent(requireContext(), activityClass));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (dashboardRepository != null) {
            renderHeader();
            loadDashboardData();
        }
    }

    private void loadDashboardData() {
        if (binding == null) {
            return;
        }
        binding.dashboardLoading.setVisibility(View.VISIBLE);
        binding.dashboardErrorCard.setVisibility(View.GONE);

        dashboardRepository.loadDashboardData(
                data -> {
                    if (binding == null) {
                        return;
                    }
                    binding.dashboardLoading.setVisibility(View.GONE);
                    binding.dashboardErrorCard.setVisibility(View.GONE);
                    renderFinance(data.getStats());
                    renderCounts(data.getStats());
                    renderActionCards(data.getStats());
                    renderPrioritySummary(data.getStats());
                    renderReminder(data);
                    renderBirthday(data);
                    renderBill(data);
                    renderRecentActivity(data);
                },
                error -> {
                    if (binding == null) {
                        return;
                    }
                    binding.dashboardLoading.setVisibility(View.GONE);
                    binding.dashboardErrorCard.setVisibility(View.VISIBLE);
                }
        );
    }

    private void renderPrioritySummary(@NonNull DashboardStats stats) {
        int pending = stats.getPlannerOpen() + stats.getGroceryPending();
        int upcoming = stats.getUpcomingReminders();
        int urgent = stats.getDocumentsExpiringSoon()
                + stats.getVehiclesDueSoon();
        binding.dashboardPendingValue.setText(String.valueOf(pending));
        binding.dashboardUpcomingValue.setText(String.valueOf(upcoming));
        binding.dashboardUrgentValue.setText(String.valueOf(urgent));
        binding.dashboardPendingSource.setText(getString(
                R.string.dashboard_pending_breakdown,
                stats.getPlannerOpen(), stats.getGroceryPending()));
        binding.dashboardUpcomingSource.setText(getString(
                R.string.dashboard_upcoming_breakdown,
                stats.getUpcomingReminders()));
        binding.dashboardUrgentSource.setText(getString(
                R.string.dashboard_urgent_breakdown,
                stats.getDocumentsExpiringSoon(), stats.getVehiclesDueSoon()));
        binding.dashboardPendingCard.setOnClickListener(v ->
                openPendingPriority(stats));
        binding.dashboardUpcomingCard.setOnClickListener(v -> {
            if (stats.getUpcomingReminders() > 0) openTab(R.id.nav_reminders);
            else showNoPriorityData();
        });
        binding.dashboardUrgentCard.setOnClickListener(v ->
                openUrgentPriority(stats));
        binding.dashboardNotificationBadge.setText(String.valueOf(upcoming));
        binding.dashboardNotificationBadge.setVisibility(
                upcoming > 0 ? View.VISIBLE : View.GONE);
        binding.dashboardLastUpdated.setText(getString(
                R.string.dashboard_last_updated,
                new SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(new Date())));
        boolean empty = pending == 0 && upcoming == 0 && urgent == 0
                && stats.getTotalMembers() == 0 && stats.getDocuments() == 0
                && stats.getHealthAlerts() == 0 && stats.getIncome() == 0D
                && stats.getExpense() == 0D;
        binding.dashboardEmptyState.setVisibility(
                empty ? View.VISIBLE : View.GONE);
    }

    private void openPendingPriority(@NonNull DashboardStats stats) {
        int planner = stats.getPlannerOpen();
        int grocery = stats.getGroceryPending();
        if (planner <= 0 && grocery <= 0) {
            showNoPriorityData();
        } else if (planner > 0 && grocery <= 0) {
            openFeature(new PlannerFragment());
        } else if (grocery > 0 && planner <= 0) {
            openFeature(new GroceryFragment());
        } else {
            String[] labels = {
                    getString(R.string.dashboard_priority_planner, planner),
                    getString(R.string.dashboard_priority_grocery, grocery)
            };
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dashboard_priority_choose)
                    .setItems(labels, (dialog, which) -> {
                        if (which == 0) openFeature(new PlannerFragment());
                        else openFeature(new GroceryFragment());
                    }).show();
        }
    }

    private void openUrgentPriority(@NonNull DashboardStats stats) {
        int documents = stats.getDocumentsExpiringSoon();
        int vehicles = stats.getVehiclesDueSoon();
        if (documents <= 0 && vehicles <= 0) {
            showNoPriorityData();
        } else if (documents > 0 && vehicles <= 0) {
            openFeature(new DocumentsFragment());
        } else if (vehicles > 0 && documents <= 0) {
            openFeature(new VehicleFragment());
        } else {
            String[] labels = {
                    getString(R.string.dashboard_priority_documents, documents),
                    getString(R.string.dashboard_priority_vehicles, vehicles)
            };
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dashboard_priority_choose)
                    .setItems(labels, (dialog, which) -> {
                        if (which == 0) openFeature(new DocumentsFragment());
                        else openFeature(new VehicleFragment());
                    }).show();
        }
    }

    private void showNoPriorityData() {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), R.string.dashboard_priority_none,
                    Snackbar.LENGTH_SHORT).show();
        }
    }

    private void renderBirthday(@NonNull DashboardData data) {
        FamilyMember member = data.getNextBirthdayMember();
        if (!data.hasUpcomingBirthday() || member == null) {
            binding.dashboardBirthdayTitle.setText(
                    R.string.dashboard_no_birthday_title);
            binding.dashboardBirthdayDetail.setText(
                    R.string.dashboard_no_birthday_detail);
            return;
        }
        binding.dashboardBirthdayTitle.setText(member.name);
        binding.dashboardBirthdayDetail.setText(getString(
                R.string.dashboard_birthday_detail,
                member.relation,
                reminderDateFormat.format(new Date(data.getNextBirthdayAt()))
        ));
    }

    private void renderBill(@NonNull DashboardData data) {
        Reminder bill = data.getNextBillReminder();
        if (!data.hasUpcomingBill() || bill == null) {
            binding.dashboardBillTitle.setText(
                    R.string.dashboard_no_bill_title);
            binding.dashboardBillDetail.setText(
                    R.string.dashboard_no_bill_detail);
            return;
        }
        Date billDate = new Date(data.getNextBillTriggerAt());
        binding.dashboardBillTitle.setText(bill.title);
        binding.dashboardBillDetail.setText(getString(
                R.string.dashboard_bill_detail,
                reminderDateFormat.format(billDate),
                reminderTimeFormat.format(billDate)
        ));
    }

    private void renderRecentActivity(@NonNull DashboardData data) {
        if (data.hasUpcomingReminder() && data.getNextReminder() != null) {
            binding.dashboardRecentActivityDetail.setText(getString(
                    R.string.dashboard_activity_reminder,
                    data.getNextReminder().title
            ));
        } else if (data.getExpense() > 0d) {
            binding.dashboardRecentActivityDetail.setText(getString(
                    R.string.dashboard_activity_expense,
                    currencyFormatter.format(data.getExpense())
            ));
        } else if (data.getTotalMembers() > 0) {
            binding.dashboardRecentActivityDetail.setText(getString(
                    R.string.dashboard_activity_family,
                    data.getTotalMembers()
            ));
        } else {
            binding.dashboardRecentActivityDetail.setText(
                    R.string.dashboard_no_recent_activity);
        }
    }

    private void renderActionCards(@NonNull DashboardStats stats) {
        binding.actionPlanner.setModel(new ActionCardModel(
                getString(R.string.action_planner_title),
                getString(R.string.action_open_value, stats.getPlannerOpen()),
                getString(R.string.action_completed_value,
                        stats.getPlannerCompleted()),
                R.drawable.ic_planner,
                R.color.fh_module_reminders,
                R.color.fh_module_reminders_container
        ));

        binding.actionGrocery.setModel(new ActionCardModel(
                getString(R.string.action_grocery_title),
                getString(R.string.action_pending_value,
                        stats.getGroceryPending()),
                getString(R.string.action_purchased_value,
                        stats.getGroceryPurchased()),
                R.drawable.ic_grocery,
                R.color.fh_module_finance,
                R.color.fh_module_finance_container
        ));

        binding.actionDocuments.setModel(new ActionCardModel(
                getString(R.string.dashboard_shortcut_safety),
                getString(R.string.dashboard_shortcut_safety_value),
                getString(R.string.dashboard_shortcut_safety_detail),
                R.drawable.ic_safe_place_shield,
                R.color.fh_primary,
                R.color.fh_primary_container
        ));

        binding.actionVehicles.setModel(new ActionCardModel(
                getString(R.string.dashboard_shortcut_journey),
                getString(R.string.dashboard_shortcut_journey_value),
                getString(R.string.dashboard_shortcut_journey_detail),
                R.drawable.ic_family_map_route,
                R.color.fh_secondary,
                R.color.fh_secondary_container
        ));

        binding.actionNotes.setModel(new ActionCardModel(
                getString(R.string.dashboard_shortcut_reports),
                getString(R.string.dashboard_shortcut_reports_value),
                getString(R.string.dashboard_shortcut_reports_detail),
                R.drawable.ic_family_map_route,
                R.color.fh_info,
                R.color.fh_info_container
        ));

        binding.actionFamilyLive.setModel(new ActionCardModel(
                getString(R.string.dashboard_shortcut_routines),
                getString(R.string.dashboard_shortcut_routines_value),
                getString(R.string.dashboard_shortcut_routines_detail),
                R.drawable.ic_family_automation,
                R.color.fh_warning,
                R.color.fh_warning_container
        ));
    }

    private void renderFinance(@NonNull DashboardStats stats) {
        binding.dashboardMonthlyExpenseValue.setText(
                currencyFormatter.format(stats.getExpense()));
        com.tridev.familyhub.core.ui.SemanticValueStyler.apply(
                binding.dashboardMonthlyExpenseValue,
                -stats.getExpense()
        );
        String detail = getString(
                R.string.dashboard_finance_detail,
                currencyFormatter.format(stats.getIncome()),
                currencyFormatter.format(stats.getBalance())
        );
        binding.dashboardMonthlyExpenseDetail.setText(detail);
        financeStatusCard.setModel(new StatusCardModel(
                getString(R.string.status_finance),
                currencyFormatter.format(stats.getBalance()),
                detail,
                R.drawable.ic_wallet
        ));
        financeStatusCard.setValueColorBySign(stats.getBalance());
    }

    private void renderCounts(@NonNull DashboardStats stats) {
        int members = stats.getTotalMembers();
        int documents = stats.getDocuments();
        int healthRecords = stats.getHealthAlerts();

        familyStatusCard.setModel(new StatusCardModel(
                getString(R.string.status_family),
                getResources().getQuantityString(
                        R.plurals.dashboard_family_member_count,
                        members,
                        members
                ),
                getString(R.string.status_family_ready),
                R.drawable.ic_family
        ));
        documentStatusCard.setModel(new StatusCardModel(
                getString(R.string.status_documents),
                getResources().getQuantityString(
                        R.plurals.dashboard_document_count,
                        documents,
                        documents
                ),
                getString(R.string.dashboard_documents_local_detail),
                R.drawable.ic_document
        ));
        healthStatusCard.setModel(new StatusCardModel(
                getString(R.string.status_health),
                getResources().getQuantityString(
                        R.plurals.health_record_count,
                        healthRecords,
                        healthRecords
                ),
                getString(R.string.health_dashboard_detail),
                R.drawable.ic_health
        ));
    }

    private void renderReminder(@NonNull DashboardData data) {
        Reminder nextReminder = data.getNextReminder();
        if (!data.hasUpcomingReminder() || nextReminder == null) {
            binding.dashboardUpcomingReminderTitle.setText(
                    R.string.dashboard_no_upcoming_reminder_title);
            binding.dashboardUpcomingReminderDetail.setText(
                    R.string.dashboard_no_upcoming_reminder_detail);
            return;
        }
        Date reminderDate = new Date(data.getNextReminderTriggerAt());
        binding.dashboardUpcomingReminderTitle.setText(nextReminder.title);
        if (Reminder.REPEAT_DAILY.equals(nextReminder.repeatType)) {
            binding.dashboardUpcomingReminderDetail.setText(getString(
                    R.string.reminder_daily_at,
                    reminderTimeFormat.format(reminderDate)
            ));
        } else {
            binding.dashboardUpcomingReminderDetail.setText(getString(
                    R.string.dashboard_next_reminder_detail,
                    reminderDateFormat.format(reminderDate),
                    reminderTimeFormat.format(reminderDate)
            ));
        }
    }

    private void openTab(int destinationId) {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openTab(destinationId);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        if (dashboardRepository != null) {
            dashboardRepository.close();
            dashboardRepository = null;
        }
        financeStatusCard = null;
        healthStatusCard = null;
        familyStatusCard = null;
        documentStatusCard = null;
        profileAvatar = null;
        binding = null;
        super.onDestroyView();
    }
}
