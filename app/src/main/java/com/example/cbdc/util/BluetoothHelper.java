package com.example.cbdc.util;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for managing Bluetooth permissions and state
 * Handles BLE-only mode without requiring NEARBY_WIFI_DEVICES on Android 13+
 */
public class BluetoothHelper {
    private static final String TAG = "BluetoothHelper";
    public static final int REQUEST_ENABLE_BT = 1001;
    public static final int REQUEST_BT_PERMISSIONS = 1002;

    /**
     * Get all Bluetooth permissions based on Android version
     * NEARBY_WIFI_DEVICES is included but app works without it (BLE-only mode)
     */
    public static String[] getRequiredPermissions(Context context) {
        List<String> permissions = new ArrayList<>();
        
        // Camera for QR scanning
        permissions.add(Manifest.permission.CAMERA);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            // NEARBY_WIFI_DEVICES - requested but not critical
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else { // Android 11 and below
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        
        return permissions.toArray(new String[0]);
    }

    /**
     * Check if critical BLE permissions are granted (works without NEARBY_WIFI_DEVICES)
     */
    public static boolean hasAllPermissions(Context context) {
        String[] permissions = getRequiredPermissions(context);
        int missingCount = 0;
        
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                // NEARBY_WIFI_DEVICES is optional - app works in BLE-only mode without it
                if (permission.equals(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                    Log.w(TAG, "NEARBY_WIFI_DEVICES not granted - using BLE-only mode");
                    continue; // Don't count as missing
                }
                Log.d(TAG, "Missing permission: " + permission);
                missingCount++;
            }
        }
        
        return missingCount == 0;
    }

    /**
     * Request all permissions
     */
    public static void requestPermissions(Activity activity) {
        String[] permissions = getRequiredPermissions(activity);
        ActivityCompat.requestPermissions(activity, permissions, REQUEST_BT_PERMISSIONS);
    }

    /**
     * Check if Bluetooth is enabled
     */
    public static boolean isBluetoothEnabled(Context context) {
        BluetoothManager bluetoothManager = 
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return false;
        }
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /**
     * Request to enable Bluetooth
     */
    public static void requestEnableBluetooth(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
                return;
            }
        }
        
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }
}
