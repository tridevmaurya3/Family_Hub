package com.tridev.familyhub.feature.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.tridev.familyhub.R;
import com.tridev.familyhub.databinding.ActivityAuthBinding;
import com.tridev.familyhub.feature.familyaccount.FamilySetupActivity;

import java.util.Locale;

/**
 * Secure email/password entry point. New accounts must verify their email
 * before any family data can be opened.
 */
public class AuthActivity extends AppCompatActivity {

    private ActivityAuthBinding binding;
    private FirebaseAuth auth;
    private boolean registerMode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        auth = FirebaseAuth.getInstance();
        auth.useAppLanguage();

        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toggleAuthMode.addOnButtonCheckedListener(
                (group, checkedId, isChecked) -> {
                    if (isChecked) {
                        setRegisterMode(checkedId == R.id.button_register_mode);
                    }
                }
        );
        binding.toggleAuthMode.check(R.id.button_login_mode);

        binding.buttonAuthSubmit.setOnClickListener(v -> submit());
        binding.buttonResetPassword.setOnClickListener(v -> sendPasswordReset());
        binding.editPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
    }

    private void setRegisterMode(boolean registerMode) {
        this.registerMode = registerMode;
        binding.inputDisplayName.setVisibility(registerMode ? View.VISIBLE : View.GONE);
        binding.buttonResetPassword.setVisibility(registerMode ? View.GONE : View.VISIBLE);
        binding.buttonAuthSubmit.setText(registerMode
                ? R.string.auth_create_account
                : R.string.auth_sign_in);
        binding.tvAuthMessage.setVisibility(View.GONE);
        clearErrors();
    }

    private void submit() {
        clearErrors();

        String displayName = textOf(binding.editDisplayName);
        String email = textOf(binding.editEmail).toLowerCase(Locale.ROOT);
        String password = textOf(binding.editPassword);

        boolean valid = true;

        if (registerMode && displayName.length() < 2) {
            binding.inputDisplayName.setError(getString(R.string.auth_name_error));
            valid = false;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.inputEmail.setError(getString(R.string.auth_email_error));
            valid = false;
        }

        if (password.length() < 8) {
            binding.inputPassword.setError(getString(R.string.auth_password_error));
            valid = false;
        }

        if (!valid) {
            return;
        }

        setLoading(true);

        if (registerMode) {
            createAccount(displayName, email, password);
        } else {
            signIn(email, password);
        }
    }

    private void createAccount(
            @NonNull String displayName,
            @NonNull String email,
            @NonNull String password
    ) {
        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        setLoading(false);
                        showFailure(task.getException());
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();
                    if (user == null) {
                        setLoading(false);
                        showMessage(R.string.auth_error_generic, true);
                        return;
                    }

                    UserProfileChangeRequest profile =
                            new UserProfileChangeRequest.Builder()
                                    .setDisplayName(displayName)
                                    .build();

                    user.updateProfile(profile)
                            .continueWithTask(profileTask -> {
                                if (!profileTask.isSuccessful()) {
                                    Exception error = profileTask.getException();
                                    return Tasks.forException(error != null
                                            ? error
                                            : new IllegalStateException());
                                }
                                return user.sendEmailVerification();
                            })
                            .addOnCompleteListener(this, verificationTask -> {
                                auth.signOut();
                                setLoading(false);

                                if (!verificationTask.isSuccessful()) {
                                    showFailure(verificationTask.getException());
                                    return;
                                }

                                setRegisterMode(false);
                                binding.toggleAuthMode.check(R.id.button_login_mode);
                                binding.editPassword.setText(null);
                                showMessage(R.string.auth_verification_sent, false);
                            });
                });
    }

    private void signIn(@NonNull String email, @NonNull String password) {
        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        setLoading(false);
                        showFailure(task.getException());
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();
                    if (user == null) {
                        setLoading(false);
                        showMessage(R.string.auth_error_generic, true);
                        return;
                    }

                    user.reload().addOnCompleteListener(this, reloadTask -> {
                        FirebaseUser refreshedUser = auth.getCurrentUser();

                        if (!reloadTask.isSuccessful() || refreshedUser == null) {
                            auth.signOut();
                            setLoading(false);
                            showFailure(reloadTask.getException());
                            return;
                        }

                        if (!refreshedUser.isEmailVerified()) {
                            refreshedUser.sendEmailVerification()
                                    .addOnCompleteListener(this, verificationTask -> {
                                        auth.signOut();
                                        setLoading(false);
                                        showMessage(
                                                verificationTask.isSuccessful()
                                                        ? R.string.auth_email_not_verified
                                                        : R.string.auth_verification_send_failed,
                                                !verificationTask.isSuccessful()
                                        );
                                    });
                            return;
                        }

                        openFamilySetup();
                    });
                });
    }

    private void sendPasswordReset() {
        clearErrors();

        String email = textOf(binding.editEmail).toLowerCase(Locale.ROOT);
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.inputEmail.setError(getString(R.string.auth_email_error));
            return;
        }

        setLoading(true);
        auth.sendPasswordResetEmail(email)
                .addOnCompleteListener(this, task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        showMessage(R.string.auth_reset_sent, false);
                    } else {
                        showFailure(task.getException());
                    }
                });
    }

    private void openFamilySetup() {
        Intent intent = new Intent(this, FamilySetupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        binding.progressAuth.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.buttonAuthSubmit.setEnabled(!loading);
        binding.buttonResetPassword.setEnabled(!loading);
        binding.toggleAuthMode.setEnabled(!loading);
        binding.editDisplayName.setEnabled(!loading);
        binding.editEmail.setEnabled(!loading);
        binding.editPassword.setEnabled(!loading);
    }

    private void clearErrors() {
        binding.inputDisplayName.setError(null);
        binding.inputEmail.setError(null);
        binding.inputPassword.setError(null);
    }

    private void showFailure(@Nullable Exception error) {
        if (error instanceof FirebaseNetworkException) {
            showMessage(R.string.auth_network_error, true);
        } else if (error instanceof FirebaseTooManyRequestsException) {
            showMessage(R.string.auth_too_many_requests, true);
        } else if (error instanceof FirebaseAuthUserCollisionException) {
            showMessage(R.string.auth_account_exists, true);
        } else if (error instanceof FirebaseAuthWeakPasswordException) {
            showMessage(R.string.auth_password_error, true);
        } else if (error instanceof FirebaseAuthInvalidCredentialsException) {
            showMessage(R.string.auth_invalid_credentials, true);
        } else {
            showMessage(R.string.auth_error_generic, true);
        }
    }

    private void showMessage(int messageRes, boolean isError) {
        binding.tvAuthMessage.setText(messageRes);
        binding.tvAuthMessage.setTextColor(getColor(
                isError ? R.color.fh_error : R.color.fh_success
        ));
        binding.tvAuthMessage.setVisibility(View.VISIBLE);
    }

    @NonNull
    private static String textOf(@NonNull android.widget.EditText editText) {
        return editText.getText() == null
                ? ""
                : editText.getText().toString().trim();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
