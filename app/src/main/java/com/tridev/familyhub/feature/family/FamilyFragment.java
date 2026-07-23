package com.tridev.familyhub.feature.family;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.databinding.DialogMemberEditorBinding;
import com.tridev.familyhub.databinding.FragmentFamilyBinding;
import com.tridev.familyhub.feature.family.adapter.FamilyMemberAdapter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Complete local-only CRUD screen for family profiles. */
public class FamilyFragment extends Fragment implements com.tridev.familyhub.feature.main.AddActionHost {

    private static final String ISO_DATE_PATTERN = "yyyy-MM-dd";

    private FragmentFamilyBinding binding;
    private FamilyMemberAdapter memberAdapter;
    private FamilyMemberRepository repository;

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
        repository.loadMembers(query, members -> {
            if (binding == null) {
                return;
            }
            memberAdapter.submitList(members);
            boolean isEmpty = members.isEmpty();
            binding.memberRecyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            binding.familyEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        });
    }

    private void showMemberEditor(@Nullable FamilyMember existingMember) {
        DialogMemberEditorBinding dialogBinding = DialogMemberEditorBinding.inflate(getLayoutInflater());
        boolean isNewMember = existingMember == null;
        dialogBinding.editorTitle.setText(isNewMember
                ? R.string.add_family_member
                : R.string.edit_family_member);

        if (!isNewMember) {
            dialogBinding.memberNameInput.setText(existingMember.name);
            dialogBinding.memberRelationInput.setText(existingMember.relation);
            dialogBinding.memberPhoneInput.setText(existingMember.phone);
            dialogBinding.memberEmailInput.setText(existingMember.email);
            dialogBinding.memberBirthDateInput.setText(existingMember.dateOfBirth);
            dialogBinding.memberNoteInput.setText(existingMember.note);
        }

        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();

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
            member.name = dialogBinding.memberNameInput.getText().toString().trim();
            member.relation = dialogBinding.memberRelationInput.getText().toString().trim();
            member.phone = dialogBinding.memberPhoneInput.getText().toString().trim();
            member.email = dialogBinding.memberEmailInput.getText().toString().trim();
            member.dateOfBirth = dialogBinding.memberBirthDateInput.getText().toString().trim();
            member.note = dialogBinding.memberNoteInput.getText().toString().trim();

            repository.save(member, () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                loadMembers(binding.memberSearchInput.getText().toString());
                Snackbar.make(
                        binding.getRoot(),
                        isNewMember ? R.string.member_added : R.string.member_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });
        dialog.show();
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
        binding = null;
        super.onDestroyView();
    }
}
