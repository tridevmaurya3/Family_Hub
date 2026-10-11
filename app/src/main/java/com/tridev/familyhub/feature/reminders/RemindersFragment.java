package com.tridev.familyhub.feature.reminders;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.tridev.familyhub.feature.main.MainActivity;

/** Main and floating reminders share the same existing save/sync/scheduling paths. */
public class RemindersFragment extends Fragment implements com.tridev.familyhub.feature.main.AddActionHost {
    private ReminderWorkspace workspace;
    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        workspace = new ReminderWorkspace(requireContext(), false, () -> ((MainActivity) requireActivity()).showFeatureMenu());
        return workspace.getView();
    }
    @Override public void onAddRequested() { if (workspace != null) workspace.add(); }
    @Override public void onResume() { super.onResume(); if (workspace != null) workspace.reload(); }
    @Override public void onDestroyView() { if (workspace != null) workspace.dispose(); workspace = null; super.onDestroyView(); }
}
