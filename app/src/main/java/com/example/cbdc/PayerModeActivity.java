package com.example.cbdc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
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
import com.example.cbdc.util.BluetoothHelper;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PayerModeActivity extends AppCompatActivity {
    private static final String TAG = "PayerModeActivity";
    private static final int CAMERA_REQUEST_CODE = 200;

    private TextView balanceText;
    private TextView statusText;
    private Button scanQRButton;
    private ProgressBar progressBar;

    private DeviceKeyManager deviceKeyManager;
    private TokenManager tokenManager;
    private PayerNearbyClient nearbyClient;
    private Handler handler;
    private String transactionId;
    private String posId;
    private boolean isConnected = false;
    private boolean isConnectionEstablishing = false;

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
            if (!checkPermissionsAndBluetooth()) {
                return;
            }
            startQRScan();
        });
    }

    private boolean checkPermissionsAndBluetooth() {
        if (!BluetoothHelper.hasAllPermissions(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Please grant all required permissions to scan QR code and make payments.")
                .setPositiveButton("Grant", (dialog, which) -> {
                    BluetoothHelper.requestPermissions(this);
                })
                .setNegativeButton("Cancel", null)
                .show();
            return false;
        }

        if (!BluetoothHelper.isBluetoothEnabled(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Enable Bluetooth")
                .setMessage("Bluetooth must be enabled for offline payments.")
                .setPositiveButton("Enable", (dialog, which) -> {
                    BluetoothHelper.requestEnableBluetooth(this);
                })
                .setNegativeButton("Cancel", null)
                .show();
            return false;
        }

        return true;
    }

    private void updateBalance() {
        double balance = tokenManager.getBalance();
        balanceText.setText(getString(R.string.balance, String.format("%.2f", balance)));
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
            } else {
                showError("QR scan failed: No data received.");
            }
        } else if (requestCode == BluetoothHelper.REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Bluetooth is required for payments", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void processQRCode(String qrDataString) {
        Log.d(TAG, "=== Processing QR Code ===");
        Log.d(TAG, "QR Data Length: " + (qrDataString != null ? qrDataString.length() : "null"));
        Log.d(TAG, "QR Data (first 200 chars): " + (qrDataString != null ? qrDataString.substring(0, Math.min(200, qrDataString.length())) : "null"));
        
        try {
            if (qrDataString == null || qrDataString.isEmpty()) {
                showError("QR scan returned empty data.");
                return;
            }
            
            JSONObject qrData = QrParser.parseQRString(qrDataString);
            if (qrData == null) {
                showError("Invalid QR code format.\n\nExpected JSON starting with '{'");
                Log.e(TAG, "QR Parser returned null.");
                return;
            }
            
            Log.d(TAG, "✓ QR parsed successfully");
            Log.d(TAG, "QR Data keys: " + qrData.keys().toString());

            if (!QrParser.verifyQRSignature(qrData)) {
                showError("QR signature verification failed. The QR code may be tampered with.");
                return;
            }

            posId = QrParser.extractPosId(qrData);
            if (posId == null) {
                showError("Failed to extract POS ID from QR code.");
                return;
            }
            
            Log.d(TAG, "✓ QR verified. POS ID: " + posId);

            statusText.setText("QR Scanned! Discovering merchant...");
            progressBar.setVisibility(android.view.View.VISIBLE);
            isConnectionEstablishing = true;

            initializeNearbyClient();
            nearbyClient.startDiscovery();

            handler.postDelayed(() -> {
                if (isConnectionEstablishing && !isConnected) {
                    showError("Connection timeout. Please ensure merchant device is nearby and try again.");
                    if (nearbyClient != null) {
                        nearbyClient.disconnect();
                    }
                    isConnectionEstablishing = false;
                }
            }, 15000); // 15 second timeout
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing QR code", e);
            showError("Error processing QR code: " + e.getMessage());
        }
    }

    private void initializeNearbyClient() {
        nearbyClient = new PayerNearbyClient(this, deviceKeyManager, tokenManager,
            new PayerNearbyClient.PayerCallback() {

                @Override
                public void onEndpointDiscovered(String endpointId, String endpointName, String serviceId) {
                    Log.d(TAG, "Endpoint discovered: " + endpointName + " [" + endpointId + "]");
                    if (posId != null && posId.equals(endpointName)) {
                        Log.d(TAG, "Found target merchant! Connecting...");
                        nearbyClient.connectToEndpoint(endpointId, endpointName);
                    }
                }

                @Override
                public void onEndpointLost(String endpointId) {
                    Log.d(TAG, "Endpoint lost: " + endpointId);
                }

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
                        statusText.setText("✓ Payment accepted!");

                        Toast.makeText(PayerModeActivity.this, "Payment successful!", Toast.LENGTH_SHORT).show();

                        updateBalance();
                        handler.postDelayed(() -> finish(), 2000);
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        isConnectionEstablishing = false;
                        isConnected = false;
                        progressBar.setVisibility(android.view.View.GONE);
                        showError("Payment error: " + error);
                        Log.e(TAG, "Payment error: " + error);
                    });
                }

                @Override
                public void onConnected() {
                    runOnUiThread(() -> {
                        isConnected = true;
                        isConnectionEstablishing = false;
                        statusText.setText("✓ Connection established! Enter amount to send:");
                        progressBar.setVisibility(android.view.View.GONE);

                        // Show amount dialog immediately after connection
                        handler.postDelayed(() -> showAmountDialog(), 500);
                    });
                }

                @Override
                public void onDisconnected() {
                    runOnUiThread(() -> {
                        isConnected = false;
                        if (!isFinishing()) {
                            statusText.setText("Disconnected from merchant");
                        }
                    });
                }
            });
    }

    private void showAmountDialog() {
        if (!isConnected) {
            showError("Not connected to merchant. Please scan QR code again.");
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Amount to Send");

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Amount in Rs");
        builder.setView(input);

        builder.setPositiveButton("Send", (dialog, which) -> {
            String amountString = input.getText().toString();
            try {
                double amount = Double.parseDouble(amountString);
                if (amount <= 0) {
                    showError("Please enter a valid amount greater than 0");
                    return;
                }

                List<Token> tokensToSend = tokenManager.getTokensForAmount(amount);
                if (tokensToSend == null || tokensToSend.isEmpty()) {
                    showError("Insufficient balance or cannot make exact amount with available tokens");
                    return;
                }

                String tokenInfo = "Sending: " + tokensToSend.stream()
                    .map(t -> "Rs " + (int)t.getAmount())
                    .collect(Collectors.joining(", "));
                Log.d(TAG, tokenInfo);

                statusText.setText("Sending payment of Rs " + (int)amount + "...");
                progressBar.setVisibility(android.view.View.VISIBLE);
                sendPayment(tokensToSend);

            } catch (NumberFormatException e) {
                showError("Invalid amount format. Please enter a number.");
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            if (nearbyClient != null) {
                nearbyClient.disconnect();
            }
            finish();
        });

        builder.setCancelable(false);
        builder.show();
    }

    private void sendPayment(List<Token> tokens) {
        if (nearbyClient == null || !isConnected) {
            showError("Not connected to merchant");
            return;
        }

        for (Token token : tokens) {
            nearbyClient.sendTokenTransfer(token, posId);
        }

        for (Token token : tokens) {
            tokenManager.deleteToken(token.getSerial());
            Log.d(TAG, "Deleted token from wallet: " + token.getSerial() + " (Rs " + token.getAmount() + ")");
        }
    }

    private void showError(String error) {
        statusText.setText("Error: " + error);
        progressBar.setVisibility(android.view.View.GONE);
        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BluetoothHelper.REQUEST_BT_PERMISSIONS) {
            if (BluetoothHelper.hasAllPermissions(this)) {
                Toast.makeText(this, "Permissions granted. You can now scan QR codes.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some permissions were denied. Please grant all permissions in app settings.", Toast.LENGTH_LONG).show();
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
