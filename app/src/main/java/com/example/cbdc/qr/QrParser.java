package com.example.cbdc.qr;

import android.util.Log;
import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.JsonUtil;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.PublicKey;

public class QrParser {
    private static final String TAG = "QrParser";
    
    /**
     * Parse QR code string to JSON
     */
    public static JSONObject parseQRString(String qrString) {
        try {
            return JsonUtil.parse(qrString);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse QR string", e);
            return null;
        }
    }
    
    /**
     * Verify QR code signature
     */
    public static boolean verifyQRSignature(JSONObject qrData) {
        try {
            if (!qrData.has("signature") || !qrData.has("merchant_public_key")) {
                return false;
            }
            
            String merchantKeyBase64 = qrData.getString("merchant_public_key");
            PublicKey merchantKey = CryptoUtil.decodePublicKey(Base64Util.decode(merchantKeyBase64));
            
            String signatureBase64 = qrData.getString("signature");
            byte[] signature = Base64Util.decode(signatureBase64);
            
            // Recreate QR data without signature for verification
            JSONObject qrDataForVerify = new JSONObject();
            qrDataForVerify.put("pos_id", qrData.getString("pos_id"));
            // Support both old service_uuid and new service_id for backward compatibility
            if (qrData.has("service_id")) {
                qrDataForVerify.put("service_id", qrData.getString("service_id"));
            } else if (qrData.has("service_uuid")) {
                qrDataForVerify.put("service_uuid", qrData.getString("service_uuid"));
            }
            qrDataForVerify.put("ephemeral_public_key", qrData.getString("ephemeral_public_key"));
            qrDataForVerify.put("nonce", qrData.getString("nonce"));
            qrDataForVerify.put("timestamp", qrData.getLong("timestamp"));
            
            String data = qrDataForVerify.toString();
            return CryptoUtil.verify(merchantKey, data.getBytes(), signature);
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify QR signature", e);
            return false;
        }
    }
    
    /**
     * Extract POS ID from QR data
     */
    public static String extractPosId(JSONObject qrData) {
        try {
            return qrData.getString("pos_id");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to extract POS ID", e);
            return null;
        }
    }
    
    /**
     * Extract service ID/UUID from QR data
     * Supports both new service_id (Nearby API) and old service_uuid (GATT) for compatibility
     */
    public static String extractServiceUuid(JSONObject qrData) {
        try {
            // Try new service_id first (Nearby Connections API)
            if (qrData.has("service_id")) {
                return qrData.getString("service_id");
            }
            // Fallback to old service_uuid (GATT) for backward compatibility
            if (qrData.has("service_uuid")) {
                return qrData.getString("service_uuid");
            }
            return null;
        } catch (JSONException e) {
            Log.e(TAG, "Failed to extract service ID/UUID", e);
            return null;
        }
    }
    
    /**
     * Extract ephemeral public key from QR data
     */
    public static PublicKey extractEphemeralPublicKey(JSONObject qrData) {
        try {
            String keyBase64 = qrData.getString("ephemeral_public_key");
            return CryptoUtil.decodePublicKey(Base64Util.decode(keyBase64));
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract ephemeral public key", e);
            return null;
        }
    }
    
    /**
     * Extract device address (BLE MAC) from QR or use service UUID to find device
     */
    public static String extractDeviceAddress(JSONObject qrData) {
        // In a real implementation, you might scan for BLE devices advertising the service UUID
        // For now, we'll return null and let the BLE client scan for devices
        return null;
    }
}

