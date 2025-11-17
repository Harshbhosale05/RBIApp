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
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class PayerModeActivity extends AppCompatActivity {
    private static final String TAG = "PayerModeActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int CAMERA_REQUEST_CODE = 200;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

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
            List<String> missingPermissions = getMissingPermissions();
            if (missingPermissions.isEmpty()) {
                startQRScan();
            } else {
                ActivityCompat.requestPermissions(this, missingPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        });
    }

    private void updateBalance() {
        double balance = tokenManager.getBalance();
        balanceText.setText(getString(R.string.balance, String.format("%.2f", balance)));
    }

    private List<String> getMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
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
        }
    }

    private void processQRCode(String qrDataString) {
        Log.d(TAG, "Processing QR Code data: " + qrDataString);
        try {
            JSONObject qrData = QrParser.parseQRString(qrDataString);
            if (qrData == null) {
                showError("Invalid QR code format. Expected JSON.");
                Log.e(TAG, "QR Parser returned null. Raw data: " + qrDataString);
                return;
            }

            if (!QrParser.verifyQRSignature(qrData)) {
                showError("QR signature verification failed. The QR code may be tampered with or from an untrusted source.");
                return;
            }

            String posId = QrParser.extractPosId(qrData);
            if (posId == null) {
                showError("Failed to extract POS ID from QR code.");
                return;
            }

            currentQRData = qrData;
            currentPosId = posId;

            showAmountDialog();

        } catch (Exception e) {
            Log.e(TAG, "Failed to process QR code", e);
            showError("An unexpected error occurred while processing the QR code: " + e.getMessage());
        }
    }

    private void showAmountDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Amount");

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String amountString = input.getText().toString();
            try {
                double amount = Double.parseDouble(amountString);
                if (amount <= 0) {
                    showError("Invalid amount");
                    return;
                }

                List<Token> tokensToSend = tokenManager.getTokensForAmount(amount);
                if (tokensToSend == null || tokensToSend.isEmpty()) {
                    showError("Insufficient balance or could not make exact amount");
                    return;
                }

                statusText.setText("Discovering merchant...");
                progressBar.setVisibility(android.view.View.VISIBLE);
                connectAndSendPayment(tokensToSend, currentPosId);

            } catch (NumberFormatException e) {
                showError("Invalid amount format");
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void connectAndSendPayment(List<Token> tokens, String posId) {
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

                        // Delete tokens after successful transfer
                        for (Token token : tokens) {
                            tokenManager.deleteToken(token.getSerial());
                        }
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
                    });
                }

                @Override
                public void onDisconnected() {
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected");
                    });
                }
            });

        nearbyClient.startDiscovery();

        // Send payment - it will be queued until key exchange completes
        for (Token token : tokens) {
            nearbyClient.sendTokenTransfer(token, posId);
        }
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
            List<String> missingPermissions = getMissingPermissions();
            if (missingPermissions.isEmpty()) {
                startQRScan();
            } else {
                List<String> readableNames = new ArrayList<>();
                for(String perm : missingPermissions) {
                    readableNames.add(perm.substring(perm.lastIndexOf('.') + 1));
                }
                String missingPermissionsText = String.join(", ", readableNames);
                Log.e(TAG, "User denied permissions: " + missingPermissionsText);
                Toast.makeText(this, "Permissions denied: " + missingPermissionsText + ". Please grant them in app settings.", Toast.LENGTH_LONG).show();
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
