package com.tridev.familyhub.feature.family.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.databinding.ItemFamilyMemberBinding;

import java.util.ArrayList;
import java.util.List;

/** Simple adapter for local family profiles. */
public class FamilyMemberAdapter extends RecyclerView.Adapter<FamilyMemberAdapter.MemberViewHolder> {

    public interface MemberActionListener {
        void onEdit(FamilyMember member);

        void onDelete(FamilyMember member);
    }

    private final List<FamilyMember> members = new ArrayList<>();
    private final MemberActionListener listener;

    public FamilyMemberAdapter(MemberActionListener listener) {
        this.listener = listener;
    }

    public void submitList(List<FamilyMember> updatedMembers) {
        members.clear();
        members.addAll(updatedMembers);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public MemberViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFamilyMemberBinding binding = ItemFamilyMemberBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new MemberViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MemberViewHolder holder, int position) {
        holder.bind(members.get(position));
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    class MemberViewHolder extends RecyclerView.ViewHolder {
        private final ItemFamilyMemberBinding binding;

        MemberViewHolder(ItemFamilyMemberBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(FamilyMember member) {
            binding.memberInitials.setText(initials(member.name));
            binding.memberName.setText(member.name);
            binding.memberRelation.setText(member.relation);
            binding.memberContact.setText(contactSummary(member));
            binding.getRoot().setOnClickListener(v -> listener.onEdit(member));
            binding.editMemberButton.setOnClickListener(v -> listener.onEdit(member));
            binding.deleteMemberButton.setOnClickListener(v -> listener.onDelete(member));
        }

        private String initials(String name) {
            String[] words = name.trim().split("\\s+");
            if (words.length == 0 || words[0].isEmpty()) {
                return "?";
            }
            if (words.length == 1) {
                return words[0].substring(0, 1).toUpperCase();
            }
            return (words[0].substring(0, 1) + words[words.length - 1].substring(0, 1)).toUpperCase();
        }

        private String contactSummary(FamilyMember member) {
            if (!TextUtils.isEmpty(member.phone)) {
                return member.phone;
            }
            if (!TextUtils.isEmpty(member.email)) {
                return member.email;
            }
            return binding.getRoot().getContext().getString(R.string.member_contact_missing);
        }
    }
}
