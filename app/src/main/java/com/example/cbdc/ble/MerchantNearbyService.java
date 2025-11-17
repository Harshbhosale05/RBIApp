package com.example.cbdc.ble;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.ChainProof;
import com.example.cbdc.token.TokenManager;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.JsonUtil;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.gson.Gson;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

/**
 * Merchant Nearby Service - Handles receiving payments via Google Nearby Connections API
 * Replaces the old MerchantBleServer with a more reliable implementation
 */
public class MerchantNearbyService extends Service {
    private static final String TAG = "MerchantNearbyService";
    private static final String SERVICE_ID = "com.example.cbdc.CBDC_SERVICE";
    
    // Use P2P_STAR strategy for one-to-one merchant-payer connections
    private static final Strategy STRATEGY = Strategy.P2P_STAR;
    
    private ConnectionsClient connectionsClient;
    private final IBinder binder = new LocalBinder();
    private MerchantCallback callback;
    
    // Device and session management
    private String posId;
    private DeviceKeyManager deviceKeyManager;
    private TokenManager tokenManager;
    
    // Ephemeral keys for session encryption
    private KeyPair ephemeralKeyPair;
    private final Map<String, SecretKey> sessionKeys = new HashMap<>(); // endpointId -> sessionKey
    private final Map<String, PublicKey> payerPublicKeys = new HashMap<>(); // endpointId -> payerPublicKey
    
    // Track connected payers
    private final Map<String, String> connectedEndpoints = new HashMap<>(); // endpointId -> payerName
    
    public interface MerchantCallback {
        void onPaymentReceived(String tokenSerial, double amount);
        void onError(String error);
        void onClientConnected();
        void onClientDisconnected();
    }
    
    public class LocalBinder extends Binder {
        public MerchantNearbyService getService() {
            return MerchantNearbyService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        connectionsClient = Nearby.getConnectionsClient(this);
        Log.d(TAG, "MerchantNearbyService created");
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    public void setCallback(MerchantCallback callback) {
        this.callback = callback;
    }
    
    public void initialize(String posId, DeviceKeyManager deviceKeyManager, TokenManager tokenManager) {
        this.posId = posId;
        this.deviceKeyManager = deviceKeyManager;
        this.tokenManager = tokenManager;
        
        // Generate ephemeral key pair for this session
        ephemeralKeyPair = CryptoUtil.generateX25519KeyPair();
        Log.d(TAG, "Service initialized for POS: " + posId);
    }
    
    public void startAdvertising() {
        if (posId == null || deviceKeyManager == null || tokenManager == null) {
            Log.e(TAG, "✗ Service not initialized. Call initialize() first.");
            if (callback != null) {
                callback.onError("Service not initialized");
            }
            return;
        }
        
        // Log diagnostic info
        Log.d(TAG, "========== Starting BLE Advertising ==========");
        Log.d(TAG, "POS ID: " + posId);
        Log.d(TAG, "Service ID: " + SERVICE_ID);
        Log.d(TAG, "Strategy: " + STRATEGY);
        
        AdvertisingOptions options = new AdvertisingOptions.Builder()
                .setStrategy(STRATEGY)
                .build();
        
        String deviceName = "CBDC-Merchant-" + posId;
        Log.d(TAG, "Device Name: " + deviceName);
        Log.d(TAG, "Calling Nearby.startAdvertising()...");
        
        connectionsClient.startAdvertising(
                deviceName,
                SERVICE_ID,
                connectionLifecycleCallback,
                options
        )
        .addOnSuccessListener(aVoid -> {
            Log.d(TAG, "✓✓ Advertising started successfully!");
            Log.d(TAG, "Merchant is now discoverable as: " + deviceName);
            Log.d(TAG, "Advertising service: " + SERVICE_ID);
            Log.d(TAG, "Waiting for payer to connect...");
        })
        .addOnFailureListener(e -> {
            Log.e(TAG, "✗✗ Advertising FAILED!");
            Log.e(TAG, "Error: " + e.getMessage(), e);
            Log.e(TAG, "Error class: " + e.getClass().getName());
            
            String errorMsg = "Failed to start advertising: ";
            if (e.getMessage() != null) {
                if (e.getMessage().contains("BLUETOOTH")) {
                    errorMsg += "Bluetooth permission denied or not enabled";
                } else if (e.getMessage().contains("ADVERTISE")) {
                    errorMsg += "Bluetooth advertising not supported";
                } else if (e.getMessage().contains("8029")) {
                    errorMsg += "NEARBY_WIFI_DEVICES permission missing (Android 13+). Grant permission or use BLE-only mode.";
                } else {
                    errorMsg += e.getMessage();
                }
            } else {
                errorMsg += "Unknown error";
            }
            
            if (callback != null) {
                callback.onError(errorMsg);
            }
        });
    }
    
    public void stopAdvertising() {
        connectionsClient.stopAdvertising();
        connectionsClient.stopAllEndpoints();
        sessionKeys.clear();
        payerPublicKeys.clear();
        connectedEndpoints.clear();
        Log.d(TAG, "Advertising stopped");
    }
    
    public KeyPair getEphemeralKeyPair() {
        return ephemeralKeyPair;
    }
    
    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
            Log.i(TAG, "✓ Connection initiated with: " + connectionInfo.getEndpointName());
            // Automatically accept all connections
            connectionsClient.acceptConnection(endpointId, payloadCallback);
        }
        
        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                Log.i(TAG, "✓✓ Connection SUCCESSFUL with payer: " + endpointId);
                connectedEndpoints.put(endpointId, "Payer");
                if (callback != null) {
                    callback.onClientConnected();
                }
            } else {
                Log.w(TAG, "✗ Connection FAILED with: " + endpointId + " - Status: " + result.getStatus());
                if (callback != null) {
                    callback.onError("Connection failed with payer");
                }
            }
        }
        
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Log.i(TAG, "⚠ Disconnected from payer: " + endpointId);
            sessionKeys.remove(endpointId);
            payerPublicKeys.remove(endpointId);
            connectedEndpoints.remove(endpointId);
            if (callback != null) {
                callback.onClientDisconnected();
            }
        }
    };
    
    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                handleIncomingMessage(endpointId, payload.asBytes());
            }
        }
        
        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
            // Not used for byte payloads
        }
    };
    
    private void handleIncomingMessage(String endpointId, byte[] encryptedData) {
        try {
            SecretKey sessionKey = sessionKeys.get(endpointId);
            
            if (sessionKey == null) {
                // First message: payer's ephemeral public key for ECDH
                Log.d(TAG, "← Received payer's ephemeral public key");
                PublicKey payerPublicKey = CryptoUtil.decodePublicKey(encryptedData);
                payerPublicKeys.put(endpointId, payerPublicKey);
                
                // Perform ECDH key exchange
                byte[] sharedSecret = CryptoUtil.performECDH(
                        ephemeralKeyPair.getPrivate(),
                        payerPublicKey
                );
                
                // Derive session key
                byte[] salt = new byte[32]; // Zero salt for simplicity
                byte[] info = "CBDC_SESSION".getBytes();
                SecretKey derivedSessionKey = CryptoUtil.deriveSessionKey(sharedSecret, salt, info);
                sessionKeys.put(endpointId, derivedSessionKey);
                
                // Send our ephemeral public key back
                byte[] ourPublicKey = CryptoUtil.encodePublicKey(ephemeralKeyPair.getPublic());
                Payload responsePayload = Payload.fromBytes(ourPublicKey);
                connectionsClient.sendPayload(endpointId, responsePayload);
                
                Log.d(TAG, "✓ Session key established with payer " + endpointId);
                Log.d(TAG, "→ Sent our ephemeral public key back to payer");
                return;
            }
            
            // Decrypt message
            Log.d(TAG, "← Received encrypted payment message");
            byte[] plaintext = CryptoUtil.decryptAEAD(sessionKey, encryptedData, null);
            JSONObject message = JsonUtil.fromBytes(plaintext);
            
            String messageType = message.getString("type");
            
            if ("TOKEN_TRANSFER".equals(messageType)) {
                Log.d(TAG, "Processing TOKEN_TRANSFER message");
                handleTokenTransfer(endpointId, message);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to handle incoming message: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("Failed to process payment: " + e.getMessage());
            }
        }
    }
    
    private void handleTokenTransfer(String endpointId, JSONObject message) {
        try {
            Log.d(TAG, "Extracting token and transfer data");
            JSONObject tokenData = message.getJSONObject("token");
            JSONObject transfer = message.getJSONObject("transfer");
            
            String tokenSerial = tokenData.getString("serial");
            double amount = tokenData.getDouble("amount");
            
            Log.d(TAG, "Token: " + tokenSerial + ", Amount: Rs " + amount);
            
            // Verify transfer signature
            String payerPublicKeyBase64 = transfer.getString("payer_public_key");
            PublicKey payerKey = CryptoUtil.decodePublicKey(Base64Util.decode(payerPublicKeyBase64));
            
            String transferData = transfer.toString();
            String signatureBase64 = transfer.getString("signature");
            byte[] signature = Base64Util.decode(signatureBase64);
            
            // Remove signature for verification
            JSONObject transferForVerify = new JSONObject(transferData);
            transferForVerify.remove("signature");
            
            Log.d(TAG, "Verifying transfer signature...");
            boolean verified = CryptoUtil.verify(
                    payerKey,
                    transferForVerify.toString().getBytes(),
                    signature
            );
            
            if (!verified) {
                Log.e(TAG, "✗ Transfer signature verification FAILED");
                throw new Exception("Transfer signature verification failed");
            }
            
            Log.d(TAG, "✓ Transfer signature verified");
            
            // Create accept receipt
            JSONObject acceptReceipt = createAcceptReceipt(tokenData, transfer);
            
            // Store token with chain proof
            ChainProof chainProof = new ChainProof();
            chainProof.addTransfer(transfer);
            chainProof.setAcceptReceipt(acceptReceipt);
            
            tokenManager.addReceivedToken(tokenData, chainProof);
            Log.d(TAG, "✓ Token stored in merchant wallet");
            
            // Send accept receipt back
            sendAcceptReceipt(endpointId, acceptReceipt);
            
            if (callback != null) {
                callback.onPaymentReceived(tokenSerial, amount);
            }
            
            Log.d(TAG, "✓✓ Payment completed successfully: Rs " + amount);
            
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to handle token transfer: " + e.getMessage(), e);
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
                    CryptoUtil.encodePublicKey(deviceKey.getPublic())
            ));
            
            String acceptData = accept.toString();
            byte[] signature = CryptoUtil.sign(deviceKey.getPrivate(), acceptData.getBytes());
            accept.put("signature", Base64Util.encode(signature));
            
            return accept;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create accept receipt", e);
            throw new RuntimeException("Accept receipt creation failed", e);
        }
    }
    
    private void sendAcceptReceipt(String endpointId, JSONObject acceptReceipt) {
        try {
            SecretKey sessionKey = sessionKeys.get(endpointId);
            if (sessionKey == null) {
                Log.e(TAG, "No session key for endpoint: " + endpointId);
                return;
            }
            
            byte[] plaintext = JsonUtil.toBytes(acceptReceipt);
            byte[] encrypted = CryptoUtil.encryptAEAD(sessionKey, plaintext, null);
            
            Payload payload = Payload.fromBytes(encrypted);
            connectionsClient.sendPayload(endpointId, payload);
            
            Log.d(TAG, "Accept receipt sent to " + endpointId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send accept receipt", e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAdvertising();
        Log.d(TAG, "Service destroyed");
    }
}

