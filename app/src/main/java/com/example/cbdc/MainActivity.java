package com.example.cbdc;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.TokenManager;

public class MainActivity extends AppCompatActivity {
    
    private TextView balanceText;
    private Button payerModeButton;
    private Button merchantModeButton;
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
        
        // Issue test tokens if balance is zero
        tokenManager.mintTestTokens();
        
        balanceText = findViewById(R.id.balanceText);
        payerModeButton = findViewById(R.id.payerModeButton);
        merchantModeButton = findViewById(R.id.merchantModeButton);
        
        payerModeButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PayerModeActivity.class);
            startActivity(intent);
        });
        
        merchantModeButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, MerchantModeActivity.class);
            startActivity(intent);
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateBalance();
    }
    
    private void updateBalance() {
        double balance = tokenManager.getBalance();
        balanceText.setText(getString(R.string.balance, String.format("%.2f", balance)));
    }
}

