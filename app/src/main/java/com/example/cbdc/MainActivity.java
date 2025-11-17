package com.example.cbdc;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.TokenManager;
import com.example.cbdc.util.BluetoothHelper;
import com.google.android.material.card.MaterialCardView;

public class MainActivity extends AppCompatActivity {
    
    private TextView balanceText;
    private MaterialCardView payerModeCard;
    private MaterialCardView merchantModeCard;
    private TokenManager tokenManager;
    private DeviceKeyManager deviceKeyManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize managers
        deviceKeyManager = new DeviceKeyManager(this);
        tokenManager = new TokenManager(this, deviceKeyManager);
        
        // Ensure device key exists
        if (!deviceKeyManager.hasDeviceKey()) {
            deviceKeyManager.getOrCreateDeviceKey();
        }
        
            // Ensure the wallet is initialized with 2500 Rs
            tokenManager.ensureInitialWallet();
        
        balanceText = findViewById(R.id.balanceText);
        payerModeCard = findViewById(R.id.payerModeCard);
        merchantModeCard = findViewById(R.id.merchantModeCard);
        
        payerModeCard.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PayerModeActivity.class);
            startActivity(intent);
        });
        
        merchantModeCard.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, MerchantModeActivity.class);
            startActivity(intent);
        });
        
        // Check and request permissions on startup
        checkAndRequestPermissions();
    }
    
    private void checkAndRequestPermissions() {
        if (!BluetoothHelper.hasAllPermissions(this)) {
            showPermissionDialog();
        } else if (!BluetoothHelper.isBluetoothEnabled(this)) {
            requestBluetoothEnable();
        }
    }
    
    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app requires Bluetooth, Location, Camera, and WiFi permissions for offline payments. Please grant all permissions.")
            .setPositiveButton("Grant", (dialog, which) -> {
                BluetoothHelper.requestPermissions(this);
            })
            .setNegativeButton("Cancel", (dialog, which) -> {
                Toast.makeText(this, "Permissions are required for the app to function", Toast.LENGTH_LONG).show();
            })
            .setCancelable(false)
            .show();
    }
    
    private void requestBluetoothEnable() {
        new AlertDialog.Builder(this)
            .setTitle("Enable Bluetooth")
            .setMessage("Bluetooth is required for offline payments. Please enable Bluetooth.")
            .setPositiveButton("Enable", (dialog, which) -> {
                BluetoothHelper.requestEnableBluetooth(this);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BluetoothHelper.REQUEST_BT_PERMISSIONS) {
            if (BluetoothHelper.hasAllPermissions(this)) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
                // Check Bluetooth after permissions
                if (!BluetoothHelper.isBluetoothEnabled(this)) {
                    requestBluetoothEnable();
                }
            } else {
                Toast.makeText(this, "Some permissions were denied. App may not function properly.", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "Bluetooth is required for offline payments", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateBalance();
    }
    
    private void updateBalance() {
        double balance = tokenManager.getBalance();
        java.util.List<com.example.cbdc.token.Token> tokens = tokenManager.getAllTokens();
        
        // Update main balance
        balanceText.setText("₹ " + String.format("%.0f", balance));
        
        // Update token breakdown
        TextView tokenBreakdownText = findViewById(R.id.tokenBreakdownText);
        if (tokenBreakdownText != null) {
            StringBuilder breakdown = new StringBuilder();
            java.util.Map<Integer, Integer> denominationCount = new java.util.HashMap<>();
            
            for (com.example.cbdc.token.Token token : tokens) {
                int denom = (int) token.getAmount();
                denominationCount.put(denom, denominationCount.getOrDefault(denom, 0) + 1);
            }
            
            java.util.List<Integer> denoms = new java.util.ArrayList<>(denominationCount.keySet());
            java.util.Collections.sort(denoms, java.util.Collections.reverseOrder());
            
            for (int i = 0; i < Math.min(3, denoms.size()); i++) {
                int denom = denoms.get(i);
                int count = denominationCount.get(denom);
                if (i > 0) breakdown.append("  ");
                breakdown.append("₹").append(denom).append("×").append(count);
            }
            
            tokenBreakdownText.setText(breakdown.toString());
        }
    }
}

