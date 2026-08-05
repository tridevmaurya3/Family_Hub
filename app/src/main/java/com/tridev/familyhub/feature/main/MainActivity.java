package com.tridev.familyhub.feature.main;

import android.content.Intent;
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
import com.tridev.familyhub.feature.more.MoreFragment;
import com.tridev.familyhub.feature.profile.ProfileSettingsActivity;
import com.tridev.familyhub.feature.reminders.RemindersFragment;

/** Hosts the primary bottom navigation and feature screens. */
public class MainActivity extends AppCompatActivity {

    private static final String EXTRA_OPEN_DOCUMENTS_VAULT =
            "open_documents_vault";
    public static final String EXTRA_OPEN_GROCERY = "open_grocery";

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
            }
        }
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
        if (intent.getBooleanExtra(EXTRA_OPEN_GROCERY, false)) {
            intent.removeExtra(EXTRA_OPEN_GROCERY);
            clearSecondaryScreens();
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.main_content, new GroceryFragment())
                    .commit();
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
