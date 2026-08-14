package com.tridev.familyhub.feature.main;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.databinding.ActivityMainBinding;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.dashboard.DashboardFragment;
import com.tridev.familyhub.feature.documents.DocumentsFragment;
import com.tridev.familyhub.feature.family.FamilyFragment;
import com.tridev.familyhub.feature.finance.FinanceFragment;
import com.tridev.familyhub.feature.grocery.GroceryFragment;
import com.tridev.familyhub.feature.health.HealthFragment;
import com.tridev.familyhub.feature.notes.NotesFragment;
import com.tridev.familyhub.feature.passwordvault.PasswordVaultFragment;
import com.tridev.familyhub.feature.planner.PlannerFragment;
import com.tridev.familyhub.feature.property.PropertyFragment;
import com.tridev.familyhub.feature.vehicle.VehicleFragment;
import com.tridev.familyhub.feature.more.MoreFragment;
import com.tridev.familyhub.feature.profile.ProfileSettingsActivity;
import com.tridev.familyhub.feature.reminders.RemindersFragment;

/** Hosts the primary bottom navigation and feature screens. */
public class MainActivity extends AppCompatActivity {

    private static final String EXTRA_OPEN_DOCUMENTS_VAULT =
            "open_documents_vault";
    private static final String EXTRA_OPEN_HEALTH = "open_health";
    public static final String EXTRA_OPEN_GROCERY = "open_grocery";
    public static final String EXTRA_OPEN_ROUTE = "open_feature_route";
    public static final String ROUTE_FAMILY = "family";
    public static final String ROUTE_REMINDERS = "reminders";
    public static final String ROUTE_FINANCE = "finance";
    public static final String ROUTE_GROCERY = "grocery";
    public static final String ROUTE_DOCUMENTS = "documents";
    public static final String ROUTE_HEALTH = "health";
    public static final String ROUTE_VEHICLES = "vehicles";
    public static final String ROUTE_PROPERTY = "property";
    public static final String ROUTE_NOTES = "notes";
    public static final String ROUTE_PLANNER = "planner";
    public static final String ROUTE_VAULT = "vault";

    private ActivityMainBinding binding;
    private boolean redirectingToAuth;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!hasVerifiedSession()) {
            redirectToAuth();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();

        binding.fabAdd.setOnClickListener(v -> {
            Fragment active = getSupportFragmentManager()
                    .findFragmentById(R.id.main_content);
            if (active instanceof AddActionHost) {
                ((AddActionHost) active).onAddRequested();
            }
        });

        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(
                            @NonNull FragmentManager fm,
                            @NonNull Fragment fragment
                    ) {
                        if (binding != null) {
                            binding.fabAdd.setVisibility(
                                    fragment instanceof AddActionHost
                                            ? View.VISIBLE
                                            : View.GONE
                            );
                            boolean grocery = fragment instanceof GroceryFragment;
                            binding.fabAdd.setBackgroundTintList(ColorStateList.valueOf(
                                    getColor(grocery
                                            ? R.color.fh_module_grocery_container
                                            : R.color.fh_primary)));
                            binding.fabAdd.setImageTintList(ColorStateList.valueOf(
                                    getColor(grocery
                                            ? R.color.fh_module_grocery
                                            : R.color.fh_on_primary)));
                            binding.fabAdd.setCompatElevation(grocery ? 3f : 6f);
                        }
                    }
                },
                false
        );

        binding.bottomNavigation.setOnItemSelectedListener(destinationId -> {
            showDestination(destinationId);
            return true;
        });

        if (savedInstanceState == null) {
            if (!handleLaunchIntent(getIntent())) {
                binding.bottomNavigation.setSelectedItemId(R.id.nav_home);
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.main_content, new DashboardFragment())
                        .commitNow();
            }
        } else if (getSupportFragmentManager()
                .findFragmentById(R.id.main_content) == null) {
            binding.bottomNavigation.setSelectedItemId(R.id.nav_home);
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.main_content, new DashboardFragment())
                    .commitNow();
        } else {
            syncBottomNavigationWithRestoredFragment();
        }
    }

    /** Keeps restored content and navigation selection aligned after recreation. */
    private void syncBottomNavigationWithRestoredFragment() {
        Fragment restored = getSupportFragmentManager()
                .findFragmentById(R.id.main_content);
        int destinationId = R.id.nav_home;
        if (restored instanceof FamilyFragment) {
            destinationId = R.id.nav_family;
        } else if (restored instanceof RemindersFragment) {
            destinationId = R.id.nav_reminders;
        } else if (restored instanceof FinanceFragment) {
            destinationId = R.id.nav_finance;
        } else if (restored instanceof MoreFragment) {
            destinationId = R.id.nav_more;
        }
        binding.bottomNavigation.setSelectedItemId(destinationId);
    }

    private void applySystemBarInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(
                binding.getRoot(),
                (view, windowInsets) -> {
                    Insets safeInsets = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                                    | WindowInsetsCompat.Type.displayCutout()
                    );
                    binding.mainContent.setPadding(
                            safeInsets.left,
                            safeInsets.top,
                            safeInsets.right,
                            0
                    );
                    binding.bottomNavigation.setPadding(
                            safeInsets.left,
                            0,
                            safeInsets.right,
                            0
                    );

                    ViewGroup.MarginLayoutParams navigationParams =
                            (ViewGroup.MarginLayoutParams)
                                    binding.bottomNavigation.getLayoutParams();
                    int navigationMargin = Math.round(
                            6f * getResources()
                                    .getDisplayMetrics()
                                    .density
                    );
                    navigationParams.leftMargin = navigationMargin;
                    navigationParams.rightMargin = navigationMargin;
                    navigationParams.bottomMargin =
                            safeInsets.bottom + navigationMargin;
                    binding.bottomNavigation.setLayoutParams(
                            navigationParams
                    );

                    return windowInsets;
                }
        );
        ViewCompat.requestApplyInsets(binding.getRoot());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private boolean handleLaunchIntent(@Nullable Intent intent) {
        if (binding == null || intent == null) {
            return false;
        }
        String route = intent.getStringExtra(EXTRA_OPEN_ROUTE);
        if (route != null && !route.isEmpty()) {
            intent.removeExtra(EXTRA_OPEN_ROUTE);
            openRoute(route);
            return true;
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_GROCERY, false)) {
            intent.removeExtra(EXTRA_OPEN_GROCERY);
            clearSecondaryScreens();
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.main_content, new GroceryFragment())
                    .commit();
            return true;
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_HEALTH, false)) {
            intent.removeExtra(EXTRA_OPEN_HEALTH);
            openRoute(ROUTE_HEALTH);
            return true;
        }
        if (!intent.getBooleanExtra(EXTRA_OPEN_DOCUMENTS_VAULT, false)) {
            return false;
        }
        intent.removeExtra(EXTRA_OPEN_DOCUMENTS_VAULT);
        clearSecondaryScreens();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.main_content, new DocumentsFragment())
                .commit();
        return true;
    }

    private void openRoute(@NonNull String route) {
        clearSecondaryScreens();
        if (ROUTE_FAMILY.equals(route)) {
            binding.bottomNavigation.setSelectedItemId(R.id.nav_family);
        } else if (ROUTE_REMINDERS.equals(route)) {
            binding.bottomNavigation.setSelectedItemId(R.id.nav_reminders);
        } else if (ROUTE_FINANCE.equals(route)) {
            binding.bottomNavigation.setSelectedItemId(R.id.nav_finance);
        } else {
            Fragment fragment;
            if (ROUTE_GROCERY.equals(route)) fragment = new GroceryFragment();
            else if (ROUTE_DOCUMENTS.equals(route)) fragment = new DocumentsFragment();
            else if (ROUTE_HEALTH.equals(route)) fragment = new HealthFragment();
            else if (ROUTE_VEHICLES.equals(route)) fragment = new VehicleFragment();
            else if (ROUTE_PROPERTY.equals(route)) fragment = new PropertyFragment();
            else if (ROUTE_NOTES.equals(route)) fragment = new NotesFragment();
            else if (ROUTE_PLANNER.equals(route)) fragment = new PlannerFragment();
            else if (ROUTE_VAULT.equals(route)) fragment = new PasswordVaultFragment();
            else fragment = new DashboardFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.main_content, fragment).commit();
        }
    }

    public void openDocument(long documentId) {
        if (documentId <= 0L) return;
        clearSecondaryScreens();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_content,
                        DocumentsFragment.forDocument(documentId))
                .addToBackStack(null)
                .commit();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (binding != null && !hasVerifiedSession()) {
            redirectToAuth();
        }
    }

    public void openTab(@IdRes int destinationId) {
        if (binding != null) {
            binding.bottomNavigation.setSelectedItemId(destinationId);
            showDestination(destinationId);
        }
    }

    /** Returns a secondary module to the primary Home dashboard. */
    public void openHome() {
        if (binding != null) {
            FragmentManager manager = getSupportFragmentManager();
            if (manager.getBackStackEntryCount() > 0) {
                manager.popBackStack();
                return;
            }
            if (binding.bottomNavigation.getSelectedItemId() != R.id.nav_home) {
                binding.bottomNavigation.setSelectedItemId(R.id.nav_home);
            } else {
                showDestination(R.id.nav_home);
            }
        }
    }

    public void openFeature(@NonNull Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        android.R.anim.fade_in,
                        android.R.anim.fade_out,
                        android.R.anim.fade_in,
                        android.R.anim.fade_out
                )
                .replace(R.id.main_content, fragment)
                .addToBackStack(fragment.getClass().getSimpleName())
                .commit();
    }

    /** Opens the categorized hamburger menu from the dashboard. */
    public void showFeatureMenu() {
        FamilyFeatureMenu.show(this);
    }

    /** Opens the account profile from the dashboard avatar or feature menu. */
    public void openProfile() {
        startActivity(new Intent(this, ProfileSettingsActivity.class));
    }

    private void showDestination(@IdRes int destinationId) {
        clearSecondaryScreens();

        Fragment fragment;

        if (destinationId == R.id.nav_home) {
            fragment = new DashboardFragment();
        } else if (destinationId == R.id.nav_family) {
            fragment = new FamilyFragment();
        } else if (destinationId == R.id.nav_reminders) {
            fragment = new RemindersFragment();
        } else if (destinationId == R.id.nav_finance) {
            fragment = new FinanceFragment();
        } else {
            fragment = new MoreFragment();
        }

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.main_content, fragment)
                .commit();
    }

    private void clearSecondaryScreens() {
        FragmentManager fragmentManager = getSupportFragmentManager();

        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStackImmediate(
                    null,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE
            );
        }
    }

    private boolean hasVerifiedSession() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null && user.isEmailVerified();
    }

    private void redirectToAuth() {
        if (redirectingToAuth) {
            return;
        }
        redirectingToAuth = true;

        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
