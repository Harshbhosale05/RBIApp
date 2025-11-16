package com.example.cbdc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.cbdc.ble.PayerNearbyClient;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.qr.QrParser;
import com.example.cbdc.token.Token;
import com.example.cbdc.token.TokenManager;
import org.json.JSONObject;

import java.util.List;

public class PayerModeActivity extends AppCompatActivity {
    private static final String TAG = "PayerModeActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int CAMERA_REQUEST_CODE = 200;
    
    private TextView balanceText;
    private TextView statusText;
    private Button scanQRButton;
    private ProgressBar progressBar;
    
    private DeviceKeyManager deviceKeyManager;
    private TokenManager tokenManager;
    private PayerNearbyClient nearbyClient;
    private Handler handler;
    private JSONObject currentQRData;
    private String currentPosId;
    private Token currentToken;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payer_mode);
        
        deviceKeyManager = new DeviceKeyManager(this);
        tokenManager = new TokenManager(this, deviceKeyManager);
        handler = new Handler();
        
        balanceText = findViewById(R.id.payerBalanceText);
        statusText = findViewById(R.id.payerStatusText);
        scanQRButton = findViewById(R.id.scanQRButton);
        progressBar = findViewById(R.id.payerProgressBar);
        
        updateBalance();
        
        scanQRButton.setOnClickListener(v -> {
            if (checkPermissions()) {
                startQRScan();
            } else {
                requestPermissions();
            }
        });
    }
    
    private void updateBalance() {
        double balance = tokenManager.getBalance();
        balanceText.setText(getString(R.string.balance, String.format("%.2f", balance)));
    }
    
    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }
    
    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
            new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            },
            PERMISSION_REQUEST_CODE);
    }
    
    private void startQRScan() {
        Intent intent = new Intent(this, CameraQRScanActivity.class);
        startActivityForResult(intent, CAMERA_REQUEST_CODE);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            String qrDataString = data.getStringExtra("qr_data");
            if (qrDataString != null) {
                processQRCode(qrDataString);
            }
        }
    }
    
    private void processQRCode(String qrDataString) {
        try {
            JSONObject qrData = QrParser.parseQRString(qrDataString);
            if (qrData == null) {
                showError("Invalid QR code");
                return;
            }
            
            if (!QrParser.verifyQRSignature(qrData)) {
                showError("QR signature verification failed");
                return;
            }
            
            String posId = QrParser.extractPosId(qrData);
            if (posId == null) {
                showError("Failed to extract POS ID");
                return;
            }
            
            // Get a token to send
            List<Token> tokens = tokenManager.getAllTokens();
            if (tokens.isEmpty()) {
                showError("No tokens available");
                return;
            }
            
            currentToken = tokens.get(0);
            currentQRData = qrData;
            currentPosId = posId;
            
            // Start Nearby discovery and connect
            statusText.setText("Discovering merchant...");
            progressBar.setVisibility(android.view.View.VISIBLE);
            connectAndSendPayment(currentToken, currentPosId);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to process QR code", e);
            showError("Failed to process QR code: " + e.getMessage());
        }
    }
    
    private void connectAndSendPayment(Token token, String posId) {
        statusText.setText("Discovering merchant...");
        
        nearbyClient = new PayerNearbyClient(this, deviceKeyManager, tokenManager, 
            new PayerNearbyClient.PayerCallback() {
                @Override
                public void onPaymentSent() {
                    runOnUiThread(() -> {
                        statusText.setText("Payment sent, waiting for confirmation...");
                    });
                }
                
                @Override
                public void onPaymentAccepted(JSONObject acceptReceipt) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(android.view.View.GONE);
                        statusText.setText("Payment accepted!");
                        
                        // Delete token after successful transfer
                        tokenManager.deleteToken(token.getSerial());
                        updateBalance();
                        
                        Toast.makeText(PayerModeActivity.this, 
                            "Payment successful!", Toast.LENGTH_SHORT).show();
                        
                        handler.postDelayed(() -> finish(), 2000);
                    });
                }
                
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(android.view.View.GONE);
                        showError(error);
                    });
                }
                
                @Override
                public void onConnected() {
                    runOnUiThread(() -> {
                        statusText.setText("Connected, establishing secure session...");
                        // Payment will be sent automatically after key exchange completes
                        // The key exchange happens in the background
                    });
                }
                
                @Override
                public void onDisconnected() {
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected");
                    });
                }
            });
        
        // Start discovery - will automatically connect when merchant is found
        nearbyClient.startDiscovery();
        
        // Send payment - it will be queued until key exchange completes
        nearbyClient.sendTokenTransfer(token, posId);
    }
    
    private void showError(String error) {
        statusText.setText(getString(R.string.error, error));
        progressBar.setVisibility(android.view.View.GONE);
        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startQRScan();
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nearbyClient != null) {
            nearbyClient.disconnect();
        }
    }
}

