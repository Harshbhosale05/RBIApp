package com.example.cbdc;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.qr.QrGenerator;
import com.example.cbdc.util.Base64Util;

import java.security.KeyPair;
import java.security.PublicKey;

public class QRDisplayActivity extends AppCompatActivity {
    
    private ImageView qrImageView;
    private TextView qrInfoText;
    private DeviceKeyManager deviceKeyManager;
    private String posId;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_display);
        
        posId = getIntent().getStringExtra("pos_id");
        if (posId == null) {
            finish();
            return;
        }
        
        deviceKeyManager = new DeviceKeyManager(this);
        qrImageView = findViewById(R.id.qrImageView);
        qrInfoText = findViewById(R.id.qrInfoText);
        
        generateAndDisplayQR();
    }
    
    private void generateAndDisplayQR() {
        try {
            KeyPair deviceKey = deviceKeyManager.getOrCreateDeviceKey();
            
            // Get ephemeral public key from BLE server (passed via intent)
            String ephemeralKeyBase64 = getIntent().getStringExtra("ephemeral_public_key");
            org.json.JSONObject qrData;
            
            if (ephemeralKeyBase64 != null) {
                PublicKey serverEphemeralPublicKey = CryptoUtil.decodePublicKey(Base64Util.decode(ephemeralKeyBase64));
                qrData = QrGenerator.generateMerchantQRWithPublicKey(
                    posId, deviceKey, serverEphemeralPublicKey);
            } else {
                KeyPair ephemeralKey = CryptoUtil.generateX25519KeyPair();
                qrData = QrGenerator.generateMerchantQR(posId, deviceKey, ephemeralKey);
            }
            
            Log.d("QRDisplayActivity", "Generated QR Data: " + qrData.toString());
            Log.d("QRDisplayActivity", "QR Data length: " + qrData.toString().length());

            // Generate the QR bitmap directly from JSON
            Bitmap qrBitmap = QrGenerator.generateQRBitmap(qrData, 500, 500);
            qrImageView.setImageBitmap(qrBitmap);
            
            qrInfoText.setText("POS ID: " + posId + "\nScan this QR code to pay");
            
        } catch (Exception e) {
            Log.e("QRDisplayActivity", "Error generating QR", e);
            qrInfoText.setText("Error generating QR: " + e.getMessage());
        }
    }
}
