package com.seoul.metersim;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.seoul.metersim.USB_PERMISSION";
    private static final String[] TAB_TITLES = {"설정", "프레임", "로그"};

    public static MainActivity instance;
    public final SimConfig config = new SimConfig();

    private UsbManager       usbManager;
    private UsbSerialPort    serialPort;
    private TextView         tvConnStatus;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isConnected = false;
    private volatile boolean isRunning   = false;

    public int rxCount = 0, txCount = 0;

    private ConfigFragment  configFragment;
    private PreviewFragment previewFragment;
    private LogFragment     logFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance     = this;
        usbManager   = (UsbManager) getSystemService(Context.USB_SERVICE);
        tvConnStatus = findViewById(R.id.tvConnStatus);

        setupViewPager();

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    }

    private void setupViewPager() {
        ViewPager2 vp = findViewById(R.id.viewPager);
        TabLayout  tl = findViewById(R.id.tabLayout);
        vp.setAdapter(new FragmentStateAdapter(this) {
            public int getItemCount() { return 3; }
            public Fragment createFragment(int pos) {
                switch (pos) {
                    case 0: configFragment  = new ConfigFragment();  return configFragment;
                    case 1: previewFragment = new PreviewFragment(); return previewFragment;
                    default: logFragment   = new LogFragment();      return logFragment;
                }
            }
        });
        new TabLayoutMediator(tl, vp, (tab, pos) -> tab.setText(TAB_TITLES[pos])).attach();
    }

    public void connectUsb() {
        addLog("=== USB 연결 시도 ===", "INFO");
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) { addLog("USB 장치 없음", "ERR"); return; }
        UsbDevice dev = drivers.get(0).getDevice();
        addLog("USB: " + dev.getProductName() + " VID=" + dev.getVendorId(), "INFO");
        if (!usbManager.hasPermission(dev)) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(dev, pi);
            addLog("권한 요청 중...", "WARN"); return;
        }
        openPort(drivers.get(0));
    }

    private void openPort(UsbSerialDriver driver) {
        executor.execute(() -> {
            try {
                UsbDeviceConnection conn = usbManager.openDevice(driver.getDevice());
                if (conn == null) { addLog("openDevice 실패", "ERR"); return; }
                UsbSerialPort port = driver.getPorts().get(0);
                port.open(conn);
                port.setParameters(MeterProtocol.BAUD_RATE,
                    UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                port.setDTR(true); port.setRTS(true);
                Thread.sleep(50);
                serialPort = port; isConnected = true;
                setConnStatus("● 연결됨", R.color.green);
                mainHandler.post(() -> { if (configFragment != null) configFragment.onConnected(true); });
                addLog("연결됨: " + driver.getDevice().getProductName() + " (1200bps 8N1)", "OK");
                startSimLoop();
            } catch (IOException | InterruptedException e) {
                addLog("연결 실패: " + e.getMessage(), "ERR");
            }
        });
    }

    public void disconnectUsb() {
        isRunning = false; isConnected = false;
        UsbSerialPort p = serialPort; serialPort = null;
        if (p != null) { try { p.close(); } catch (IOException ignored) {} }
        setConnStatus("● 연결 안됨", R.color.muted);
        mainHandler.post(() -> { if (configFragment != null) configFragment.onConnected(false); });
        addLog("=== 연결 해제 ===", "WARN");
    }

    /** 시뮬레이터 메인 루프: REQ 수신 → REP 응답 */
    private void startSimLoop() {
        isRunning = true;
        byte[] buf = new byte[256];
        byte[] acc = new byte[512];
        int[]  accLen = {0};

        while (isRunning && serialPort != null) {
            try {
                int n = serialPort.read(buf, 200);
                if (n > 0) {
                    System.arraycopy(buf, 0, acc, accLen[0], n);
                    accLen[0] += n;

                    int pos = MeterProtocol.detectRequest(acc, accLen[0]);
                    if (pos >= 0) {
                        int addr = MeterProtocol.getRequestAddr(acc, pos);
                        byte[] reqBytes = new byte[5];
                        System.arraycopy(acc, pos, reqBytes, 0, 5);
                        String reqHex = MeterProtocol.toHex(reqBytes);

                        rxCount++;
                        addLog("← REQ [" + rxCount + "] addr=" + addr + " | " + reqHex, "HEX");

                        // 프로토콜: Low 50ms 후 High 35ms 후 응답
                        Thread.sleep(50);

                        byte[] rep = MeterProtocol.buildLongFrame(config);
                        serialPort.write(rep, 3000);
                        txCount++;
                        String repHex = MeterProtocol.toHex(rep);

                        addLog("→ REP [" + txCount + "] " + rep.length + "B | " + repHex, "OK");
                        addLog("   " + config.meterNo + "  " +
                            String.format("%." + config.decimal + "f", config.reading) + "㎥  " +
                            (hasAlarm() ? "경보" : "정상"), "INFO");

                        // 카운터 및 프리뷰 업데이트
                        final String rh = reqHex, ph = repHex;
                        mainHandler.post(() -> {
                            if (logFragment != null)    logFragment.updateCounters(rxCount, txCount);
                            if (previewFragment != null) previewFragment.update(rh, ph, MeterProtocol.parseToString(config));
                        });

                        // 수신 버퍼에서 처리된 부분 제거
                        accLen[0] -= (pos + 5);
                        System.arraycopy(acc, pos + 5, acc, 0, accLen[0]);
                    }
                    if (accLen[0] > 400) accLen[0] = 0;
                }
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("closed"))) {
                    mainHandler.post(this::disconnectUsb); break;
                }
            } catch (InterruptedException ignored) {}
        }
    }

    private boolean hasAlarm() {
        return config.q3 || config.rev || config.leak || config.batt;
    }

    private void setConnStatus(String text, int colorRes) {
        mainHandler.post(() -> {
            if (tvConnStatus == null) return;
            tvConnStatus.setText(text);
            tvConnStatus.setTextColor(getColor(colorRes));
        });
    }

    public void addLog(String msg, String level) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (logFragment != null) logFragment.addLog(msg, level);
        } else {
            mainHandler.post(() -> { if (logFragment != null) logFragment.addLog(msg, level); });
        }
    }

    public void updatePreview() {
        if (previewFragment == null) return;
        byte[] req = new byte[5];
        req[0] = MeterProtocol.SHORT_START; req[1] = MeterProtocol.C_REQ_UD2_A;
        req[2] = (byte)(config.addr & 0xFF);
        req[3] = (byte)((MeterProtocol.C_REQ_UD2_A + config.addr) & 0xFF);
        req[4] = MeterProtocol.STOP_BYTE;
        byte[] rep = MeterProtocol.buildLongFrame(config);
        previewFragment.update(
            MeterProtocol.toHex(req) + "  (" + req.length + "B)",
            MeterProtocol.toHex(rep) + "  (" + rep.length + "B)",
            MeterProtocol.parseToString(config)
        );
    }

    public boolean isConnected() { return isConnected; }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                addLog("USB 권한: " + (granted ? "허용" : "거부"), granted ? "OK" : "ERR");
                if (granted) {
                    List<UsbSerialDriver> drivers =
                        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                    if (!drivers.isEmpty()) openPort(drivers.get(0));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                addLog("USB 연결됨", "INFO");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (isConnected) disconnectUsb();
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy(); disconnectUsb(); executor.shutdown();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        instance = null;
    }
}
