package com.tridev.familyhub.feature.familyaccount;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.repository.FamilyAccountRepository;
import com.tridev.familyhub.databinding.ActivityFamilySetupBinding;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.main.MainActivity;

import java.util.Locale;

public class FamilySetupActivity extends AppCompatActivity {

    private ActivityFamilySetupBinding binding;
    private FamilyAccountRepository repository;
    @Nullable private String pendingFamilyId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            openAuth();
            return;
        }

        binding = ActivityFamilySetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        repository = new FamilyAccountRepository();

        binding.buttonCreateFamily.setOnClickListener(v -> createFamily());
        binding.buttonJoinFamily.setOnClickListener(v -> requestJoin());
        binding.buttonPendingRefresh.setOnClickListener(v -> loadSession());
        binding.buttonCancelRequest.setOnClickListener(v -> cancelPendingRequest());

        loadSession();
    }

    private void loadSession() {
        setLoading(true);
        repository.loadSession(new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
            @Override
            public void onSuccess(
                    @Nullable FamilyAccountRepository.SessionState result
            ) {
                if (result == null || binding == null) {
                    return;
                }
                setLoading(false);

                if (result.isActive()) {
                    openMain();
                } else if (result.isPending()) {
                    pendingFamilyId = result.pendingFamilyId;
                    showPendingState();
                } else {
                    pendingFamilyId = null;
                    showSetupState();
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                setLoading(false);
                showError();
            }
        });
    }

    private void createFamily() {
        binding.inputFamilyName.setError(null);
        String familyName = valueOf(binding.editFamilyName);
        if (familyName.length() < 2) {
            binding.inputFamilyName.setError(getString(
                    R.string.family_account_name_error
            ));
            return;
        }

        setLoading(true);
        repository.createFamily(
                familyName,
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.CreateFamilyResult>() {
                    @Override
                    public void onSuccess(
                            @Nullable FamilyAccountRepository.CreateFamilyResult result
                    ) {
                        setLoading(false);
                        if (result == null) {
                            showError();
                            return;
                        }
                        showInviteCode(result.inviteCode, true);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        setLoading(false);
                        showError();
                    }
                }
        );
    }

    private void requestJoin() {
        binding.inputJoinCode.setError(null);
        String code = valueOf(binding.editJoinCode)
                .replace(" ", "")
                .replace("-", "")
                .toUpperCase(Locale.ROOT);

        if (code.length() != 10) {
            binding.inputJoinCode.setError(getString(
                    R.string.family_account_code_error
            ));
            return;
        }

        setLoading(true);
        repository.requestJoin(
                code,
                new FamilyAccountRepository.ResultCallback<String>() {
                    @Override
                    public void onSuccess(@Nullable String familyId) {
                        setLoading(false);
                        pendingFamilyId = familyId;
                        showPendingState();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        setLoading(false);
                        binding.inputJoinCode.setError(getString(
                                R.string.family_account_invite_invalid
                        ));
                    }
                }
        );
    }

    private void cancelPendingRequest() {
        if (pendingFamilyId == null) {
            return;
        }

        setLoading(true);
        repository.cancelJoinRequest(
                pendingFamilyId,
                new FamilyAccountRepository.ResultCallback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void result) {
                        setLoading(false);
                        pendingFamilyId = null;
                        showSetupState();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        setLoading(false);
                        showError();
                    }
                }
        );
    }

    private void showInviteCode(
            @NonNull String code,
            boolean continueToApp
    ) {
        MaterialAlertDialogBuilder dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.family_account_invite_ready)
                        .setMessage(getString(
                                R.string.family_account_invite_message,
                                code
                        ))
                        .setNeutralButton(
                                R.string.family_account_copy_code,
                                (ignored, which) -> copyCode(code)
                        );

        if (continueToApp) {
            dialog.setPositiveButton(
                    R.string.family_account_continue,
                    (ignored, which) -> openMain()
            );
        } else {
            dialog.setPositiveButton(R.string.ok, null);
        }

        dialog.show();
    }

    private void copyCode(@NonNull String code) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.family_account_invite_code_label),
                code
        ));
    }

    private void showSetupState() {
        binding.groupFamilySetup.setVisibility(View.VISIBLE);
        binding.groupPending.setVisibility(View.GONE);
        binding.tvSetupError.setVisibility(View.GONE);
    }

    private void showPendingState() {
        binding.groupFamilySetup.setVisibility(View.GONE);
        binding.groupPending.setVisibility(View.VISIBLE);
        binding.tvSetupError.setVisibility(View.GONE);
    }

    private void setLoading(boolean loading) {
        if (binding == null) {
            return;
        }
        binding.progressFamilySetup.setVisibility(
                loading ? View.VISIBLE : View.GONE
        );
        binding.buttonCreateFamily.setEnabled(!loading);
        binding.buttonJoinFamily.setEnabled(!loading);
        binding.buttonPendingRefresh.setEnabled(!loading);
        binding.buttonCancelRequest.setEnabled(!loading);
    }

    private void showError() {
        if (binding != null) {
            binding.tvSetupError.setText(R.string.family_account_error);
            binding.tvSetupError.setVisibility(View.VISIBLE);
        }
    }

    @NonNull
    private static String valueOf(@NonNull android.widget.EditText editText) {
        return editText.getText() == null
                ? ""
                : editText.getText().toString().trim();
    }

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void openAuth() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
