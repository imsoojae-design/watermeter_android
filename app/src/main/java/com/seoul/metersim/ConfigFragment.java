package com.seoul.metersim;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class ConfigFragment extends Fragment {

    private EditText etMeterNo, etReading, etAddr;
    private Spinner  spinDiam, spinDec;
    private CheckBox chkQ3, chkRev, chkLeak, chkBatt;
    private Button   btnConnect, btnApply, btnAlarmOn, btnAlarmOff;

    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup vg, Bundle b) {
        View v = inf.inflate(R.layout.fragment_config, vg, false);

        etMeterNo = v.findViewById(R.id.etMeterNo);
        etReading = v.findViewById(R.id.etReading);
        etAddr    = v.findViewById(R.id.etAddr);
        spinDiam  = v.findViewById(R.id.spinDiam);
        spinDec   = v.findViewById(R.id.spinDec);
        chkQ3     = v.findViewById(R.id.chkQ3);
        chkRev    = v.findViewById(R.id.chkRev);
        chkLeak   = v.findViewById(R.id.chkLeak);
        chkBatt   = v.findViewById(R.id.chkBatt);
        btnConnect  = v.findViewById(R.id.btnConnect);
        btnApply    = v.findViewById(R.id.btnApply);
        btnAlarmOn  = v.findViewById(R.id.btnAlarmOn);
        btnAlarmOff = v.findViewById(R.id.btnAlarmOff);

        // 구경 스피너
        String[] diams = new String[MeterProtocol.DIAM_LIST.length];
        for (int i = 0; i < diams.length; i++) diams[i] = MeterProtocol.DIAM_LIST[i] + " mm";
        spinDiam.setAdapter(new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item, diams));

        // 소수점 스피너
        spinDec.setAdapter(new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item, new String[]{"1","2","3","4"}));
        spinDec.setSelection(2); // 기본 3

        btnConnect.setOnClickListener(x -> {
            if (MainActivity.instance == null) return;
            if (MainActivity.instance.isConnected()) MainActivity.instance.disconnectUsb();
            else MainActivity.instance.connectUsb();
        });

        btnApply.setOnClickListener(x -> applyAndPreview());

        btnAlarmOff.setOnClickListener(x -> {
            chkQ3.setChecked(false); chkRev.setChecked(false);
            chkLeak.setChecked(false); chkBatt.setChecked(false);
            applyAndPreview();
        });

        btnAlarmOn.setOnClickListener(x -> {
            chkQ3.setChecked(true); chkRev.setChecked(true);
            chkLeak.setChecked(true); chkBatt.setChecked(true);
            applyAndPreview();
        });

        return v;
    }

    private void applyAndPreview() {
        if (MainActivity.instance == null) return;
        SimConfig cfg = MainActivity.instance.config;

        // 기물번호
        String mn = etMeterNo.getText().toString().trim().replace("-","").replace(" ","");
        if (mn.length() == 8 && mn.matches("\\d+"))
            cfg.meterNo = mn.substring(0,2) + "-" + mn.substring(2);

        // 검침값
        try { cfg.reading = Double.parseDouble(etReading.getText().toString()); } catch (Exception ignored) {}

        // 구경
        int diIdx = spinDiam.getSelectedItemPosition();
        if (diIdx >= 0) cfg.diameter = MeterProtocol.DIAM_LIST[diIdx];

        // 소수점
        cfg.decimal = spinDec.getSelectedItemPosition() + 1;

        // 주소
        try { cfg.addr = Integer.parseInt(etAddr.getText().toString()); } catch (Exception ignored) {}
        if (cfg.addr < 1) cfg.addr = 1;

        // 경보
        cfg.q3   = chkQ3.isChecked();
        cfg.rev  = chkRev.isChecked();
        cfg.leak = chkLeak.isChecked();
        cfg.batt = chkBatt.isChecked();

        MainActivity.instance.updatePreview();
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("설정 적용: " + cfg.meterNo +
                " " + String.format("%." + cfg.decimal + "f", cfg.reading) + "㎥ " +
                cfg.diameter + "mm", "INFO");
    }

    public void onConnected(boolean on) {
        if (btnConnect == null) return;
        if (on) {
            btnConnect.setText("연결 끊기");
            btnConnect.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.red));
        } else {
            btnConnect.setText("USB 시리얼 연결");
            btnConnect.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.accent2));
        }
    }
}
