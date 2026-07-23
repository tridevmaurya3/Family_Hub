package com.tridev.familyhub.feature.familylive;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveMemberData;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;
import com.tridev.familyhub.databinding.FragmentFamilyLiveBinding;
import com.tridev.familyhub.feature.familylive.adapter.FamilyLiveAdapter;
import com.tridev.familyhub.feature.familylive.model.FamilyLiveMemberUiModel;

import java.util.ArrayList;
import java.util.List;

/** Displays real family profiles with their latest optional live status. */
public class FamilyLiveFragment extends Fragment {

    private FragmentFamilyLiveBinding binding;
    private FamilyLiveAdapter familyLiveAdapter;
    private FamilyLiveRepository repository;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFamilyLiveBinding.inflate(
                inflater,
                container,
                false
        );
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        repository = new FamilyLiveRepository(requireContext());
        familyLiveAdapter = new FamilyLiveAdapter();

        binding.recyclerFamilyLive.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.recyclerFamilyLive.setAdapter(familyLiveAdapter);

        loadFamilyLiveMembers();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (repository != null) {
            loadFamilyLiveMembers();
        }
    }

    private void loadFamilyLiveMembers() {
        repository.loadMemberStatuses(memberStatuses -> {
            if (binding == null) {
                return;
            }

            List<FamilyLiveMemberUiModel> uiModels =
                    mapToUiModels(memberStatuses);

            familyLiveAdapter.submitList(uiModels);
            binding.tvMemberCount.setText(getString(
                    R.string.family_live_member_count,
                    uiModels.size()
            ));

            boolean isEmpty = uiModels.isEmpty();
            binding.recyclerFamilyLive.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.familyLiveEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        });
    }

    @NonNull
    private List<FamilyLiveMemberUiModel> mapToUiModels(
            @NonNull List<FamilyLiveMemberData> memberStatuses
    ) {
        List<FamilyLiveMemberUiModel> uiModels = new ArrayList<>();

        for (FamilyLiveMemberData data : memberStatuses) {
            boolean locationVisible =
                    data.isLocationSharingEnabled && data.hasLocation;

            String location = locationVisible
                    ? data.currentPlaceName
                    : getString(R.string.family_live_location_off);

            uiModels.add(new FamilyLiveMemberUiModel(
                    data.familyMemberId,
                    data.memberName,
                    location,
                    data.onlineStatus,
                    data.batteryPercentage,
                    data.isCharging,
                    data.hasInternet,
                    data.movementType,
                    data.lastUpdatedAt
            ));
        }

        return uiModels;
    }

    @Override
    public void onDestroyView() {
        binding.recyclerFamilyLive.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
