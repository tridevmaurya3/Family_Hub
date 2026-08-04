package com.tridev.familyhub.feature.family;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.model.FamilyRoles;
import com.tridev.familyhub.data.repository.FamilyAccountRepository;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.databinding.DialogMemberEditorBinding;
import com.tridev.familyhub.databinding.FragmentFamilyBinding;
import com.tridev.familyhub.feature.family.adapter.FamilyMemberAdapter;
import com.tridev.familyhub.feature.familyaccount.FamilyManagementActivity;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Complete local-only CRUD screen for family profiles. */
public class FamilyFragment extends Fragment implements com.tridev.familyhub.feature.main.AddActionHost {

    private static final String ISO_DATE_PATTERN = "yyyy-MM-dd";
    private static final String[] FAMILY_ROLES = {
            FamilyMember.ROLE_GUARDIAN,
            FamilyMember.ROLE_ADULT,
            FamilyMember.ROLE_CHILD
    };

    private FragmentFamilyBinding binding;
    private FamilyMemberAdapter memberAdapter;
    private FamilyMemberRepository repository;
    private FamilyAccountRepository familyAccountRepository;
    private int memberLoadGeneration;
    private ActivityResultLauncher<String[]> photoPicker;
    private DialogMemberEditorBinding activeEditor;
    private String selectedPhotoUri = "";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        photoPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null || activeEditor == null) {
                        return;
                    }
                    try {
                        requireContext().getContentResolver()
                                .takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                );
                    } catch (SecurityException ignored) {
                        // The selected image remains available for this session.
                    }
                    selectedPhotoUri = uri.toString();
                    showPhoto(activeEditor, selectedPhotoUri);
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFamilyBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repository = new FamilyMemberRepository(requireContext());
        familyAccountRepository = new FamilyAccountRepository();
        memberAdapter = new FamilyMemberAdapter(new FamilyMemberAdapter.MemberActionListener() {
            @Override
            public void onEdit(FamilyMember member) {
                showMemberEditor(member);
            }

            @Override
            public void onDelete(FamilyMember member) {
                confirmDelete(member);
            }
        });

        binding.memberRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.memberRecyclerView.setAdapter(memberAdapter);
        binding.emptyAddMemberButton.setOnClickListener(v -> showMemberEditor(null));
        binding.memberSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // No action needed before filtering.
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Filtering is applied after the user-visible text has updated.
            }

            @Override
            public void afterTextChanged(Editable searchText) {
                loadMembers(searchText.toString());
            }
        });
        loadMembers("");
    }

    @Override
    public void onAddRequested() {
        showMemberEditor(null);
    }

    private void loadMembers(String query) {
        int generation = ++memberLoadGeneration;
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        repository.loadMembers(query, localMembers -> {
            if (binding == null || generation != memberLoadGeneration) {
                return;
            }
            familyAccountRepository.loadAuthorisedMembers(
                    new FamilyAccountRepository.ResultCallback<List<FamilyAccountRepository.Member>>() {
                        @Override
                        public void onSuccess(
                                @Nullable List<FamilyAccountRepository.Member> cloudMembers
                        ) {
                            if (binding == null
                                    || generation != memberLoadGeneration) {
                                return;
                            }
                            renderMembers(mergeMembers(
                                    localMembers,
                                    cloudMembers == null
                                            ? new ArrayList<>()
                                            : cloudMembers,
                                    normalizedQuery
                            ));
                        }

                        @Override
                        public void onError(@NonNull Exception error) {
                            if (binding != null
                                    && generation == memberLoadGeneration) {
                                renderMembers(localMembers);
                            }
                        }
                    }
            );
        });
    }

    private void renderMembers(@NonNull List<FamilyMember> members) {
        memberAdapter.submitList(members);
        boolean isEmpty = members.isEmpty();
        binding.memberRecyclerView.setVisibility(
                isEmpty ? View.GONE : View.VISIBLE
        );
        binding.familyEmptyState.setVisibility(
                isEmpty ? View.VISIBLE : View.GONE
        );
    }

    @NonNull
    private List<FamilyMember> mergeMembers(
            @NonNull List<FamilyMember> localMembers,
            @NonNull List<FamilyAccountRepository.Member> cloudMembers,
            @NonNull String normalizedQuery
    ) {
        List<FamilyMember> merged = new ArrayList<>(localMembers);
        Set<String> localEmails = new HashSet<>();
        for (FamilyMember local : localMembers) {
            if (!local.email.trim().isEmpty()) {
                localEmails.add(local.email.trim().toLowerCase(Locale.ROOT));
            }
        }

        for (FamilyAccountRepository.Member cloud : cloudMembers) {
            String cloudEmail = cloud.email.trim().toLowerCase(Locale.ROOT);
            String searchable = (cloud.displayName + " "
                    + cloud.email + " " + cloud.role)
                    .toLowerCase(Locale.ROOT);
            if (!normalizedQuery.isEmpty()
                    && !searchable.contains(normalizedQuery)) {
                continue;
            }
            if (!cloudEmail.isEmpty() && localEmails.contains(cloudEmail)) {
                continue;
            }

            FamilyMember account = new FamilyMember();
            account.id = -1L
                    - Integer.toUnsignedLong(cloud.uid.hashCode());
            account.cloudManaged = true;
            account.cloudUid = cloud.uid;
            account.name = cloud.displayName.trim().isEmpty()
                    ? getString(R.string.family_account_member_fallback)
                    : cloud.displayName;
            account.email = cloud.email;
            account.relation = cloudRoleLabel(cloud.role);
            account.familyRole = cloudFamilyRole(cloud.role);
            account.isGuardian = FamilyRoles.OWNER_ADMIN.equals(cloud.role)
                    || FamilyRoles.GUARDIAN.equals(cloud.role);
            merged.add(account);
        }
        return merged;
    }

    @NonNull
    private String cloudRoleLabel(@NonNull String role) {
        if (FamilyRoles.OWNER_ADMIN.equals(role)) {
            return getString(R.string.family_role_owner);
        }
        if (FamilyRoles.GUARDIAN.equals(role)) {
            return getString(R.string.family_role_guardian);
        }
        if (FamilyRoles.CHILD.equals(role)) {
            return getString(R.string.family_role_child);
        }
        if (FamilyRoles.SENIOR_CITIZEN.equals(role)) {
            return getString(R.string.family_role_senior);
        }
        if (FamilyRoles.GUEST.equals(role)) {
            return getString(R.string.family_role_guest);
        }
        return getString(R.string.family_role_adult);
    }

    @NonNull
    private String cloudFamilyRole(@NonNull String role) {
        if (FamilyRoles.GUARDIAN.equals(role)
                || FamilyRoles.OWNER_ADMIN.equals(role)) {
            return FamilyMember.ROLE_GUARDIAN;
        }
        if (FamilyRoles.CHILD.equals(role)) {
            return FamilyMember.ROLE_CHILD;
        }
        return FamilyMember.ROLE_ADULT;
    }

    private void showMemberEditor(@Nullable FamilyMember existingMember) {
        DialogMemberEditorBinding dialogBinding = DialogMemberEditorBinding.inflate(getLayoutInflater());
        activeEditor = dialogBinding;
        boolean isCloudAccount = existingMember != null
                && existingMember.cloudManaged;
        boolean isNewMember = existingMember == null || isCloudAccount;
        selectedPhotoUri = existingMember == null
                ? ""
                : existingMember.profilePhotoUri;
        dialogBinding.editorTitle.setText(existingMember == null
                ? R.string.add_family_member
                : R.string.edit_family_member);

        String[] genderLabels =
                getResources().getStringArray(R.array.member_gender_labels);
        String[] bloodLabels =
                getResources().getStringArray(R.array.member_blood_labels);
        String[] roleLabels =
                getResources().getStringArray(R.array.member_role_labels);
        String[] relationLabels =
                getResources().getStringArray(R.array.family_relation_labels);
        dialogBinding.memberGenderInput.setAdapter(dropdown(genderLabels));
        dialogBinding.memberBloodGroupInput.setAdapter(dropdown(bloodLabels));
        dialogBinding.memberRoleInput.setAdapter(dropdown(roleLabels));
        dialogBinding.memberRelationInput.setAdapter(dropdown(relationLabels));

        if (existingMember != null) {
            dialogBinding.memberNameInput.setText(existingMember.name);
            dialogBinding.memberRelationInput.setText(existingMember.relation, false);
            dialogBinding.memberPhoneInput.setText(existingMember.phone);
            dialogBinding.memberEmailInput.setText(existingMember.email);
            dialogBinding.memberBirthDateInput.setText(existingMember.dateOfBirth);
            dialogBinding.memberNoteInput.setText(existingMember.note);
            dialogBinding.memberGenderInput.setText(
                    existingMember.gender, false
            );
            dialogBinding.memberBloodGroupInput.setText(
                    existingMember.bloodGroup, false
            );
            dialogBinding.memberAddressInput.setText(existingMember.address);
            dialogBinding.memberEmergencyNameInput.setText(
                    existingMember.emergencyContactName
            );
            dialogBinding.memberEmergencyPhoneInput.setText(
                    existingMember.emergencyContactPhone
            );
            dialogBinding.memberRoleInput.setText(
                    roleLabels[indexOf(FAMILY_ROLES, existingMember.familyRole)],
                    false
            );
            dialogBinding.memberGuardianSwitch.setChecked(
                    existingMember.isGuardian
            );
        } else {
            dialogBinding.memberGenderInput.setText(genderLabels[0], false);
            dialogBinding.memberBloodGroupInput.setText(bloodLabels[0], false);
            dialogBinding.memberRoleInput.setText(roleLabels[1], false);
        }
        showPhoto(dialogBinding, selectedPhotoUri);

        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();
        dialog.setOnDismissListener(ignored -> {
            if (activeEditor == dialogBinding) {
                activeEditor = null;
                selectedPhotoUri = "";
            }
        });

        dialogBinding.selectMemberPhotoButton.setOnClickListener(
                v -> photoPicker.launch(new String[]{"image/*"})
        );
        dialogBinding.removeMemberPhotoButton.setOnClickListener(v -> {
            selectedPhotoUri = "";
            showPhoto(dialogBinding, "");
        });
        dialogBinding.memberRoleInput.setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position == 0) {
                        dialogBinding.memberGuardianSwitch.setChecked(true);
                    }
                }
        );
        dialogBinding.memberBirthDateInput.setOnClickListener(v -> showDatePicker(
                dialogBinding.memberBirthDateInput
        ));
        dialogBinding.memberBirthDateLayout.setEndIconOnClickListener(v -> showDatePicker(
                dialogBinding.memberBirthDateInput
        ));
        dialogBinding.cancelMemberButton.setOnClickListener(v -> dialog.dismiss());
        dialogBinding.saveMemberButton.setOnClickListener(v -> {
            if (!validateEditor(dialogBinding)) {
                return;
            }

            FamilyMember member = isNewMember ? new FamilyMember() : existingMember;
            String phone = textOf(dialogBinding.memberPhoneInput);
            String email = textOf(dialogBinding.memberEmailInput);
            repository.checkUniqueContact(member.id, phone, email,
                    (phoneAvailable, emailAvailable) -> {
                        if (!phoneAvailable) {
                            dialogBinding.memberPhoneLayout.setError(
                                    getString(R.string.member_phone_duplicate)
                            );
                        }
                        if (!emailAvailable) {
                            dialogBinding.memberEmailLayout.setError(
                                    getString(R.string.member_email_duplicate)
                            );
                        }
                        if (!phoneAvailable || !emailAvailable) {
                            return;
                        }

                        member.name = textOf(dialogBinding.memberNameInput);
                        member.relation = textOf(
                                dialogBinding.memberRelationInput
                        );
                        member.phone = phone;
                        member.email = email;
                        member.dateOfBirth = textOf(
                                dialogBinding.memberBirthDateInput
                        );
                        member.note = textOf(dialogBinding.memberNoteInput);
                        member.profilePhotoUri = selectedPhotoUri;
                        member.gender = textOf(
                                dialogBinding.memberGenderInput
                        );
                        member.bloodGroup = textOf(
                                dialogBinding.memberBloodGroupInput
                        );
                        member.address = textOf(
                                dialogBinding.memberAddressInput
                        );
                        member.emergencyContactName = textOf(
                                dialogBinding.memberEmergencyNameInput
                        );
                        member.emergencyContactPhone = textOf(
                                dialogBinding.memberEmergencyPhoneInput
                        );
                        int roleIndex = indexOf(
                                roleLabels,
                                textOf(dialogBinding.memberRoleInput)
                        );
                        member.familyRole = FAMILY_ROLES[roleIndex];
                        member.isGuardian =
                                dialogBinding.memberGuardianSwitch.isChecked()
                                        || FamilyMember.ROLE_GUARDIAN.equals(
                                        member.familyRole
                                );

                        repository.save(member, () -> {
                            if (binding == null) {
                                return;
                            }
                            dialog.dismiss();
                            loadMembers(binding.memberSearchInput
                                    .getText().toString());
                            Snackbar.make(
                                    binding.getRoot(),
                                    isNewMember
                                            ? R.string.member_added
                                            : R.string.member_updated,
                                    Snackbar.LENGTH_SHORT
                            ).show();
                        });
                    });
        });
        dialog.show();
    }

    private ArrayAdapter<String> dropdown(@NonNull String[] values) {
        return new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                values
        );
    }

    private int indexOf(@NonNull String[] values, @NonNull String selected) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(selected)) {
                return index;
            }
        }
        return 0;
    }

    private void showPhoto(
            @NonNull DialogMemberEditorBinding editor,
            @NonNull String uriValue
    ) {
        if (uriValue.isEmpty()) {
            int padding = getResources().getDimensionPixelSize(
                    R.dimen.space_16
            );
            editor.memberPhotoPreview.setPadding(
                    padding, padding, padding, padding
            );
            editor.memberPhotoPreview.setImageResource(R.drawable.ic_family);
            editor.removeMemberPhotoButton.setVisibility(View.GONE);
            return;
        }
        try {
            editor.memberPhotoPreview.setPadding(0, 0, 0, 0);
            editor.memberPhotoPreview.setImageURI(Uri.parse(uriValue));
            editor.removeMemberPhotoButton.setVisibility(View.VISIBLE);
        } catch (RuntimeException ignored) {
            int padding = getResources().getDimensionPixelSize(
                    R.dimen.space_16
            );
            editor.memberPhotoPreview.setPadding(
                    padding, padding, padding, padding
            );
            editor.memberPhotoPreview.setImageResource(R.drawable.ic_family);
            editor.removeMemberPhotoButton.setVisibility(View.GONE);
        }
    }

    @NonNull
    private String textOf(@NonNull EditText input) {
        return input.getText() == null
                ? "" : input.getText().toString().trim();
    }

    private boolean validateEditor(DialogMemberEditorBinding editor) {
        String name = editor.memberNameInput.getText().toString().trim();
        String relation = editor.memberRelationInput.getText().toString().trim();
        String phone = editor.memberPhoneInput.getText().toString().trim();
        String email = editor.memberEmailInput.getText().toString().trim();
        String birthDate = editor.memberBirthDateInput.getText().toString().trim();
        boolean valid = true;

        valid &= requireText(editor.memberNameLayout, name, R.string.member_name_required);
        valid &= requireText(editor.memberRelationLayout, relation, R.string.member_relation_required);

        if (!phone.isEmpty() && (phone.length() < 7 || !Patterns.PHONE.matcher(phone).matches())) {
            editor.memberPhoneLayout.setError(getString(R.string.member_phone_invalid));
            valid = false;
        } else {
            editor.memberPhoneLayout.setError(null);
        }
        if (!email.isEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            editor.memberEmailLayout.setError(getString(R.string.member_email_invalid));
            valid = false;
        } else {
            editor.memberEmailLayout.setError(null);
        }
        if (!birthDate.isEmpty() && !isValidIsoDate(birthDate)) {
            editor.memberBirthDateLayout.setError(getString(R.string.member_birth_date_invalid));
            valid = false;
        } else {
            editor.memberBirthDateLayout.setError(null);
        }
        return valid;
    }

    private boolean requireText(TextInputLayout layout, String value, int errorMessage) {
        if (TextUtils.isEmpty(value)) {
            layout.setError(getString(errorMessage));
            return false;
        }
        layout.setError(null);
        return true;
    }

    private void showDatePicker(EditText dateInput) {
        Calendar calendar = Calendar.getInstance();
        String value = dateInput.getText().toString().trim();
        if (isValidIsoDate(value)) {
            try {
                Date parsedDate = new SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).parse(value);
                if (parsedDate != null) {
                    calendar.setTime(parsedDate);
                }
            } catch (ParseException ignored) {
                // The validator ensures this does not affect the user flow.
            }
        }

        DatePickerDialog datePicker = new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> dateInput.setText(String.format(
                        Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth
                )),
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        datePicker.getDatePicker().setMaxDate(System.currentTimeMillis());
        datePicker.show();
    }

    private boolean isValidIsoDate(String value) {
        if (value.isEmpty()) {
            return false;
        }
        SimpleDateFormat formatter = new SimpleDateFormat(ISO_DATE_PATTERN, Locale.US);
        formatter.setLenient(false);
        try {
            formatter.parse(value);
            return true;
        } catch (ParseException exception) {
            return false;
        }
    }

    private void confirmDelete(FamilyMember member) {
        if (member.cloudManaged) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.member_account_delete_title)
                    .setMessage(R.string.member_account_delete_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(
                            R.string.member_open_family_settings,
                            (dialog, which) -> startActivity(
                                    new Intent(
                                            requireContext(),
                                            FamilyManagementActivity.class
                                    )
                            )
                    )
                    .show();
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_member_title)
                .setMessage(getString(R.string.delete_member_message, member.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_delete, (dialog, which) -> repository.delete(member, () -> {
                    if (binding == null) {
                        return;
                    }
                    loadMembers(binding.memberSearchInput.getText().toString());
                    Snackbar.make(binding.getRoot(), R.string.member_deleted, Snackbar.LENGTH_SHORT).show();
                }))
                .show();
    }

    @Override
    public void onDestroyView() {
        activeEditor = null;
        selectedPhotoUri = "";
        binding.memberRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
