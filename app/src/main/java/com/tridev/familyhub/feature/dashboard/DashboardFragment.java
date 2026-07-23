package com.tridev.familyhub.feature.dashboard;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.cards.HeroCardModel;
import com.tridev.familyhub.core.ui.cards.StatusCardModel;
import com.tridev.familyhub.core.ui.cards.StatusCardView;
import com.tridev.familyhub.core.ui.search.SearchBarModel;
import com.tridev.familyhub.data.model.DashboardData;
import com.tridev.familyhub.data.model.DashboardStats;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.repository.DashboardRepository;
import com.tridev.familyhub.databinding.FragmentDashboardBinding;
import com.tridev.familyhub.feature.familylive.FamilyLiveFragment;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.documents.DocumentsFragment;
import com.tridev.familyhub.feature.passwordvault.PasswordVaultFragment;
import com.tridev.familyhub.feature.health.HealthFragment;
import com.tridev.familyhub.feature.vehicle.VehicleFragment;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Main Family Hub dashboard.
 *
 * Displays:
 * - Reusable search bar
 * - Reusable hero card
 * - Quick actions
 * - Reusable overview status cards
 * - Upcoming reminder
 * - Monthly finance summary
 */
public class DashboardFragment extends Fragment {

    private FragmentDashboardBinding binding;

    private DashboardRepository dashboardRepository;

    private StatusCardView financeStatusCard;
    private StatusCardView healthStatusCard;
    private StatusCardView familyStatusCard;
    private StatusCardView documentStatusCard;

    private final NumberFormat currencyFormatter =
            NumberFormat.getCurrencyInstance(
                    new Locale("en", "IN")
            );

    private final SimpleDateFormat reminderDateFormat =
            new SimpleDateFormat(
                    "dd MMM",
                    Locale.getDefault()
            );

    private final SimpleDateFormat reminderTimeFormat =
            new SimpleDateFormat(
                    "hh:mm a",
                    Locale.getDefault()
            );

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

        dashboardRepository =
                new DashboardRepository(requireContext());

        bindStatusCards();

        setupSearchBar();
        setupHeroCard();
        setupStatusCards();
        setupQuickActions();
        setupNotificationAction();

        loadDashboardData();
    }

    /**
     * Connects reusable status cards with ViewBinding.
     */
    private void bindStatusCards() {
        financeStatusCard =
                binding.cardFinanceStatus;

        healthStatusCard =
                binding.cardHealthStatus;

        familyStatusCard =
                binding.cardFamilyStatus;

        documentStatusCard =
                binding.cardDocumentStatus;
    }

    /**
     * Configures the reusable dashboard search bar.
     */
    private void setupSearchBar() {
        SearchBarModel searchBarModel =
                new SearchBarModel(
                        getString(
                                R.string.search_hint_dashboard
                        ),
                        "",
                        true,
                        true
                );

        binding.dashboardSearchBar.setModel(
                searchBarModel
        );

        binding.dashboardSearchBar.setOnSearchActionListener(
                this::handleDashboardSearch
        );

        binding.dashboardSearchBar.setOnVoiceClickListener(
                view -> Snackbar.make(
                        view,
                        R.string.dashboard_voice_search_coming_soon,
                        Snackbar.LENGTH_SHORT
                ).show()
        );

        binding.dashboardSearchBar.setOnFilterClickListener(
                view -> Snackbar.make(
                        view,
                        R.string.dashboard_filter_coming_soon,
                        Snackbar.LENGTH_SHORT
                ).show()
        );
    }

    /**
     * Opens a matching module for the submitted dashboard query.
     */
    private void handleDashboardSearch(
            @NonNull String query
    ) {
        String normalizedQuery =
                query.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalizedQuery.isEmpty()) {
            Snackbar.make(
                    binding.getRoot(),
                    R.string.dashboard_search_empty,
                    Snackbar.LENGTH_SHORT
            ).show();

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

        if (normalizedQuery.contains("document") || normalizedQuery.contains("pdf") || normalizedQuery.contains("certificate")) {
            binding.dashboardSearchBar.clearSearchFocus();
            openFeature(new DocumentsFragment());
            return;
        }

        if (normalizedQuery.contains("password") || normalizedQuery.contains("credential") || normalizedQuery.contains("login")) {
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

        Snackbar.make(
                binding.getRoot(),
                getString(
                        R.string.dashboard_search_no_result,
                        query
                ),
                Snackbar.LENGTH_LONG
        ).show();
    }

    /**
     * Configures the reusable dashboard hero card.
     */
    private void setupHeroCard() {
        HeroCardModel heroCardModel =
                new HeroCardModel(
                        getString(R.string.family_status),
                        getString(R.string.family_status_detail),
                        R.drawable.ic_family_hub_mark,
                        getString(R.string.family_live)
                );

        binding.dashboardHeroCard.setModel(
                heroCardModel
        );

        binding.dashboardHeroCard.setOnActionClickListener(
                this::openFamilyLive
        );
    }

    /**
     * Configures reusable dashboard overview cards.
     */
    private void setupStatusCards() {
        financeStatusCard.setModel(
                new StatusCardModel(
                        getString(R.string.status_finance),
                        currencyFormatter.format(0),
                        getString(
                                R.string.status_balance_available
                        ),
                        R.drawable.ic_wallet
                )
        );

        healthStatusCard.setModel(
                new StatusCardModel(
                        getString(R.string.status_health),
                        getString(
                                R.string.status_no_health_data
                        ),
                        getString(
                                R.string.status_health_update
                        ),
                        R.drawable.ic_wallet
                )
        );

        familyStatusCard.setModel(
                new StatusCardModel(
                        getString(R.string.status_family),
                        getString(
                                R.string.status_zero_members
                        ),
                        getString(
                                R.string.status_family_ready
                        ),
                        R.drawable.ic_family
                )
        );

        documentStatusCard.setModel(
                new StatusCardModel(
                        getString(R.string.status_documents),
                        getString(
                                R.string.status_zero_files
                        ),
                        getString(
                                R.string.status_documents_ready
                        ),
                        R.drawable.ic_wallet
                )
        );

        financeStatusCard.setOnClickListener(
                view -> openTab(
                        R.id.nav_finance
                )
        );

        familyStatusCard.setOnClickListener(
                view -> openTab(
                        R.id.nav_family
                )
        );

        healthStatusCard.setOnClickListener(
                view -> openFeature(new HealthFragment())
        );

        documentStatusCard.setOnClickListener(
                view -> openFeature(new DocumentsFragment())
        );
    }

    /**
     * Configures dashboard quick-action cards.
     */
    private void setupQuickActions() {
        binding.quickFamily.setOnClickListener(
                view -> openTab(
                        R.id.nav_family
                )
        );

        binding.quickReminder.setOnClickListener(
                view -> openTab(
                        R.id.nav_reminders
                )
        );

        binding.quickExpense.setOnClickListener(
                view -> openTab(
                        R.id.nav_finance
                )
        );

        binding.quickFamilyLive.setOnClickListener(
                view -> openFamilyLive()
        );
    }

    /**
     * Configures the notification action.
     */
    private void setupNotificationAction() {
        binding.notificationButton.setOnClickListener(
                view -> Snackbar.make(
                        view,
                        R.string.notifications_clear,
                        Snackbar.LENGTH_SHORT
                ).show()
        );
    }

    /**
     * Displays a temporary message for unfinished modules.
     */
    private void showComingSoonMessage(
            @NonNull View view,
            @NonNull String message
    ) {
        Snackbar.make(
                view,
                message,
                Snackbar.LENGTH_SHORT
        ).show();
    }

    /**
     * Opens the Family Live feature screen.
     */
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

    @Override
    public void onResume() {
        super.onResume();

        if (dashboardRepository != null) {
            loadDashboardData();
        }
    }

    private void loadDashboardData() {
        dashboardRepository.loadDashboardData(data -> {
            if (binding == null) {
                return;
            }

            renderFinance(data.getStats());
            renderCounts(data.getStats());
            renderReminder(data);
        });
    }

    private void renderFinance(@NonNull DashboardStats stats) {
        binding.dashboardMonthlyExpenseValue.setText(
                currencyFormatter.format(stats.getExpense())
        );

        String detail = getString(
                R.string.dashboard_finance_detail,
                currencyFormatter.format(stats.getIncome()),
                currencyFormatter.format(stats.getBalance())
        );
        binding.dashboardMonthlyExpenseDetail.setText(detail);

        financeStatusCard.setModel(
                new StatusCardModel(
                        getString(R.string.status_finance),
                        currencyFormatter.format(stats.getBalance()),
                        detail,
                        R.drawable.ic_wallet
                )
        );
    }

    private void renderCounts(@NonNull DashboardStats stats) {
        int members = stats.getTotalMembers();
        int documents = stats.getDocuments();
        int healthRecords = stats.getHealthAlerts();

        familyStatusCard.setModel(
                new StatusCardModel(
                        getString(R.string.status_family),
                        members + (members == 1 ? " member" : " members"),
                        getString(R.string.status_family_ready),
                        R.drawable.ic_family
                )
        );

        documentStatusCard.setModel(
                new StatusCardModel(
                        getString(R.string.status_documents),
                        documents + (documents == 1 ? " file" : " files"),
                        "Stored securely on this device",
                        R.drawable.ic_wallet
                )
        );

        healthStatusCard.setModel(
                new StatusCardModel(
                        getString(R.string.status_health),
                        getResources().getQuantityString(
                                R.plurals.health_record_count,
                                healthRecords,
                                healthRecords
                        ),
                        getString(R.string.health_dashboard_detail),
                        R.drawable.ic_health
                )
        );
    }

    private void renderReminder(@NonNull DashboardData data) {
        Reminder nextReminder = data.getNextReminder();
        if (!data.hasUpcomingReminder() || nextReminder == null) {
            binding.dashboardUpcomingReminderTitle.setText(
                    R.string.dashboard_no_upcoming_reminder_title
            );
            binding.dashboardUpcomingReminderDetail.setText(
                    R.string.dashboard_no_upcoming_reminder_detail
            );
            return;
        }

        Date reminderDate = new Date(data.getNextReminderTriggerAt());
        binding.dashboardUpcomingReminderTitle.setText(nextReminder.title);

        if (Reminder.REPEAT_DAILY.equals(nextReminder.repeatType)) {
            binding.dashboardUpcomingReminderDetail.setText(
                    getString(
                            R.string.reminder_daily_at,
                            reminderTimeFormat.format(reminderDate)
                    )
            );
        } else {
            binding.dashboardUpcomingReminderDetail.setText(
                    getString(
                            R.string.dashboard_next_reminder_detail,
                            reminderDateFormat.format(reminderDate),
                            reminderTimeFormat.format(reminderDate)
                    )
            );
        }
    }

    /**
     * Opens a primary bottom-navigation tab.
     */
    private void openTab(int destinationId) {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openTab(
                    destinationId
            );
        }
    }

    @Override
    public void onDestroyView() {
        if (dashboardRepository != null) {
            dashboardRepository.close();
            dashboardRepository = null;
        }

        financeStatusCard =
                null;

        healthStatusCard =
                null;

        familyStatusCard =
                null;

        documentStatusCard =
                null;

        binding =
                null;

        super.onDestroyView();
    }
}
