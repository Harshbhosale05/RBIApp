package com.example.cbdc.ble;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.Token;
import com.example.cbdc.token.TokenManager;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.JsonUtil;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONObject;

import java.security.KeyPair;
import java.security.PublicKey;

import javax.crypto.SecretKey;

/**
 * Payer Nearby Client - Handles sending payments via Google Nearby Connections API
 * Replaces the old PayerBleClient with a more reliable implementation
 */
public class PayerNearbyClient {
    private static final String TAG = "PayerNearbyClient";
    private static final String SERVICE_ID = "com.example.cbdc.CBDC_SERVICE";
    private static final Strategy STRATEGY = Strategy.P2P_STAR;
    
    private final Context context;
    private final ConnectionsClient connectionsClient;
    private final DeviceKeyManager deviceKeyManager;
    private final TokenManager tokenManager;
    private final PayerCallback callback;
    
    // Ephemeral keys for session encryption
    private KeyPair ephemeralKeyPair;
    private SecretKey sessionKey;
    private PublicKey merchantPublicKey;
    private String connectedEndpointId;
    
    // State tracking
    private boolean isDiscovering = false;
    private boolean isKeyExchangeComplete = false;
    
    // Payment data to send after key exchange
    private Token pendingToken;
    private String pendingPosId;
    
    public interface PayerCallback {
        void onPaymentSent();
        void onPaymentAccepted(JSONObject acceptReceipt);
        void onError(String error);
        void onConnected();
        void onDisconnected();
        void onEndpointDiscovered(String endpointId, String endpointName, String serviceId);
        void onEndpointLost(String endpointId);
    }
    
    public PayerNearbyClient(Context context,
                            DeviceKeyManager deviceKeyManager,
                            TokenManager tokenManager,
                            PayerCallback callback) {
        this.context = context;
        this.deviceKeyManager = deviceKeyManager;
        this.tokenManager = tokenManager;
        this.callback = callback;
        this.connectionsClient = Nearby.getConnectionsClient(context);
        
        // Generate ephemeral key pair for session
        ephemeralKeyPair = CryptoUtil.generateX25519KeyPair();
    }
    
    public void startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress");
            return;
        }
        
        // Log diagnostic info
        Log.d(TAG, "========== Starting BLE Discovery ==========");
        Log.d(TAG, "Service ID: " + SERVICE_ID);
        Log.d(TAG, "Strategy: " + STRATEGY);
        Log.d(TAG, "Context: " + context.getClass().getSimpleName());
        
        DiscoveryOptions options = new DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .build();
        
        Log.d(TAG, "Calling Nearby.startDiscovery()...");
        
        connectionsClient.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                options
        )
        .addOnSuccessListener(aVoid -> {
            Log.d(TAG, "✓✓ Discovery started successfully!");
            Log.d(TAG, "Now scanning for devices advertising: " + SERVICE_ID);
            isDiscovering = true;
        })
        .addOnFailureListener(e -> {
            Log.e(TAG, "✗✗ Discovery FAILED!");
            Log.e(TAG, "Error: " + e.getMessage(), e);
            Log.e(TAG, "Error class: " + e.getClass().getName());
            
            String errorMsg = "Failed to start discovery: ";
            if (e.getMessage() != null) {
                if (e.getMessage().contains("BLUETOOTH")) {
                    errorMsg += "Bluetooth permission denied or not enabled";
                } else if (e.getMessage().contains("LOCATION")) {
                    errorMsg += "Location permission required";
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
    
    public void stopDiscovery() {
        if (isDiscovering) {
            connectionsClient.stopDiscovery();
            isDiscovering = false;
            Log.d(TAG, "Discovery stopped");
        }
    }
    
    public void disconnect() {
        stopDiscovery();
        if (connectedEndpointId != null) {
            connectionsClient.disconnectFromEndpoint(connectedEndpointId);
            connectedEndpointId = null;
        }
        sessionKey = null;
        merchantPublicKey = null;
        isKeyExchangeComplete = false;
    }
    
    /**
     * Connect directly to a known endpoint (from cache)
     * Used for instant connection when merchant already discovered
     */
    public void connectToEndpoint(String endpointId, String endpointName) {
        Log.d(TAG, "========== CONNECTING TO CACHED ENDPOINT ==========");
        Log.d(TAG, "Endpoint ID: " + endpointId);
        Log.d(TAG, "Endpoint Name: " + endpointName);
        
        // Stop discovery since we're connecting directly
        stopDiscovery();
        
        // Request connection
        connectionsClient.requestConnection(
                "CBDC-Payer",
                endpointId,
                connectionLifecycleCallback
        )
        .addOnSuccessListener(aVoid -> {
            Log.d(TAG, "✓ Connection request sent to cached merchant");
        })
        .addOnFailureListener(e -> {
            Log.e(TAG, "✗ Failed to connect to cached endpoint: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("Failed to connect to merchant: " + e.getMessage());
            }
        });
    }
    
    public void sendTokenTransfer(Token token, String posId) {
        // Store payment data if key exchange not complete yet
        if (!isKeyExchangeComplete || sessionKey == null || connectedEndpointId == null) {
            Log.d(TAG, "Key exchange not complete yet, storing payment data for later");
            pendingToken = token;
            pendingPosId = posId;
            return;
        }
        
        // Send payment immediately if ready
        sendPaymentInternal(token, posId);
    }
    
    private void sendPaymentInternal(Token token, String posId) {
        if (sessionKey == null || connectedEndpointId == null) {
            Log.e(TAG, "Cannot send payment - no session");
            if (callback != null) {
                callback.onError("Not connected or session not established");
            }
            return;
        }
        
        try {
            KeyPair deviceKey = deviceKeyManager.getOrCreateDeviceKey();
            
            // Create transfer message
            JSONObject transfer = new JSONObject();
            transfer.put("type", "TOKEN_TRANSFER");
            transfer.put("token_serial", token.getSerial());
            transfer.put("pos_id", posId);
            transfer.put("timestamp", System.currentTimeMillis());
            transfer.put("payer_public_key", Base64Util.encode(
                    CryptoUtil.encodePublicKey(deviceKey.getPublic())
            ));
            
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
            
            Payload payload = Payload.fromBytes(encrypted);
            connectionsClient.sendPayload(connectedEndpointId, payload);
            
            Log.d(TAG, "Token transfer sent");
            if (callback != null) {
                callback.onPaymentSent();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to send token transfer", e);
            if (callback != null) {
                callback.onError("Failed to send payment: " + e.getMessage());
            }
        }
    }
    
    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            Log.i(TAG, "========== ENDPOINT FOUND! ==========");
            Log.i(TAG, "✓ Merchant discovered!");
            Log.i(TAG, "Endpoint Name: " + info.getEndpointName());
            Log.i(TAG, "Endpoint ID: " + endpointId);
            Log.i(TAG, "Service ID: " + info.getServiceId());
            
            // Notify callback for caching (don't stop discovery for background mode)
            if (callback != null) {
                callback.onEndpointDiscovered(endpointId, info.getEndpointName(), info.getServiceId());
            }
        }
        
        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            Log.i(TAG, "⚠ Endpoint lost: " + endpointId);
            if (callback != null) {
                callback.onEndpointLost(endpointId);
            }
        }
    };
    
    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
            Log.i(TAG, "✓ Connection initiated with: " + connectionInfo.getEndpointName());
            // Automatically accept connection
            connectionsClient.acceptConnection(endpointId, payloadCallback);
        }
        
        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                Log.i(TAG, "✓✓ Connection SUCCESSFUL with: " + endpointId);
                connectedEndpointId = endpointId;
                stopDiscovery(); // Stop discovery once connected
                
                if (callback != null) {
                    callback.onConnected();
                }
                
                // Start key exchange by sending our ephemeral public key
                try {
                    byte[] ourPublicKey = CryptoUtil.encodePublicKey(ephemeralKeyPair.getPublic());
                    Payload payload = Payload.fromBytes(ourPublicKey);
                    connectionsClient.sendPayload(endpointId, payload);
                    Log.d(TAG, "→ Sent our ephemeral public key for key exchange");
                } catch (Exception e) {
                    Log.e(TAG, "✗ Failed to send public key: " + e.getMessage(), e);
                    if (callback != null) {
                        callback.onError("Failed to initiate key exchange: " + e.getMessage());
                    }
                }
            } else {
                Log.w(TAG, "✗ Connection FAILED with: " + endpointId + " - Status: " + result.getStatus());
                if (callback != null) {
                    callback.onError("Connection failed - please try again");
                }
            }
        }
        
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Log.i(TAG, "⚠ Disconnected from: " + endpointId);
            connectedEndpointId = null;
            sessionKey = null;
            merchantPublicKey = null;
            isKeyExchangeComplete = false;
            if (callback != null) {
                callback.onDisconnected();
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
    
    private void handleIncomingMessage(String endpointId, byte[] data) {
        try {
            if (!isKeyExchangeComplete) {
                // This should be merchant's ephemeral public key
                Log.d(TAG, "← Received merchant's ephemeral public key");
                merchantPublicKey = CryptoUtil.decodePublicKey(data);
                
                // Perform ECDH key exchange
                byte[] sharedSecret = CryptoUtil.performECDH(
                        ephemeralKeyPair.getPrivate(),
                        merchantPublicKey
                );
                
                // Derive session key
                byte[] salt = new byte[32];
                byte[] info = "CBDC_SESSION".getBytes();
                sessionKey = CryptoUtil.deriveSessionKey(sharedSecret, salt, info);
                
                isKeyExchangeComplete = true;
                Log.d(TAG, "✓ Session key established via ECDH");
                
                // Send pending payment if any
                if (pendingToken != null && pendingPosId != null) {
                    Log.d(TAG, "→ Sending pending payment after key exchange");
                    sendPaymentInternal(pendingToken, pendingPosId);
                    pendingToken = null;
                    pendingPosId = null;
                }
                
            } else {
                // This is an encrypted message (ACCEPT receipt)
                if (sessionKey == null) {
                    Log.e(TAG, "✗ Received encrypted message but no session key");
                    return;
                }
                
                Log.d(TAG, "← Received encrypted message (ACCEPT receipt)");
                byte[] plaintext = CryptoUtil.decryptAEAD(sessionKey, data, null);
                JSONObject message = JsonUtil.fromBytes(plaintext);
                
                String messageType = message.getString("type");
                if ("ACCEPT".equals(messageType)) {
                    Log.d(TAG, "✓ Payment ACCEPTED by merchant");
                    if (callback != null) {
                        callback.onPaymentAccepted(message);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to handle incoming message: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("Failed to process message: " + e.getMessage());
            }
        }
    }
}

