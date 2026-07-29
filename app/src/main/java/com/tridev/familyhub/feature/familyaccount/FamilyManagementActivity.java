package com.tridev.familyhub.feature.familyaccount;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyRoles;
import com.tridev.familyhub.data.repository.FamilyAccountRepository;
import com.tridev.familyhub.databinding.ActivityFamilyManagementBinding;
import com.tridev.familyhub.databinding.ItemFamilyAccountMemberBinding;
import com.tridev.familyhub.databinding.ItemPendingJoinRequestBinding;

public class FamilyManagementActivity extends AppCompatActivity {

    private ActivityFamilyManagementBinding binding;
    private FamilyAccountRepository repository;
    @Nullable private String familyId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityFamilyManagementBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        repository = new FamilyAccountRepository();

        binding.buttonBack.setOnClickListener(v ->
                getOnBackPressedDispatcher().onBackPressed());
        binding.buttonGenerateInvite.setOnClickListener(v -> generateInvite());
        binding.buttonRefreshFamily.setOnClickListener(v -> loadSession());

        loadSession();
    }

    private void loadSession() {
        setLoading(true);
        repository.loadSession(new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
            @Override
            public void onSuccess(
                    @Nullable FamilyAccountRepository.SessionState result
            ) {
                if (result == null
                        || !result.isActive()
                        || !FamilyRoles.OWNER_ADMIN.equals(result.role)
                        || result.familyId == null) {
                    setLoading(false);
                    showError(R.string.family_admin_owner_required);
                    binding.buttonGenerateInvite.setVisibility(View.GONE);
                    return;
                }

                familyId = result.familyId;
                binding.buttonGenerateInvite.setVisibility(View.VISIBLE);
                loadAdminData();
            }

            @Override
            public void onError(@NonNull Exception error) {
                setLoading(false);
                showError(R.string.family_admin_load_error);
            }
        });
    }

    private void loadAdminData() {
        if (familyId == null) {
            return;
        }

        repository.loadAdminData(
                familyId,
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.AdminData>() {
                    @Override
                    public void onSuccess(
                            @Nullable FamilyAccountRepository.AdminData result
                    ) {
                        setLoading(false);
                        if (result == null) {
                            showError(R.string.family_admin_load_error);
                            return;
                        }
                        render(result);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        setLoading(false);
                        showError(R.string.family_admin_load_error);
                    }
                }
        );
    }

    private void render(@NonNull FamilyAccountRepository.AdminData data) {
        binding.tvAdminError.setVisibility(View.GONE);
        binding.tvFamilyName.setText(
                data.familyName == null || data.familyName.trim().isEmpty()
                        ? getString(R.string.family_account_title)
                        : data.familyName
        );

        binding.pendingContainer.removeAllViews();
        binding.memberContainer.removeAllViews();

        binding.tvPendingEmpty.setVisibility(
                data.pendingRequests.isEmpty() ? View.VISIBLE : View.GONE
        );

        LayoutInflater inflater = LayoutInflater.from(this);

        for (FamilyAccountRepository.PendingRequest request
                : data.pendingRequests) {
            ItemPendingJoinRequestBinding row =
                    ItemPendingJoinRequestBinding.inflate(
                            inflater,
                            binding.pendingContainer,
                            false
                    );
            row.tvPendingName.setText(displayName(request.displayName));
            row.tvPendingEmail.setText(request.email);
            row.tvPendingRole.setText(roleLabel(request.requestedRole));
            row.buttonApprove.setOnClickListener(v -> approve(request));
            row.buttonReject.setOnClickListener(v -> reject(request));
            binding.pendingContainer.addView(row.getRoot());
        }

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        String currentUid = currentUser == null ? "" : currentUser.getUid();

        for (FamilyAccountRepository.Member member : data.members) {
            ItemFamilyAccountMemberBinding row =
                    ItemFamilyAccountMemberBinding.inflate(
                            inflater,
                            binding.memberContainer,
                            false
                    );
            row.tvMemberName.setText(displayName(member.displayName));
            row.tvMemberEmail.setText(member.email);
            row.tvMemberRole.setText(roleLabel(member.role));

            boolean roleCanChange =
                    !member.uid.equals(currentUid)
                            && !FamilyRoles.OWNER_ADMIN.equals(member.role);
            row.buttonChangeRole.setVisibility(
                    roleCanChange ? View.VISIBLE : View.GONE
            );
            row.buttonChangeRole.setOnClickListener(v ->
                    showRoleDialog(member));

            binding.memberContainer.addView(row.getRoot());
        }
    }

    private void approve(
            @NonNull FamilyAccountRepository.PendingRequest request
    ) {
        if (familyId == null) {
            return;
        }

        setLoading(true);
        repository.approveRequest(
                familyId,
                request,
                refreshCallback()
        );
    }

    private void reject(
            @NonNull FamilyAccountRepository.PendingRequest request
    ) {
        if (familyId == null) {
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.family_admin_reject_title)
                .setMessage(getString(
                        R.string.family_admin_reject_message,
                        displayName(request.displayName)
                ))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.family_admin_reject, (dialog, which) -> {
                    setLoading(true);
                    repository.rejectRequest(
                            familyId,
                            request,
                            refreshCallback()
                    );
                })
                .show();
    }

    private void showRoleDialog(
            @NonNull FamilyAccountRepository.Member member
    ) {
        String[] values = {
                FamilyRoles.GUARDIAN,
                FamilyRoles.ADULT_MEMBER,
                FamilyRoles.CHILD,
                FamilyRoles.SENIOR_CITIZEN,
                FamilyRoles.GUEST
        };
        String[] labels = {
                getString(R.string.family_role_guardian),
                getString(R.string.family_role_adult),
                getString(R.string.family_role_child),
                getString(R.string.family_role_senior),
                getString(R.string.family_role_guest)
        };

        int[] selected = {indexOf(values, member.role)};

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(
                        R.string.family_admin_change_role_title,
                        displayName(member.displayName)
                ))
                .setSingleChoiceItems(
                        labels,
                        selected[0],
                        (dialog, which) -> selected[0] = which
                )
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.family_admin_save_role, (dialog, which) -> {
                    if (familyId == null) {
                        return;
                    }
                    setLoading(true);
                    repository.changeRole(
                            familyId,
                            member.uid,
                            values[selected[0]],
                            refreshCallback()
                    );
                })
                .show();
    }

    private void generateInvite() {
        if (familyId == null) {
            return;
        }

        setLoading(true);
        repository.createInvite(
                familyId,
                new FamilyAccountRepository.ResultCallback<String>() {
                    @Override
                    public void onSuccess(@Nullable String code) {
                        setLoading(false);
                        if (code == null) {
                            showError(R.string.family_admin_action_error);
                            return;
                        }
                        showInvite(code);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        setLoading(false);
                        showError(R.string.family_admin_action_error);
                    }
                }
        );
    }

    private void showInvite(@NonNull String code) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.family_account_invite_ready)
                .setMessage(getString(
                        R.string.family_account_invite_message,
                        code
                ))
                .setNeutralButton(
                        R.string.family_account_copy_code,
                        (dialog, which) -> copyCode(code)
                )
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void copyCode(@NonNull String code) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.family_account_invite_code_label),
                code
        ));
    }

    @NonNull
    private FamilyAccountRepository.ResultCallback<Void> refreshCallback() {
        return new FamilyAccountRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                loadAdminData();
            }

            @Override
            public void onError(@NonNull Exception error) {
                setLoading(false);
                showError(R.string.family_admin_action_error);
            }
        };
    }

    private void setLoading(boolean loading) {
        if (binding == null) {
            return;
        }
        binding.progressFamilyAdmin.setVisibility(
                loading ? View.VISIBLE : View.GONE
        );
        binding.buttonGenerateInvite.setEnabled(!loading);
        binding.buttonRefreshFamily.setEnabled(!loading);
    }

    private void showError(int messageRes) {
        binding.tvAdminError.setText(messageRes);
        binding.tvAdminError.setVisibility(View.VISIBLE);
    }

    @NonNull
    private String displayName(@NonNull String value) {
        return value.trim().isEmpty()
                ? getString(R.string.family_account_member_fallback)
                : value;
    }

    @NonNull
    private String roleLabel(@NonNull String role) {
        if (FamilyRoles.OWNER_ADMIN.equals(role)) {
            return getString(R.string.family_role_owner);
        } else if (FamilyRoles.GUARDIAN.equals(role)) {
            return getString(R.string.family_role_guardian);
        } else if (FamilyRoles.CHILD.equals(role)) {
            return getString(R.string.family_role_child);
        } else if (FamilyRoles.SENIOR_CITIZEN.equals(role)) {
            return getString(R.string.family_role_senior);
        } else if (FamilyRoles.GUEST.equals(role)) {
            return getString(R.string.family_role_guest);
        }
        return getString(R.string.family_role_adult);
    }

    private static int indexOf(
            @NonNull String[] values,
            @NonNull String target
    ) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(target)) {
                return index;
            }
        }
        return 1;
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
