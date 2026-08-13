package com.tridev.familyhub.feature.familyaccount;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyRoles;
import com.tridev.familyhub.data.repository.FamilyAccountRepository;
import com.tridev.familyhub.data.repository.HouseholdRepository;
import com.tridev.familyhub.databinding.ActivityHouseholdManagementBinding;
import com.tridev.familyhub.databinding.DialogHouseholdEditorBinding;
import com.tridev.familyhub.databinding.ItemHouseholdBinding;

import java.util.ArrayList;
import java.util.List;

public class HouseholdManagementActivity extends AppCompatActivity {

    private ActivityHouseholdManagementBinding binding;
    private HouseholdRepository repository;
    @Nullable private String familyId;
    @Nullable private HouseholdRepository.Data latestData;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHouseholdManagementBinding.inflate(
                getLayoutInflater()
        );
        setContentView(binding.getRoot());
        repository = new HouseholdRepository();

        binding.buttonBack.setOnClickListener(v ->
                getOnBackPressedDispatcher().onBackPressed());
        binding.buttonRefreshHouseholds.setOnClickListener(v -> loadSession());
        binding.buttonAddHousehold.setOnClickListener(v ->
                showCreateHousehold());
        loadSession();
    }

    private void loadSession() {
        setLoading(true);
        new FamilyAccountRepository().loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override
                    public void onSuccess(
                            @Nullable FamilyAccountRepository.SessionState session
                    ) {
                        if (session == null
                                || !session.isActive()
                                || session.familyId == null
                                || !FamilyRoles.OWNER_ADMIN.equals(
                                session.role
                        )) {
                            setLoading(false);
                            showError(R.string.family_admin_owner_required);
                            binding.buttonAddHousehold.setVisibility(View.GONE);
                            return;
                        }
                        familyId = session.familyId;
                        binding.buttonAddHousehold.setVisibility(View.VISIBLE);
                        loadHouseholds();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        setLoading(false);
                        showError(R.string.household_load_error);
                    }
                }
        );
    }

    private void loadHouseholds() {
        if (familyId == null) {
            return;
        }
        setLoading(true);
        repository.load(
                familyId,
                getString(R.string.household_primary),
                new FamilyAccountRepository.ResultCallback<HouseholdRepository.Data>() {
                    @Override
                    public void onSuccess(
                            @Nullable HouseholdRepository.Data data
                    ) {
                        setLoading(false);
                        if (data == null) {
                            showError(R.string.household_load_error);
                            return;
                        }
                        latestData = data;
                        render(data);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        setLoading(false);
                        showError(R.string.household_load_error);
                    }
                }
        );
    }

    private void render(@NonNull HouseholdRepository.Data data) {
        binding.tvHouseholdError.setVisibility(View.GONE);
        binding.householdContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (HouseholdRepository.Household household : data.households) {
            ItemHouseholdBinding row = ItemHouseholdBinding.inflate(
                    inflater,
                    binding.householdContainer,
                    false
            );
            row.tvHouseholdName.setText(household.name);
            row.tvHouseholdGuardian.setText(getString(
                    R.string.household_guardian_format,
                    displayName(household.guardianName)
            ));
            row.tvHouseholdMembers.setText(getResources().getQuantityString(
                    R.plurals.household_member_count,
                    household.assignedUids.size(),
                    household.assignedUids.size()
            ));
            row.buttonChangeGuardian.setOnClickListener(v ->
                    showGuardianPicker(household));
            row.buttonAssignMembers.setOnClickListener(v ->
                    showMemberAssignment(household));
            binding.householdContainer.addView(row.getRoot());
        }
    }

    private void showCreateHousehold() {
        HouseholdRepository.Data data = latestData;
        if (familyId == null || data == null || data.members.isEmpty()) {
            showError(R.string.household_members_required);
            return;
        }

        DialogHouseholdEditorBinding editor =
                DialogHouseholdEditorBinding.inflate(getLayoutInflater());
        String[] labels = memberLabels(data.members);
        editor.householdGuardianInput.setAdapter(new ArrayAdapter<>(
                this,
                R.layout.item_form_dropdown,
                labels
        ));
        editor.householdGuardianInput.setText(labels[0], false);
        int[] selectedGuardian = {0};
        editor.householdGuardianInput.setOnItemClickListener(
                (parent, view, position, id) ->
                        selectedGuardian[0] = position
        );

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setView(editor.getRoot())
                        .create();
        editor.buttonCancelHousehold.setOnClickListener(v -> dialog.dismiss());
        editor.buttonSaveHousehold.setOnClickListener(v -> {
            String name = editor.householdNameInput.getText() == null
                    ? ""
                    : editor.householdNameInput.getText()
                    .toString().trim();
            if (name.length() < 2) {
                editor.householdNameLayout.setError(
                        getString(R.string.household_name_error)
                );
                return;
            }
            editor.householdNameLayout.setError(null);
            dialog.dismiss();
            setLoading(true);
            repository.create(
                    familyId,
                    name,
                    data.members.get(selectedGuardian[0]).uid,
                    actionCallback()
            );
        });
        dialog.show();
    }

    private void showGuardianPicker(
            @NonNull HouseholdRepository.Household household
    ) {
        HouseholdRepository.Data data = latestData;
        if (familyId == null || data == null || data.members.isEmpty()) {
            return;
        }
        String[] labels = memberLabels(data.members);
        int checked = 0;
        for (int index = 0; index < data.members.size(); index++) {
            if (data.members.get(index).uid.equals(household.guardianUid)) {
                checked = index;
                break;
            }
        }
        int[] selected = {checked};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.household_change_guardian)
                .setSingleChoiceItems(
                        labels,
                        checked,
                        (dialog, which) -> selected[0] = which
                )
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.family_admin_save_role, (dialog, which) -> {
                    setLoading(true);
                    repository.changeGuardian(
                            familyId,
                            household.householdId,
                            data.members.get(selected[0]).uid,
                            actionCallback()
                    );
                })
                .show();
    }

    private void showMemberAssignment(
            @NonNull HouseholdRepository.Household household
    ) {
        HouseholdRepository.Data data = latestData;
        if (familyId == null || data == null || data.members.isEmpty()) {
            return;
        }
        String[] labels = memberLabels(data.members);
        boolean[] checked = new boolean[data.members.size()];
        for (int index = 0; index < data.members.size(); index++) {
            checked[index] = household.assignedUids.contains(
                    data.members.get(index).uid
            );
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.household_assign_members)
                .setMultiChoiceItems(
                        labels,
                        checked,
                        (dialog, which, isChecked) ->
                                checked[which] = isChecked
                )
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.household_save_assignment, (dialog, which) -> {
                    List<String> selected = new ArrayList<>();
                    for (int index = 0; index < data.members.size(); index++) {
                        String uid = data.members.get(index).uid;
                        if (checked[index]
                                || uid.equals(household.guardianUid)) {
                            selected.add(uid);
                        }
                    }
                    setLoading(true);
                    repository.assignMembers(
                            familyId,
                            household.householdId,
                            selected,
                            actionCallback()
                    );
                })
                .show();
    }

    @NonNull
    private FamilyAccountRepository.ResultCallback<Void> actionCallback() {
        return new FamilyAccountRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                loadHouseholds();
            }

            @Override
            public void onError(@NonNull Exception error) {
                setLoading(false);
                showError(R.string.household_action_error);
            }
        };
    }

    @NonNull
    private String[] memberLabels(
            @NonNull List<HouseholdRepository.Member> members
    ) {
        String[] labels = new String[members.size()];
        for (int index = 0; index < members.size(); index++) {
            HouseholdRepository.Member member = members.get(index);
            String name = displayName(member.displayName);
            labels[index] = member.email.isEmpty()
                    ? name
                    : name + " • " + member.email;
        }
        return labels;
    }

    @NonNull
    private String displayName(@NonNull String value) {
        return value.trim().isEmpty()
                ? getString(R.string.family_account_member_fallback)
                : value.trim();
    }

    private void setLoading(boolean loading) {
        if (binding == null) {
            return;
        }
        binding.progressHouseholds.setVisibility(
                loading ? View.VISIBLE : View.GONE
        );
        binding.buttonAddHousehold.setEnabled(!loading);
        binding.buttonRefreshHouseholds.setEnabled(!loading);
    }

    private void showError(int messageRes) {
        if (binding == null) {
            return;
        }
        binding.tvHouseholdError.setText(messageRes);
        binding.tvHouseholdError.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
