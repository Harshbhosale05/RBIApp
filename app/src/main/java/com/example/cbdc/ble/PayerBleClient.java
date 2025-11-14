package com.example.cbdc.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;
import com.example.cbdc.crypto.ConsumeFlow;
import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.Token;
import com.example.cbdc.token.TokenManager;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.JsonUtil;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.UUID;
import javax.crypto.SecretKey;

public class PayerBleClient {
    private static final String TAG = "PayerBleClient";
    
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt gatt;
    private PayerBleClientCallback callback;
    private DeviceKeyManager deviceKeyManager;
    private TokenManager tokenManager;
    
    // Ephemeral keys for session
    private KeyPair ephemeralKeyPair;
    private SecretKey sessionKey;
    private PublicKey merchantPublicKey;
    private BluetoothGattCharacteristic characteristic;
    
    public interface PayerBleClientCallback {
        void onPaymentSent();
        void onPaymentAccepted(JSONObject acceptReceipt);
        void onError(String error);
        void onConnected();
        void onDisconnected();
    }
    
    public PayerBleClient(Context context, DeviceKeyManager deviceKeyManager,
                         TokenManager tokenManager, PayerBleClientCallback callback) {
        this.context = context;
        this.deviceKeyManager = deviceKeyManager;
        this.tokenManager = tokenManager;
        this.callback = callback;
        
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        
        // Generate ephemeral key pair
        ephemeralKeyPair = CryptoUtil.generateX25519KeyPair();
    }
    
    public void connect(String deviceAddress) {
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            gatt = device.connectGatt(context, false, gattCallback);
            Log.d(TAG, "Connecting to device: " + deviceAddress);
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect", e);
            if (callback != null) {
                callback.onError("Connection failed: " + e.getMessage());
            }
        }
    }
    
    public void disconnect() {
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        sessionKey = null;
        merchantPublicKey = null;
    }
    
    public void sendTokenTransfer(Token token, String posId) {
        try {
            if (sessionKey == null || characteristic == null) {
                throw new Exception("Not connected or session not established");
            }
            
            KeyPair deviceKey = deviceKeyManager.getOrCreateDeviceKey();
            
            // Create transfer message
            JSONObject transfer = new JSONObject();
            transfer.put("type", "TOKEN_TRANSFER");
            transfer.put("token_serial", token.getSerial());
            transfer.put("pos_id", posId);
            transfer.put("timestamp", System.currentTimeMillis());
            transfer.put("payer_public_key", Base64Util.encode(
                CryptoUtil.encodePublicKey(deviceKey.getPublic())));
            
            // Sign transfer
            String transferData = transfer.toString();
            byte[] signature = CryptoUtil.sign(deviceKey.getPrivate(), transferData.getBytes());
            transfer.put("signature", Base64Util.encode(signature));
            
            // Create complete message
            JSONObject message = new JSONObject();
            message.put("type", "TOKEN_TRANSFER");
            message.put("token", token.getTokenData());
            message.put("transfer", transfer);
            
            // Encrypt and send
            byte[] plaintext = JsonUtil.toBytes(message);
            byte[] encrypted = CryptoUtil.encryptAEAD(sessionKey, plaintext, null);
            
            characteristic.setValue(encrypted);
            gatt.writeCharacteristic(characteristic);
            
            Log.d(TAG, "Token transfer sent");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to send token transfer", e);
            if (callback != null) {
                callback.onError("Failed to send payment: " + e.getMessage());
            }
        }
    }
    
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server");
                gatt.discoverServices();
                if (callback != null) {
                    callback.onConnected();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server");
                if (callback != null) {
                    callback.onDisconnected();
                }
            }
        }
        
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(BleUtils.CBDC_SERVICE_UUID);
                if (service != null) {
                    characteristic = service.getCharacteristic(BleUtils.CBDC_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        // Enable notifications
                        gatt.setCharacteristicNotification(characteristic, true);
                        
                        // Read merchant's ephemeral public key
                        gatt.readCharacteristic(characteristic);
                    }
                }
            }
        }
        
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] merchantKeyBytes = characteristic.getValue();
                if (merchantKeyBytes != null && merchantKeyBytes.length > 0) {
                    try {
                        merchantPublicKey = CryptoUtil.decodePublicKey(merchantKeyBytes);
                        
                        // Perform ECDH
                        byte[] sharedSecret = CryptoUtil.performECDH(
                            ephemeralKeyPair.getPrivate(), merchantPublicKey);
                        
                        // Derive session key
                        byte[] salt = new byte[32];
                        byte[] info = "CBDC_SESSION".getBytes();
                        sessionKey = CryptoUtil.deriveSessionKey(sharedSecret, salt, info);
                        
                        Log.d(TAG, "Session key established");
                        
                        // Send our ephemeral public key
                        byte[] ourPublicKey = CryptoUtil.encodePublicKey(ephemeralKeyPair.getPublic());
                        characteristic.setValue(ourPublicKey);
                        gatt.writeCharacteristic(characteristic);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to establish session", e);
                        if (callback != null) {
                            callback.onError("Session establishment failed: " + e.getMessage());
                        }
                    }
                }
            }
        }
        
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] encryptedData = characteristic.getValue();
            if (encryptedData != null && sessionKey != null) {
                try {
                    byte[] plaintext = CryptoUtil.decryptAEAD(sessionKey, encryptedData, null);
                    JSONObject message = JsonUtil.fromBytes(plaintext);
                    
                    String messageType = message.getString("type");
                    if ("ACCEPT".equals(messageType)) {
                        if (callback != null) {
                            callback.onPaymentAccepted(message);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to process notification", e);
                }
            }
        }
        
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic write successful");
                if (callback != null) {
                    callback.onPaymentSent();
                }
            }
        }
    };
}

