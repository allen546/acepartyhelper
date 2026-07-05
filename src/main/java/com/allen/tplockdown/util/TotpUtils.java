package com.allen.tplockdown.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

public class TotpUtils {
    public static boolean verify(String secretBase32, String codeStr) {
        if (secretBase32 == null || secretBase32.isEmpty()) {
            return false;
        }
        if (codeStr == null || !codeStr.matches("^\\d{6}$")) {
            return false;
        }
        try {
            long code = Long.parseLong(codeStr);
            byte[] key = decodeBase32(secretBase32);
            long timeWindow = System.currentTimeMillis() / 1000L / 30L;
            
            for (int i = -1; i <= 1; i++) {
                if (calculateCode(key, timeWindow + i) == code) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Ignore other exceptions
        }
        return false;
    }

    private static long calculateCode(byte[] key, long time) throws GeneralSecurityException {
        byte[] data = new byte[8];
        long value = time;
        for (int i = 8; i-- > 0; value >>>= 8) {
            data[i] = (byte) value;
        }
        
        SecretKeySpec signKey = new SecretKeySpec(key, "HmacSHA1");
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(signKey);
        byte[] hash = mac.doFinal(data);
        
        int offset = hash[hash.length - 1] & 0xF;
        long truncatedHash = 0;
        for (int i = 0; i < 4; ++i) {
            truncatedHash <<= 8;
            truncatedHash |= (hash[offset + i] & 0xFF);
        }
        
        truncatedHash &= 0x7FFFFFFF;
        truncatedHash %= 1000000;
        
        return truncatedHash;
    }

    private static byte[] decodeBase32(String base32) {
        String allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        base32 = base32.toUpperCase().replaceAll("[^" + allowedChars + "]", "");
        int len = base32.length();
        int outLen = len * 5 / 8;
        byte[] out = new byte[outLen];
        int buffer = 0;
        int bitsLeft = 0;
        int count = 0;
        for (int i = 0; i < len; i++) {
            int val = allowedChars.indexOf(base32.charAt(i));
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[count++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return out;
    }
}
