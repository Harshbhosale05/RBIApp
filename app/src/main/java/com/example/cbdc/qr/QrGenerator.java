package com.example.cbdc.qr;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.util.Base64Util;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.json.JSONObject;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

public class QrGenerator {
    
    /**
     * Generate QR code data for merchant
     */
    public static JSONObject generateMerchantQR(String posId, KeyPair merchantIdentityKey, 
                                                KeyPair ephemeralKeyPair) {
        return generateMerchantQRWithPublicKey(posId, merchantIdentityKey, ephemeralKeyPair.getPublic());
    }
    
    /**
     * Generate QR code data for merchant with ephemeral public key
     */
    public static JSONObject generateMerchantQRWithPublicKey(String posId, KeyPair merchantIdentityKey, 
                                                             PublicKey ephemeralPublicKey) {
        try {
            JSONObject qrData = new JSONObject();
            qrData.put("pos_id", posId);
            qrData.put("service_uuid", "0000cbd1-0000-1000-8000-00805f9b34fb");
            qrData.put("ephemeral_public_key", Base64Util.encode(
                CryptoUtil.encodePublicKey(ephemeralPublicKey)));
            qrData.put("nonce", Base64Util.encode(CryptoUtil.generateNonce()));
            qrData.put("timestamp", System.currentTimeMillis());
            
            // Sign QR data with merchant identity key
            String qrDataString = qrData.toString();
            byte[] signature = CryptoUtil.sign(merchantIdentityKey.getPrivate(), 
                qrDataString.getBytes());
            qrData.put("signature", Base64Util.encode(signature));
            qrData.put("merchant_public_key", Base64Util.encode(
                CryptoUtil.encodePublicKey(merchantIdentityKey.getPublic())));
            
            return qrData;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate merchant QR", e);
        }
    }
    
    /**
     * Generate QR code bitmap from JSON data
     */
    public static Bitmap generateQRBitmap(JSONObject qrData, int width, int height) {
        try {
            String qrString = qrData.toString();
            
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(qrString, BarcodeFormat.QR_CODE, width, height, hints);
            
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            
            return bitmap;
        } catch (WriterException e) {
            throw new RuntimeException("Failed to generate QR bitmap", e);
        }
    }
}

