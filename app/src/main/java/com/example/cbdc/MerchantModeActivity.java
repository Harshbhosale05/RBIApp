package com.example.cbdc;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.example.cbdc.ble.MerchantNearbyService;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.TokenManager;
import com.example.cbdc.util.BluetoothHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MerchantModeActivity extends AppCompatActivity {

    private static final String TAG = "MerchantModeActivity";

    private TextView posIdText;
    private TextView balanceText;
    private TextView statusText;
    private Button displayQRButton;
    private ProgressBar progressBar;

    private DeviceKeyManager deviceKeyManager;
    private TokenManager tokenManager;
    private MerchantNearbyService nearbyService;
    private boolean isServiceBound = false;
    private String posId;
    
    // Track received tokens for receipt
    private List<String> receivedTokenIds = new ArrayList<>();
    private double transactionAmount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_merchant_mode);

        deviceKeyManager = new DeviceKeyManager(this);
        tokenManager = new TokenManager(this, deviceKeyManager);
        tokenManager.ensureInitialWallet();

        // Generate short POS ID
        posId = UUID.randomUUID().toString().substring(0, 8);

        posIdText = findViewById(R.id.merchantPosIdText);
        balanceText = findViewById(R.id.merchantBalanceText);
        statusText = findViewById(R.id.merchantStatusText);
        displayQRButton = findViewById(R.id.displayQRButton);
        progressBar = findViewById(R.id.merchantProgressBar);

        posIdText.setText("POS ID: " + posId);
        updateBalance();
        statusText.setText("Ready to accept payments");

        displayQRButton.setOnClickListener(v -> {
            if (!checkPermissionsAndBluetooth()) {
                return;
            }
            startMerchantMode();
        });
    }
    
    private void updateBalance() {
        double balance = tokenManager.getBalance();
        List<com.example.cbdc.token.Token> tokens = tokenManager.getAllTokens();
        
        // Group tokens by denomination
        StringBuilder tokenDetails = new StringBuilder();
        tokenDetails.append("Balance: Rs ").append(String.format("%.0f", balance)).append("\n\n");
        tokenDetails.append("Tokens:\n");
        
        // Count tokens by denomination
        Map<Integer, Integer> denominationCount = new HashMap<>();
        for (com.example.cbdc.token.Token token : tokens) {
            int denom = (int) token.getAmount();
            denominationCount.put(denom, denominationCount.getOrDefault(denom, 0) + 1);
        }
        
        // Sort and display
        List<Integer> denoms = new ArrayList<>(denominationCount.keySet());
        Collections.sort(denoms, Collections.reverseOrder());
        
        for (int denom : denoms) {
            int count = denominationCount.get(denom);
            tokenDetails.append("Rs ").append(denom).append(" × ").append(count).append("\n");
        }
        
        balanceText.setText(tokenDetails.toString());
    }
    
    private boolean checkPermissionsAndBluetooth() {
        // Check permissions
        if (!BluetoothHelper.hasAllPermissions(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Please grant all required permissions to receive payments.")
                .setPositiveButton("Grant", (dialog, which) -> {
                    BluetoothHelper.requestPermissions(this);
                })
                .setNegativeButton("Cancel", null)
                .show();
            return false;
        }
        
        // Check Bluetooth
        if (!BluetoothHelper.isBluetoothEnabled(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Enable Bluetooth")
                .setMessage("Bluetooth must be enabled to receive payments.")
                .setPositiveButton("Enable", (dialog, which) -> {
                    BluetoothHelper.requestEnableBluetooth(this);
                })
                .setNegativeButton("Cancel", null)
                .show();
            return false;
        }
        
        return true;
    }

    private void startMerchantMode() {
        try {
            // Bind to Nearby service
            Intent serviceIntent = new Intent(this, MerchantNearbyService.class);
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
            startService(serviceIntent);
            
            statusText.setText("Starting service...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start merchant mode", e);
            Toast.makeText(this, "Failed to start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Start advertising early (without binding service) for pre-discovery
     * This allows payers to discover merchant in background before QR is shown
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MerchantNearbyService.LocalBinder binder = (MerchantNearbyService.LocalBinder) service;
            nearbyService = binder.getService();
            isServiceBound = true;
            
            // Initialize service
            nearbyService.initialize(posId, deviceKeyManager, tokenManager);
            
            // Set callback
            nearbyService.setCallback(new MerchantNearbyService.MerchantCallback() {
                @Override
                public void onPaymentReceived(String tokenSerial, double amount) {
                    runOnUiThread(() -> {
                        // Track received tokens
                        receivedTokenIds.add(tokenSerial);
                        transactionAmount += amount;
                        
                        String shortId = tokenSerial.length() >= 8 ? tokenSerial.substring(0, 8) : tokenSerial;
                        Log.d(TAG, "← Received token: Rs " + (int)amount + " [ID: " + shortId + "...]");
                        
                        statusText.setText("Receiving payment... Rs " + (int)transactionAmount);
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(android.view.View.GONE);
                        statusText.setText("Error: " + error);
                        Toast.makeText(MerchantModeActivity.this,
                                "Error: " + error, Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Payment error: " + error);
                    });
                }

                @Override
                public void onClientConnected() {
                    runOnUiThread(() -> {
                        // Reset transaction tracking
                        receivedTokenIds.clear();
                        transactionAmount = 0;
                        
                        statusText.setText("✓ Payer connected! Waiting for payment...");
                        progressBar.setVisibility(android.view.View.VISIBLE);
                        Log.d(TAG, "Payer connected");
                    });
                }

                @Override
                public void onClientDisconnected() {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(android.view.View.GONE);
                        
                        // Show receipt if payment was received
                        if (receivedTokenIds.size() > 0) {
                            showTransactionReceipt();
                            updateBalance();
                        } else {
                            statusText.setText("Payer disconnected. Ready for next payment.");
                        }
                    });
                }
            });
            
            // Start advertising
            nearbyService.startAdvertising();
            
            // Get ephemeral public key to show via QR
            java.security.KeyPair ephemeralKey = nearbyService.getEphemeralKeyPair();
            
            if (ephemeralKey != null) {
                Intent intent = new Intent(MerchantModeActivity.this, QRDisplayActivity.class);
                intent.putExtra("pos_id", posId);
                intent.putExtra(
                        "ephemeral_public_key",
                        com.example.cbdc.util.Base64Util.encode(
                                com.example.cbdc.crypto.CryptoUtil.encodePublicKey(
                                        ephemeralKey.getPublic()
                                )
                        )
                );
                startActivity(intent);
            }
            
            statusText.setText("Advertising... Show QR to payer");
            Log.d(TAG, "Merchant advertising started");
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            nearbyService = null;
            isServiceBound = false;
        }
    };
    
    private void showTransactionReceipt() {
        StringBuilder receipt = new StringBuilder();
        receipt.append("========== PAYMENT RECEIPT ==========\n\n");
        receipt.append("Transaction Date: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
        receipt.append("POS ID: ").append(posId).append("\n\n");
        receipt.append("Amount Received: Rs ").append((int)transactionAmount).append("\n\n");
        receipt.append("Tokens Received:\n");
        
        for (String tokenSerial : receivedTokenIds) {
            String shortId = tokenSerial.length() >= 8 ? tokenSerial.substring(0, 8) : tokenSerial;
            com.example.cbdc.token.Token token = tokenManager.getTokenBySerial(tokenSerial);
            if (token != null) {
                receipt.append("• Rs ").append((int)token.getAmount()).append(" [ID: ").append(shortId).append("]\n");
            }
        }
        
        receipt.append("\n✓ Payment successful!");
        receipt.append("\n\n====================================");
        
        Log.d(TAG, receipt.toString());
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Transaction Receipt");
        builder.setMessage(receipt.toString());
        builder.setPositiveButton("OK", (dialog, which) -> {
            statusText.setText("Ready for next payment");
            dialog.dismiss();
        });
        builder.setCancelable(false);
        builder.show();
        
        Toast.makeText(this, "Payment received: Rs " + (int)transactionAmount, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == BluetoothHelper.REQUEST_BT_PERMISSIONS) {
            if (BluetoothHelper.hasAllPermissions(this)) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
                // Check Bluetooth after permissions
                if (!BluetoothHelper.isBluetoothEnabled(this)) {
                    new AlertDialog.Builder(this)
                        .setTitle("Enable Bluetooth")
                        .setMessage("Bluetooth must be enabled to receive payments.")
                        .setPositiveButton("Enable", (dialog, which) -> {
                            BluetoothHelper.requestEnableBluetooth(this);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            } else {
                Toast.makeText(this,
                        "Some permissions were denied. Please grant all permissions.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BluetoothHelper.REQUEST_ENABLE_BT) {
            if (BluetoothHelper.isBluetoothEnabled(this)) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Bluetooth is required to receive payments", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateBalance();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound && nearbyService != null) {
            nearbyService.stopAdvertising();
            unbindService(serviceConnection);
            isServiceBound = false;
        }
    }
}
