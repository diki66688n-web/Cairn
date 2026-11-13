package com.cairn.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 密钥存储。
 *
 * 设计取舍（如实说明限制）：
 *   - 密钥必须长期保存在设备上，否则每次开机都要手输，不成产品。
 *   - 首选 Android Keystore：私钥不出 TEE，落在 SharedPreferences 的只是密文。
 *   - Keystore 不可用时退化为明文存 SharedPreferences（仅在 app 私有目录，
 *     DAC 0700 保护，跨 uid 不可读）。
 *   - 无论哪种，都无法防御「管理器自身进程被攻破」—— 这是所有 root 管理器的
 *     共同边界，不是本方案特有缺陷。
 */
public final class KeyStoreBox {

    private static final String PREF = "cairn";
    private static final String KEY_CT = "key_ct";      // 密文 base64
    private static final String KEY_IV = "key_iv";      // IV base64
    private static final String KEY_PLAIN = "key_plain";// 降级明文
    private static final String KEY_MODE = "key_mode";
    private static final String ALIAS = "cairn_master";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences sp;
    private final StringBuilder log = new StringBuilder();
    private String mode = "none";

    public KeyStoreBox(Context ctx) {
        this.sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String logText() { return log.toString(); }
    public String mode() { return mode; }
    public boolean hasKey() {
        return sp.contains(KEY_CT) || sp.contains(KEY_PLAIN);
    }

    public void clear() {
        sp.edit().remove(KEY_CT).remove(KEY_IV)
          .remove(KEY_PLAIN).remove(KEY_MODE).apply();
        mode = "none";
        log.append("key cleared\n");
    }

    /** 保存 16 字节密钥（hex 字符串） */
    public boolean save(String hex16) {
        try {
            byte[] raw = hexToBytes(hex16);
            if (raw.length != 16) {
                log.append("save rejected: need 16 bytes, got ")
                   .append(raw.length).append('\n');
                return false;
            }
            if (tryKeystoreSave(raw)) { mode = "keystore"; return true; }
            // 降级
            sp.edit().putString(KEY_PLAIN, hex16).putString(KEY_MODE, "plain")
              .apply();
            mode = "plain";
            log.append("WARN keystore unavailable, fell back to plain\n");
            return true;
        } catch (Exception e) {
            log.append("save FAILED: ").append(e).append('\n');
            return false;
        }
    }

    /** 读回密钥 hex；失败返回 null */
    public String load() {
        try {
            if (sp.contains(KEY_CT)) {
                byte[] ct = Base64.decode(sp.getString(KEY_CT, ""), Base64.NO_WRAP);
                byte[] iv = Base64.decode(sp.getString(KEY_IV, ""), Base64.NO_WRAP);
                byte[] raw = keystoreDecrypt(ct, iv);
                if (raw != null) { mode = "keystore"; return bytesToHex(raw); }
                log.append("keystore decrypt failed\n");
            }
            String p = sp.getString(KEY_PLAIN, null);
            if (p != null) { mode = "plain"; return p; }
        } catch (Exception e) {
            log.append("load FAILED: ").append(e).append('\n');
        }
        mode = "none";
        return null;
    }

    // ---------- Keystore ----------

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private boolean tryKeystoreSave(byte[] raw) {
        try {
            SecretKey k = getOrCreateKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k);
            byte[] ct = c.doFinal(raw);
            byte[] iv = c.getIV();
            sp.edit()
              .putString(KEY_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
              .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
              .putString(KEY_MODE, "keystore")
              .remove(KEY_PLAIN)
              .apply();
            log.append("saved via Android Keystore (AES-GCM-256)\n");
            return true;
        } catch (Throwable t) {
            log.append("keystore path failed: ").append(t).append('\n');
            return false;
        }
    }

    private byte[] keystoreDecrypt(byte[] ct, byte[] iv) {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            SecretKey k = (SecretKey) ks.getKey(ALIAS, null);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
            return c.doFinal(ct);
        } catch (Throwable t) {
            log.append("decrypt err: ").append(t).append('\n');
            return null;
        }
    }

    // ---------- hex ----------

    public static byte[] hexToBytes(String s) {
        s = s.replaceAll("\\s", "").toUpperCase();
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("odd len");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }

    public static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    /** 16 字节小端 -> long k0/k1，与内核比对方式一致 */
    public static long[] keyToLongs(byte[] raw) {
        long k0 = 0, k1 = 0;
        for (int i = 7; i >= 0; i--) k0 = (k0 << 8) | (raw[i] & 0xffL);
        for (int i = 15; i >= 8; i--) k1 = (k1 << 8) | (raw[i] & 0xffL);
        return new long[]{k0, k1};
    }

    public static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}