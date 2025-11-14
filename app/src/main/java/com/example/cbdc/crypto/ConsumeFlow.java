package com.example.cbdc.crypto;

import android.util.Log;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.HexUtil;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.UUID;

public class ConsumeFlow {
    private static final String TAG = "ConsumeFlow";
    
    /**
     * Create a ConsumeReceipt signed by device key
     */
    public static JSONObject createConsumeReceipt(
            String tokenSerial,
            String posId,
            long timestamp,
            long counter,
            PrivateKey devicePrivateKey) {
        try {
            JSONObject receipt = new JSONObject();
            receipt.put("token_serial", tokenSerial);
            receipt.put("pos_id", posId);
            receipt.put("timestamp", timestamp);
            receipt.put("counter", counter);
            receipt.put("device_id", UUID.randomUUID().toString());
            
            // Create signature
            String receiptData = receipt.toString();
            byte[] signature = CryptoUtil.sign(devicePrivateKey, receiptData.getBytes());
            receipt.put("signature", Base64Util.encode(signature));
            
            return receipt;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create consume receipt", e);
            throw new RuntimeException("Consume receipt creation failed", e);
        }
    }
    
    /**
     * Verify consume receipt
     */
    public static boolean verifyConsumeReceipt(JSONObject receipt, PublicKey devicePublicKey) {
        try {
            if (!receipt.has("signature")) {
                return false;
            }
            
            String signatureBase64 = receipt.getString("signature");
            byte[] signature = Base64Util.decode(signatureBase64);
            
            // Recreate receipt data without signature
            JSONObject receiptData = new JSONObject();
            receiptData.put("token_serial", receipt.getString("token_serial"));
            receiptData.put("pos_id", receipt.getString("pos_id"));
            receiptData.put("timestamp", receipt.getLong("timestamp"));
            receiptData.put("counter", receipt.getLong("counter"));
            receiptData.put("device_id", receipt.getString("device_id"));
            
            String data = receiptData.toString();
            return CryptoUtil.verify(devicePublicKey, data.getBytes(), signature);
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify consume receipt", e);
            return false;
        }
    }
    
    /**
     * Extract token serial from receipt
     */
    public static String extractTokenSerial(JSONObject receipt) {
        try {
            return receipt.getString("token_serial");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to extract token serial", e);
            return null;
        }
    }
}

