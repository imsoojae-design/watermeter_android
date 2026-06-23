package com.seoul.metersim;

import java.util.Locale;

/**
 * 서울특별시 디지털계량기 프로토콜 V1.2
 * 계량기(Slave) 역할: REQ 수신 → REP 생성
 */
public class MeterProtocol {

    public static final int SHORT_START  = 0x10;
    public static final int LONG_START   = 0x68;
    public static final int STOP_BYTE    = 0x16;
    public static final int C_REQ_UD2_A  = 0x5B;
    public static final int C_REQ_UD2_B  = 0x7B;
    public static final int C_REP_UD     = 0x08;
    public static final int CI_FIELD     = 0x78;
    public static final int MDH_FIELD    = 0x0F;
    public static final int BAUD_RATE    = 1200;

    public static final int[] DIAM_LIST  = {15,20,25,32,40,50,80,100,150,200,250,300};
    public static final int[] DIAM_CODE  = { 1, 2, 3, 4, 5, 6, 7,  8,  9, 10, 11, 12};

    // ── BCD 인코딩 ────────────────────────────────────────
    /** 기물번호 → 4바이트 Little-Endian BCD */
    public static byte[] encodeMeterNo(String meterNo) {
        String s = meterNo.replace("-","").replace(" ","");
        while (s.length() < 8) s = "0" + s;
        s = s.substring(0, 8);
        byte[] pairs = new byte[4];
        for (int i = 0; i < 4; i++)
            pairs[i] = (byte)(((s.charAt(i*2)-'0')<<4)|(s.charAt(i*2+1)-'0'));
        // Little-Endian: reverse
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) result[i] = pairs[3-i];
        return result;
    }

    /** 검침값 → 4바이트 Little-Endian BCD */
    public static byte[] encodeReading(double value, int decimals) {
        long iv = Math.round(value * Math.pow(10, decimals));
        String s = String.format("%08d", iv % 100000000L);
        byte[] pairs = new byte[4];
        for (int i = 0; i < 4; i++)
            pairs[i] = (byte)(((s.charAt(i*2)-'0')<<4)|(s.charAt(i*2+1)-'0'));
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) result[i] = pairs[3-i];
        return result;
    }

    /** DIF: 구경코드(상위4비트) + 0xC(8자리BCD) */
    public static int buildDif(int diameter) {
        int dc = 1;
        for (int i = 0; i < DIAM_LIST.length; i++)
            if (DIAM_LIST[i] == diameter) { dc = DIAM_CODE[i]; break; }
        return (dc << 4) | 0x0C;
    }

    /** VIF: 0x10 + 소수점자리 */
    public static int buildVif(int decimals) {
        return 0x10 | (decimals & 0x0F);
    }

    // ── Long Frame 빌드 ───────────────────────────────────
    public static byte[] buildLongFrame(SimConfig cfg) {
        byte[] idBcd  = encodeMeterNo(cfg.meterNo);
        byte[] rdBcd  = encodeReading(cfg.reading, cfg.decimal);
        int status = (cfg.q3?0x80:0)|(cfg.rev?0x40:0)|(cfg.leak?0x20:0)|(cfg.batt?0x04:0);
        int dif    = buildDif(cfg.diameter);
        int vif    = buildVif(cfg.decimal);

        byte[] ud = new byte[11];
        int p = 0;
        ud[p++] = MDH_FIELD;
        System.arraycopy(idBcd, 0, ud, p, 4); p += 4;
        ud[p++] = (byte)status;
        ud[p++] = (byte)dif;
        ud[p++] = (byte)vif;
        System.arraycopy(rdBcd, 0, ud, p, 4); p += 4;

        int l  = 3 + p;
        int ck = C_REP_UD + (cfg.addr & 0xFF) + CI_FIELD;
        for (byte b : ud) ck += (b & 0xFF);
        ck &= 0xFF;

        byte[] frame = new byte[7 + p + 2];
        int fp = 0;
        frame[fp++] = (byte)LONG_START; frame[fp++] = (byte)l; frame[fp++] = (byte)l;
        frame[fp++] = (byte)LONG_START; frame[fp++] = (byte)C_REP_UD;
        frame[fp++] = (byte)(cfg.addr & 0xFF); frame[fp++] = (byte)CI_FIELD;
        System.arraycopy(ud, 0, frame, fp, p); fp += p;
        frame[fp++] = (byte)ck; frame[fp++] = (byte)STOP_BYTE;
        return frame;
    }

    // ── REQ 감지 ─────────────────────────────────────────
    public static int detectRequest(byte[] buf, int len) {
        if (len < 5) return -1;
        for (int i = 0; i <= len - 5; i++) {
            if ((buf[i] & 0xFF) != SHORT_START) continue;
            if ((buf[i+4] & 0xFF) != STOP_BYTE)  continue;
            int c = buf[i+1] & 0xFF;
            int a = buf[i+2] & 0xFF;
            int k = buf[i+3] & 0xFF;
            if ((c+a) & 0xFF != k) continue;
            if (c != C_REQ_UD2_A && c != C_REQ_UD2_B) continue;
            return i; // 시작 위치 반환
        }
        return -1;
    }

    public static int getRequestAddr(byte[] buf, int pos) {
        return buf[pos+2] & 0xFF;
    }

    // ── HEX 변환 ─────────────────────────────────────────
    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    // ── 파싱 결과 문자열 ──────────────────────────────────
    public static String parseToString(SimConfig cfg) {
        String alarm = "정상";
        if (cfg.q3 || cfg.rev || cfg.leak || cfg.batt) {
            StringBuilder sb = new StringBuilder();
            if (cfg.q3)   sb.append("Q3초과 ");
            if (cfg.rev)  sb.append("역류 ");
            if (cfg.leak) sb.append("누수 ");
            if (cfg.batt) sb.append("배터리 ");
            alarm = sb.toString().trim();
        }
        return String.format(Locale.getDefault(),
            "기물번호  %s\n검침값    %." + cfg.decimal + "f ㎥\n구경      %d mm\n상태      %s",
            cfg.meterNo, cfg.reading, cfg.diameter, alarm);
    }
}
