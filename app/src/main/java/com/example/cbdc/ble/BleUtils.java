package com.example.cbdc.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.util.Log;

import java.util.UUID;

public class BleUtils {
    private static final String TAG = "BleUtils";
    
    // Custom service UUID for CBDC payments
    public static final UUID CBDC_SERVICE_UUID = UUID.fromString("0000cbd1-0000-1000-8000-00805f9b34fb");
    public static final UUID CBDC_CHARACTERISTIC_UUID = UUID.fromString("0000cbd2-0000-1000-8000-00805f9b34fb");
    public static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    
    /**
     * Check if BLE is supported
     */
    public static boolean isBleSupported(Context context) {
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return false;
        }
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }
    
    /**
     * Get Bluetooth adapter
     */
    public static BluetoothAdapter getBluetoothAdapter(Context context) {
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return null;
        }
        return bluetoothManager.getAdapter();
    }
    
    /**
     * Create GATT service for CBDC
     */
    public static BluetoothGattService createCbdcService() {
        BluetoothGattService service = new BluetoothGattService(
            CBDC_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        );
        
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
            CBDC_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ |
            BluetoothGattCharacteristic.PROPERTY_WRITE |
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ |
            BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
            CLIENT_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        );
        
        characteristic.addDescriptor(descriptor);
        service.addCharacteristic(characteristic);
        
        return service;
    }
}

