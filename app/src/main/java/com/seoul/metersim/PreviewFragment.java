package com.seoul.metersim;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

public class PreviewFragment extends Fragment {

    private TextView tvReqHex, tvRepHex, tvParse;

    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup vg, Bundle b) {
        View v = inf.inflate(R.layout.fragment_preview, vg, false);
        tvReqHex = v.findViewById(R.id.tvReqHex);
        tvRepHex = v.findViewById(R.id.tvRepHex);
        tvParse  = v.findViewById(R.id.tvParse);

        // 초기 프리뷰
        if (MainActivity.instance != null)
            MainActivity.instance.updatePreview();
        return v;
    }

    public void update(String reqHex, String repHex, String parseResult) {
        if (tvReqHex == null) return;
        tvReqHex.setText(reqHex);
        tvRepHex.setText(repHex);
        tvParse.setText(parseResult);
    }
}
