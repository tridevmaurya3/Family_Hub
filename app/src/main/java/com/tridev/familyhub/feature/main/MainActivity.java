package com.tridev.familyhub.feature.main;

import android.os.Bundle;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import android.view.View;

import com.tridev.familyhub.R;
import com.tridev.familyhub.databinding.ActivityMainBinding;
import com.tridev.familyhub.feature.dashboard.DashboardFragment;
import com.tridev.familyhub.feature.family.FamilyFragment;
import com.tridev.familyhub.feature.finance.FinanceFragment;
import com.tridev.familyhub.feature.placeholder.SectionPlaceholderFragment;
import com.tridev.familyhub.feature.reminders.RemindersFragment;
import com.tridev.familyhub.feature.more.MoreFragment;

/**
 * Hosts the primary bottom navigation and feature screens.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.fabAdd.setOnClickListener(v -> {
            Fragment active = getSupportFragmentManager().findFragmentById(R.id.main_content);
            if (active instanceof AddActionHost) {
                ((AddActionHost) active).onAddRequested();
            }
        });

        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                        binding.fabAdd.setVisibility(
                                fragment instanceof AddActionHost ? View.VISIBLE : View.GONE
                        );
                    }
                }, false
        );

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            showDestination(item.getItemId());
            return true;
        });

        if (savedInstanceState == null) {
            binding.bottomNavigation.setSelectedItemId(R.id.nav_home);
        }
    }

    /**
     * Opens one of the five primary bottom-navigation destinations.
     */
    public void openTab(@IdRes int destinationId) {
        binding.bottomNavigation.setSelectedItemId(destinationId);
    }

    /**
     * Opens a secondary feature while keeping the current bottom-navigation
     * destination selected.
     *
     * The screen is added to the back stack, so the system Back button returns
     * to the previous screen.
     */
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

    /**
     * Removes secondary feature screens when the user selects a main tab.
     */
    private void clearSecondaryScreens() {
        FragmentManager fragmentManager = getSupportFragmentManager();

        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStackImmediate(
                    null,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE
            );
        }
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
