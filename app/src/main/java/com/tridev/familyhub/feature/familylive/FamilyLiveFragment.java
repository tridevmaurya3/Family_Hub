package com.tridev.familyhub.feature.familylive;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tridev.familyhub.databinding.FragmentFamilyLiveBinding;
import com.tridev.familyhub.feature.familylive.adapter.FamilyLiveAdapter;
import com.tridev.familyhub.feature.familylive.model.FamilyLiveMemberUiModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays the Family Live member list.
 *
 * Dummy data is used temporarily. In a later step, this screen will receive
 * real member and status information through FamilyLiveViewModel and Room.
 */
public class FamilyLiveFragment extends Fragment {

    private FragmentFamilyLiveBinding binding;
    private FamilyLiveAdapter familyLiveAdapter;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFamilyLiveBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        setupRecyclerView();
        showDummyMembers();
    }

    private void setupRecyclerView() {
        familyLiveAdapter = new FamilyLiveAdapter();

        binding.recyclerFamilyLive.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );

        binding.recyclerFamilyLive.setHasFixedSize(true);
        binding.recyclerFamilyLive.setAdapter(familyLiveAdapter);
    }

    /**
     * Temporary preview data.
     *
     * These entries will be removed after the ViewModel and database mapping
     * are connected.
     */
    private void showDummyMembers() {
        long currentTime = System.currentTimeMillis();

        List<FamilyLiveMemberUiModel> members = new ArrayList<>();

        members.add(
                new FamilyLiveMemberUiModel(
                        1L,
                        "Tridev Maurya",
                        "Home",
                        "Online",
                        87,
                        false,
                        true,
                        "At home",
                        currentTime
                )
        );

        members.add(
                new FamilyLiveMemberUiModel(
                        2L,
                        "Kusum Maurya",
                        "Market",
                        "Online",
                        64,
                        false,
                        true,
                        "Travelling",
                        currentTime - 120_000L
                )
        );

        members.add(
                new FamilyLiveMemberUiModel(
                        3L,
                        "Family Member",
                        "School",
                        "Offline",
                        42,
                        false,
                        false,
                        "Stationary",
                        currentTime - 600_000L
                )
        );

        familyLiveAdapter.submitList(members);

        binding.tvMemberCount.setText(
                getString(
                        com.tridev.familyhub.R.string.family_live_member_count,
                        members.size()
                )
        );
    }

    @Override
    public void onDestroyView() {
        binding.recyclerFamilyLive.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}