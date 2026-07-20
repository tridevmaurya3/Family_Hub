package com.tridev.familyhub.feature.placeholder;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.tridev.familyhub.databinding.FragmentSectionPlaceholderBinding;

/** Temporary shell for a top-level feature before its complete offline module is implemented. */
public class SectionPlaceholderFragment extends Fragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_DESCRIPTION = "description";
    private static final String ARG_ICON = "icon";

    public static SectionPlaceholderFragment newInstance(@StringRes int title,
                                                         @StringRes int description,
                                                         @DrawableRes int icon) {
        Bundle arguments = new Bundle();
        arguments.putInt(ARG_TITLE, title);
        arguments.putInt(ARG_DESCRIPTION, description);
        arguments.putInt(ARG_ICON, icon);

        SectionPlaceholderFragment fragment = new SectionPlaceholderFragment();
        fragment.setArguments(arguments);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        FragmentSectionPlaceholderBinding binding = FragmentSectionPlaceholderBinding.inflate(
                inflater, container, false
        );
        Bundle arguments = requireArguments();
        binding.sectionTitle.setText(arguments.getInt(ARG_TITLE));
        binding.sectionDescription.setText(arguments.getInt(ARG_DESCRIPTION));
        binding.sectionIcon.setImageResource(arguments.getInt(ARG_ICON));
        return binding.getRoot();
    }
}
