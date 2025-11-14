package com.example.cbdc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.cbdc.ble.MerchantBleServer;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.TokenManager;

import java.util.UUID;

public class MerchantModeActivity extends AppCompatActivity {
    private static final String TAG = "MerchantModeActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private TextView posIdText;
    private TextView statusText;
    private Button displayQRButton;
    private ProgressBar progressBar;
    
    private DeviceKeyManager deviceKeyManager;
    private TokenManager tokenManager;
    private MerchantBleServer bleServer;
    private String posId;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_merchant_mode);
        
        deviceKeyManager = new DeviceKeyManager(this);
        tokenManager = new TokenManager(this, deviceKeyManager);
        
        // Generate POS ID if not exists
        posId = UUID.randomUUID().toString().substring(0, 8);
        
        posIdText = findViewById(R.id.merchantPosIdText);
        statusText = findViewById(R.id.merchantStatusText);
        displayQRButton = findViewById(R.id.displayQRButton);
        progressBar = findViewById(R.id.merchantProgressBar);
        
        posIdText.setText("POS ID: " + posId);
        statusText.setText("Ready");
        
        displayQRButton.setOnClickListener(v -> {
            if (checkPermissions()) {
                startMerchantMode();
            } else {
                requestPermissions();
            }
        });
    }
    
    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    
    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
            new String[]{
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            },
            PERMISSION_REQUEST_CODE);
    }
    
    private void startMerchantMode() {
        try {
            // Start BLE server
            bleServer = new MerchantBleServer(this, posId, deviceKeyManager, tokenManager,
                new MerchantBleServer.MerchantBleServerCallback() {
                    @Override
                    public void onPaymentReceived(String tokenSerial, double amount) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(android.view.View.GONE);
                            statusText.setText(getString(R.string.payment_received) + 
                                " Amount: " + amount);
                            Toast.makeText(MerchantModeActivity.this, 
                                R.string.payment_received, Toast.LENGTH_SHORT).show();
                        });
                    }
                    
                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(android.view.View.GONE);
                            statusText.setText(getString(R.string.error, error));
                            Toast.makeText(MerchantModeActivity.this, error, Toast.LENGTH_LONG).show();
                        });
                    }
                    
                    @Override
                    public void onClientConnected() {
                        runOnUiThread(() -> {
                            statusText.setText("Client connected, waiting for payment...");
                            progressBar.setVisibility(android.view.View.VISIBLE);
                        });
                    }
                    
                    @Override
                    public void onClientDisconnected() {
                        runOnUiThread(() -> {
                            statusText.setText("Client disconnected");
                            progressBar.setVisibility(android.view.View.GONE);
                        });
                    }
                });
            
            bleServer.start();
            
            // Get ephemeral key from BLE server
            java.security.KeyPair ephemeralKey = bleServer.getEphemeralKeyPair();
            if (ephemeralKey != null) {
                // Display QR code with ephemeral key
                Intent intent = new Intent(this, QRDisplayActivity.class);
                intent.putExtra("pos_id", posId);
                intent.putExtra("ephemeral_public_key", 
                    com.example.cbdc.util.Base64Util.encode(
                        com.example.cbdc.crypto.CryptoUtil.encodePublicKey(
                            ephemeralKey.getPublic())));
                startActivity(intent);
            }
            
            statusText.setText("Waiting for payment...");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start merchant mode", e);
            Toast.makeText(this, "Failed to start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMerchantMode();
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleServer != null) {
            bleServer.stop();
        }
    }
}

