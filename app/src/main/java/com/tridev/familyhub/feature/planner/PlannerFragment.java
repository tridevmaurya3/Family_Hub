package com.tridev.familyhub.feature.planner;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.tridev.familyhub.feature.main.MainActivity;
public class PlannerFragment extends Fragment implements com.tridev.familyhub.feature.main.AddActionHost {
    private PlannerWorkspace workspace;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup container,@Nullable Bundle state){workspace=new PlannerWorkspace(requireContext(),false,()->((MainActivity)requireActivity()).showFeatureMenu());return workspace.getView();}
    @Override public void onAddRequested(){if(workspace!=null)workspace.add();}
    @Override public void onResume(){super.onResume();if(workspace!=null)workspace.reload();}
    @Override public void onDestroyView(){if(workspace!=null)workspace.dispose();workspace=null;super.onDestroyView();}
}
