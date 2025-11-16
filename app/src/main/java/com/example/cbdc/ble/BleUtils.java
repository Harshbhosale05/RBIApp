package com.example.cbdc.ble;

import android.content.Context;
import android.util.Log;

/**
 * BLE Utilities - Simplified for Nearby Connections API
 * The Nearby API handles all BLE/WiFi Direct communication automatically
 */
public class BleUtils {
    private static final String TAG = "BleUtils";
    
    // Service ID for Nearby Connections API (replaces old GATT UUID)
    public static final String CBDC_SERVICE_ID = "com.example.cbdc.CBDC_SERVICE";
    
    /**
     * Check if BLE/Nearby is supported
     * Note: Nearby Connections API handles both BLE and WiFi Direct automatically
     */
    public static boolean isBleSupported(Context context) {
        // Nearby Connections API will handle this check internally
        // This method is kept for compatibility
        return true;
    }
}

