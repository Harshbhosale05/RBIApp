package com.example.cbdc.util;

import android.util.Base64;

public class Base64Util {
    
    public static String encode(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE);
    }
    
    public static byte[] decode(String encoded) {
        return Base64.decode(encoded, Base64.NO_WRAP | Base64.URL_SAFE);
    }
    
    public static String encodeStandard(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }
    
    public static byte[] decodeStandard(String encoded) {
        return Base64.decode(encoded, Base64.NO_WRAP);
    }
}


