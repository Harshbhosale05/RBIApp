package com.example.cbdc.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;
import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.ChainProof;
import com.example.cbdc.token.TokenManager;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.JsonUtil;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.UUID;
import javax.crypto.SecretKey;

public class MerchantBleServer {
    private static final String TAG = "MerchantBleServer";
    
    private Context context;
    private BluetoothGattServer gattServer;
    private BluetoothAdapter bluetoothAdapter;
    private MerchantBleServerCallback callback;
    private DeviceKeyManager deviceKeyManager;
    private TokenManager tokenManager;
    private String posId;
    
    // Ephemeral keys for session
    private KeyPair ephemeralKeyPair;
    private SecretKey sessionKey;
    private PublicKey payerPublicKey;
    
    public interface MerchantBleServerCallback {
        void onPaymentReceived(String tokenSerial, double amount);
        void onError(String error);
        void onClientConnected();
        void onClientDisconnected();
    }
    
    public MerchantBleServer(Context context, String posId, 
                            DeviceKeyManager deviceKeyManager, 
                            TokenManager tokenManager,
                            MerchantBleServerCallback callback) {
        this.context = context;
        this.posId = posId;
        this.deviceKeyManager = deviceKeyManager;
        this.tokenManager = tokenManager;
        this.callback = callback;
        
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }
    
    public void start() {
        try {
//            gattServer = bluetoothAdapter.openGattServer(context, gattServerCallback);
            
            BluetoothGattService service = BleUtils.createCbdcService();
//            gattServer.addService(service);
            
            // Generate ephemeral key pair
            ephemeralKeyPair = CryptoUtil.generateX25519KeyPair();
            
            Log.d(TAG, "Merchant BLE server started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BLE server", e);
            if (callback != null) {
                callback.onError("Failed to start BLE server: " + e.getMessage());
            }
        }
    }
    
    public void stop() {
        if (gattServer != null) {
//            gattServer.close();
            gattServer = null;
        }
    }
    
    public KeyPair getEphemeralKeyPair() {
        return ephemeralKeyPair;
    }
    
    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Client connected: " + device.getAddress());
                if (callback != null) {
                    callback.onClientConnected();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Client disconnected: " + device.getAddress());
                sessionKey = null;
                payerPublicKey = null;
                if (callback != null) {
                    callback.onClientDisconnected();
                }
            }
        }
        
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (characteristic.getUuid().equals(BleUtils.CBDC_CHARACTERISTIC_UUID)) {
                handleIncomingMessage(device, value);
                
                if (responseNeeded) {
//                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
            }
        }
        
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (characteristic.getUuid().equals(BleUtils.CBDC_CHARACTERISTIC_UUID)) {
                // Send ephemeral public key
                if (ephemeralKeyPair != null) {
                    byte[] publicKeyBytes = CryptoUtil.encodePublicKey(ephemeralKeyPair.getPublic());
                    characteristic.setValue(publicKeyBytes);
                }
//                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());
            }
        }
    };
    
    private void handleIncomingMessage(BluetoothDevice device, byte[] encryptedData) {
        try {
            if (sessionKey == null) {
                // First message: payer's ephemeral public key
                payerPublicKey = CryptoUtil.decodePublicKey(encryptedData);
                
                // Perform ECDH
                byte[] sharedSecret = CryptoUtil.performECDH(
                    ephemeralKeyPair.getPrivate(), payerPublicKey);
                
                // Derive session key
                byte[] salt = new byte[32]; // Use zero salt for simplicity
                byte[] info = "CBDC_SESSION".getBytes();
                sessionKey = CryptoUtil.deriveSessionKey(sharedSecret, salt, info);
                
                Log.d(TAG, "Session key established");
                return;
            }
            
            // Decrypt message
            byte[] plaintext = CryptoUtil.decryptAEAD(sessionKey, encryptedData, null);
            JSONObject message = JsonUtil.fromBytes(plaintext);
            
            String messageType = message.getString("type");
            
            if ("TOKEN_TRANSFER".equals(messageType)) {
                handleTokenTransfer(message);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle incoming message", e);
            if (callback != null) {
                callback.onError("Failed to process message: " + e.getMessage());
            }
        }
    }
    
    private void handleTokenTransfer(JSONObject message) {
        try {
            JSONObject tokenData = message.getJSONObject("token");
            JSONObject transfer = message.getJSONObject("transfer");
            
            // Verify transfer signature
            String payerPublicKeyBase64 = transfer.getString("payer_public_key");
            PublicKey payerKey = CryptoUtil.decodePublicKey(Base64Util.decode(payerPublicKeyBase64));
            
            String transferData = transfer.toString();
            String signatureBase64 = transfer.getString("signature");
            byte[] signature = Base64Util.decode(signatureBase64);
            
            // Remove signature for verification
            JSONObject transferForVerify = new JSONObject(transferData);
            transferForVerify.remove("signature");
            
            boolean verified = CryptoUtil.verify(payerKey, 
                transferForVerify.toString().getBytes(), signature);
            
            if (!verified) {
                throw new Exception("Transfer signature verification failed");
            }
            
            // Create accept receipt
            JSONObject acceptReceipt = createAcceptReceipt(tokenData, transfer);
            
            // Store token with chain proof
            ChainProof chainProof = new ChainProof();
            chainProof.addTransfer(transfer);
            chainProof.setAcceptReceipt(acceptReceipt);
            
            tokenManager.addReceivedToken(tokenData, chainProof);
            
            // Send accept receipt back
            sendAcceptReceipt(acceptReceipt);
            
            String tokenSerial = tokenData.getString("serial");
            double amount = tokenData.getDouble("amount");
            
            if (callback != null) {
                callback.onPaymentReceived(tokenSerial, amount);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle token transfer", e);
            if (callback != null) {
                callback.onError("Token transfer failed: " + e.getMessage());
            }
        }
    }
    
    private JSONObject createAcceptReceipt(JSONObject tokenData, JSONObject transfer) {
        try {
            KeyPair deviceKey = deviceKeyManager.getOrCreateDeviceKey();
            
            JSONObject accept = new JSONObject();
            accept.put("type", "ACCEPT");
            accept.put("token_serial", tokenData.getString("serial"));
            accept.put("pos_id", posId);
            accept.put("timestamp", System.currentTimeMillis());
            accept.put("merchant_public_key", Base64Util.encode(
                CryptoUtil.encodePublicKey(deviceKey.getPublic())));
            
            String acceptData = accept.toString();
            byte[] signature = CryptoUtil.sign(deviceKey.getPrivate(), acceptData.getBytes());
            accept.put("signature", Base64Util.encode(signature));
            
            return accept;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create accept receipt", e);
            throw new RuntimeException("Accept receipt creation failed", e);
        }
    }
    
    private void sendAcceptReceipt(JSONObject acceptReceipt) {
        try {
            if (sessionKey == null) {
                return;
            }
            
            byte[] plaintext = JsonUtil.toBytes(acceptReceipt);
            byte[] encrypted = CryptoUtil.encryptAEAD(sessionKey, plaintext, null);
            
            BluetoothGattService service = gattServer.getService(BleUtils.CBDC_SERVICE_UUID);
            if (service != null) {
                BluetoothGattCharacteristic characteristic = 
                    service.getCharacteristic(BleUtils.CBDC_CHARACTERISTIC_UUID);
                if (characteristic != null) {
                    characteristic.setValue(encrypted);
                    // Notify all connected devices
//                    for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
//                        gattServer.notifyCharacteristicChanged(device, characteristic, false);
//                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send accept receipt", e);
        }
    }
}

