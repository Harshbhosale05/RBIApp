package com.example.cbdc;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.cbdc.ble.PayerBleClient;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.qr.QrParser;
import com.example.cbdc.token.Token;
import com.example.cbdc.token.TokenManager;
import org.json.JSONObject;

import java.util.ArrayList;
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
    private PayerBleClient bleClient;
    private BluetoothLeScanner bleScanner;
    private Handler handler;
    private boolean isScanning = false;
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
        
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            bleScanner = adapter.getBluetoothLeScanner();
        }
    }
    
    private void updateBalance() {
        double balance = tokenManager.getBalance();
        balanceText.setText(getString(R.string.balance, String.format("%.2f", balance)));
    }
    
    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    
    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
            new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
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
            
            // Scan for BLE device
            statusText.setText("Scanning for merchant...");
            progressBar.setVisibility(android.view.View.VISIBLE);
            scanForMerchantDevice(qrData);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to process QR code", e);
            showError("Failed to process QR code: " + e.getMessage());
        }
    }
    
    private void scanForMerchantDevice(JSONObject qrData) {
        if (bleScanner == null || isScanning) {
            return;
        }
        
        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter filter = new ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid.fromString(
                QrParser.extractServiceUuid(qrData)))
            .build();
        filters.add(filter);
        
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();
        
        isScanning = true;
        bleScanner.startScan(filters, settings, scanCallback);
        
        // Stop scanning after 10 seconds
        handler.postDelayed(() -> {
            if (isScanning) {
                stopScanning();
                showError("Merchant device not found");
            }
        }, 10000);
    }
    
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            if (device != null && currentToken != null && currentPosId != null) {
                stopScanning();
                connectAndSendPayment(device.getAddress(), currentToken, currentPosId);
            }
        }
        
        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            stopScanning();
            showError("BLE scan failed: " + errorCode);
        }
    };
    
    private void stopScanning() {
        if (isScanning && bleScanner != null) {
            bleScanner.stopScan(scanCallback);
            isScanning = false;
        }
    }
    
    private void connectAndSendPayment(String deviceAddress, Token token, String posId) {
        statusText.setText("Connecting...");
        
        bleClient = new PayerBleClient(this, deviceKeyManager, tokenManager, 
            new PayerBleClient.PayerBleClientCallback() {
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
                            R.string.payment_success, Toast.LENGTH_SHORT).show();
                        
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
                        statusText.setText("Connected, sending payment...");
                        bleClient.sendTokenTransfer(token, posId);
                    });
                }
                
                @Override
                public void onDisconnected() {
                    // Handle disconnection
                }
            });
        
        bleClient.connect(deviceAddress);
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
        stopScanning();
        if (bleClient != null) {
            bleClient.disconnect();
        }
    }
}

