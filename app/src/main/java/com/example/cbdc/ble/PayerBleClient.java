package com.example.cbdc.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.example.cbdc.crypto.ConsumeFlow;
import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.token.Token;
import com.example.cbdc.token.TokenManager;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.JsonUtil;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.UUID;
import javax.crypto.SecretKey;

public class PayerBleClient extends Context {
    private static final String TAG = "PayerBleClient";
    private static final long CONNECTION_TIMEOUT_MS = 10000; // 10 seconds

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt gatt;
    private PayerBleClientCallback callback;
    private DeviceKeyManager deviceKeyManager;
    private TokenManager tokenManager;

    // Ephemeral keys for session
    private KeyPair ephemeralKeyPair;
    private SecretKey sessionKey;
    private PublicKey merchantPublicKey;
    private BluetoothGattCharacteristic characteristic;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable connectionTimeoutRunnable;

    @Override
    public AssetManager getAssets() {
        return null;
    }

    @Override
    public Resources getResources() {
        return null;
    }

    @Override
    public PackageManager getPackageManager() {
        return null;
    }

    @Override
    public ContentResolver getContentResolver() {
        return null;
    }

    @Override
    public Looper getMainLooper() {
        return null;
    }

    @Override
    public Context getApplicationContext() {
        return null;
    }

    @Override
    public void setTheme(int resid) {

    }

    @Override
    public Resources.Theme getTheme() {
        return null;
    }

    @Override
    public ClassLoader getClassLoader() {
        return null;
    }

    @Override
    public String getPackageName() {
        return "";
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return null;
    }

    @Override
    public String getPackageResourcePath() {
        return "";
    }

    @Override
    public String getPackageCodePath() {
        return "";
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return false;
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        return false;
    }

    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return null;
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        return null;
    }

    @Override
    public boolean deleteFile(String name) {
        return false;
    }

    @Override
    public File getFileStreamPath(String name) {
        return null;
    }

    @Override
    public File getDataDir() {
        return null;
    }

    @Override
    public File getFilesDir() {
        return null;
    }

    @Override
    public File getNoBackupFilesDir() {
        return null;
    }

    @Nullable
    @Override
    public File getExternalFilesDir(@Nullable String type) {
        return null;
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        return new File[0];
    }

    @Override
    public File getObbDir() {
        return null;
    }

    @Override
    public File[] getObbDirs() {
        return new File[0];
    }

    @Override
    public File getCacheDir() {
        return null;
    }

    @Override
    public File getCodeCacheDir() {
        return null;
    }

    @Nullable
    @Override
    public File getExternalCacheDir() {
        return null;
    }

    @Override
    public File[] getExternalCacheDirs() {
        return new File[0];
    }

    @Override
    public File[] getExternalMediaDirs() {
        return new File[0];
    }

    @Override
    public String[] fileList() {
        return new String[0];
    }

    @Override
    public File getDir(String name, int mode) {
        return null;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return null;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, @Nullable DatabaseErrorHandler errorHandler) {
        return null;
    }

    @Override
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        return false;
    }

    @Override
    public boolean deleteDatabase(String name) {
        return false;
    }

    @Override
    public File getDatabasePath(String name) {
        return null;
    }

    @Override
    public String[] databaseList() {
        return new String[0];
    }

    @Override
    public Drawable getWallpaper() {
        return null;
    }

    @Override
    public Drawable peekWallpaper() {
        return null;
    }

    @Override
    public int getWallpaperDesiredMinimumWidth() {
        return 0;
    }

    @Override
    public int getWallpaperDesiredMinimumHeight() {
        return 0;
    }

    @Override
    public void setWallpaper(Bitmap bitmap) throws IOException {

    }

    @Override
    public void setWallpaper(InputStream data) throws IOException {

    }

    @Override
    public void clearWallpaper() throws IOException {

    }

    @Override
    public void startActivity(Intent intent) {

    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {

    }

    @Override
    public void startActivities(Intent[] intents) {

    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {

    }

    @Override
    public void startIntentSender(IntentSender intent, @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags) throws IntentSender.SendIntentException {

    }

    @Override
    public void startIntentSender(IntentSender intent, @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, @Nullable Bundle options) throws IntentSender.SendIntentException {

    }

    @Override
    public void sendBroadcast(Intent intent) {

    }

    @Override
    public void sendBroadcast(Intent intent, @Nullable String receiverPermission) {

    }

    @Override
    public void sendOrderedBroadcast(Intent intent, @Nullable String receiverPermission) {

    }

    @Override
    public void sendOrderedBroadcast(@NonNull Intent intent, @Nullable String receiverPermission, @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {

    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {

    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, @Nullable String receiverPermission) {

    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user, @Nullable String receiverPermission, BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {

    }

    @Override
    public void sendStickyBroadcast(Intent intent) {

    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent, BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {

    }

    @Override
    public void removeStickyBroadcast(Intent intent) {

    }

    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {

    }

    @Override
    public void sendStickyOrderedBroadcastAsUser(Intent intent, UserHandle user, BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {

    }

    @Override
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {

    }

    @Nullable
    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
        return null;
    }

    @Nullable
    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return null;
    }

    @Nullable
    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, @Nullable String broadcastPermission, @Nullable Handler scheduler) {
        return null;
    }

    @Nullable
    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, @Nullable String broadcastPermission, @Nullable Handler scheduler, int flags) {
        return null;
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {

    }

    @Nullable
    @Override
    public ComponentName startService(Intent service) {
        return null;
    }

    @Nullable
    @Override
    public ComponentName startForegroundService(Intent service) {
        return null;
    }

    @Override
    public boolean stopService(Intent service) {
        return false;
    }

    @Override
    public boolean bindService(@NonNull Intent service, @NonNull ServiceConnection conn, int flags) {
        return false;
    }

    @Override
    public void unbindService(@NonNull ServiceConnection conn) {

    }

    @Override
    public boolean startInstrumentation(@NonNull ComponentName className, @Nullable String profileFile, @Nullable Bundle arguments) {
        return false;
    }

    @Override
    public Object getSystemService(@NonNull String name) {
        return null;
    }

    @Nullable
    @Override
    public String getSystemServiceName(@NonNull Class<?> serviceClass) {
        return "";
    }

    @Override
    public int checkPermission(@NonNull String permission, int pid, int uid) {
        return 0;
    }

    @Override
    public int checkCallingPermission(@NonNull String permission) {
        return 0;
    }

    @Override
    public int checkCallingOrSelfPermission(@NonNull String permission) {
        return 0;
    }

    @Override
    public int checkSelfPermission(@NonNull String permission) {
        return 0;
    }

    @Override
    public void enforcePermission(@NonNull String permission, int pid, int uid, @Nullable String message) {

    }

    @Override
    public void enforceCallingPermission(@NonNull String permission, @Nullable String message) {

    }

    @Override
    public void enforceCallingOrSelfPermission(@NonNull String permission, @Nullable String message) {

    }

    @Override
    public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {

    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {

    }

    @Override
    public void revokeUriPermission(String toPackage, Uri uri, int modeFlags) {

    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        return 0;
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        return 0;
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        return 0;
    }

    @Override
    public int checkUriPermission(@Nullable Uri uri, @Nullable String readPermission, @Nullable String writePermission, int pid, int uid, int modeFlags) {
        return 0;
    }

    @Override
    public void enforceUriPermission(Uri uri, int pid, int uid, int modeFlags, String message) {

    }

    @Override
    public void enforceCallingUriPermission(Uri uri, int modeFlags, String message) {

    }

    @Override
    public void enforceCallingOrSelfUriPermission(Uri uri, int modeFlags, String message) {

    }

    @Override
    public void enforceUriPermission(@Nullable Uri uri, @Nullable String readPermission, @Nullable String writePermission, int pid, int uid, int modeFlags, @Nullable String message) {

    }

    @Override
    public Context createPackageContext(String packageName, int flags) throws PackageManager.NameNotFoundException {
        return null;
    }

    @Override
    public Context createContextForSplit(String splitName) throws PackageManager.NameNotFoundException {
        return null;
    }

    @Override
    public Context createConfigurationContext(@NonNull Configuration overrideConfiguration) {
        return null;
    }

    @Override
    public Context createDisplayContext(@NonNull Display display) {
        return null;
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        return null;
    }

    @Override
    public boolean isDeviceProtectedStorage() {
        return false;
    }

    private enum State {
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }
    private volatile State currentState = State.IDLE;


    public interface PayerBleClientCallback {
        void onPaymentSent();
        void onPaymentAccepted(JSONObject acceptReceipt);
        void onError(String error);
        void onConnected();
        void onDisconnected();
    }

    public PayerBleClient(Context context, DeviceKeyManager deviceKeyManager,
                          TokenManager tokenManager, PayerBleClientCallback callback) {
        this.context = context;
        this.deviceKeyManager = deviceKeyManager;
        this.tokenManager = tokenManager;
        this.callback = callback;

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        // Generate ephemeral key pair
        ephemeralKeyPair = CryptoUtil.generateX25519KeyPair();
    }

    /**
     * Cleans up all resources used by the PayerBleClient.
     * Call this when the client is no longer needed to prevent memory leaks.
     */
    public void close() {
        Log.d(TAG, "Closing PayerBleClient and cleaning up resources.");
        disconnect();
        if (connectionTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(connectionTimeoutRunnable);
        }
        context = null;
        callback = null;
        tokenManager = null;
        deviceKeyManager = null;
        ephemeralKeyPair = null;
        currentState = State.IDLE;
    }

    public void connect(String deviceAddress) {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter not initialized");
            if (callback != null) {
                callback.onError("Bluetooth is not available.");
            }
            return;
        }

        if (currentState == State.CONNECTING || currentState == State.CONNECTED) {
            Log.w(TAG, "Already connecting or connected.");
            return;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            currentState = State.CONNECTING;

            // Set up a connection timeout
            connectionTimeoutRunnable = () -> {
                Log.e(TAG, "Connection timed out");
                disconnect(); // This will trigger onDisconnected callback
                if (callback != null) {
                    callback.onError("Connection timed out.");
                }
            };
            timeoutHandler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT_MS);

            Log.d(TAG, "Connecting to device: " + deviceAddress);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            gatt = device.connectGatt(context, false, gattCallback);

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid Bluetooth address.", e);
            currentState = State.IDLE;
            if (callback != null) {
                callback.onError("Invalid device address: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initiate connection", e);
            currentState = State.IDLE;
            if (callback != null) {
                callback.onError("Connection failed: " + e.getMessage());
            }
        }
    }

    public void disconnect() {
        if (gatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        sessionKey = null;
        merchantPublicKey = null;
        currentState = State.DISCONNECTED;
        Log.d(TAG, "Disconnected and resources cleared.");
    }

    public void sendTokenTransfer(Token token, String posId) {
        if (currentState != State.CONNECTED) {
            Log.e(TAG, "Cannot send token, not in a connected state.");
            if (callback != null) {
                callback.onError("Not connected to the device.");
            }
            return;
        }
        try {
            if (sessionKey == null || characteristic == null) {
                throw new IllegalStateException("Session not established or characteristic not found");
            }

            KeyPair deviceKey = deviceKeyManager.getOrCreateDeviceKey();

            // Create transfer message
            JSONObject transfer = new JSONObject();
            transfer.put("type", "TOKEN_TRANSFER");
            transfer.put("token_serial", token.getSerial());
            transfer.put("pos_id", posId);
            transfer.put("timestamp", System.currentTimeMillis());
            transfer.put("payer_public_key", Base64Util.encode(
                    CryptoUtil.encodePublicKey(deviceKey.getPublic())));

            // Sign transfer
            String transferData = transfer.toString();
            byte[] signature = CryptoUtil.sign(deviceKey.getPrivate(), transferData.getBytes());
            transfer.put("signature", Base64Util.encode(signature));

            // Create complete message
            JSONObject message = new JSONObject();
            message.put("type", "TOKEN_TRANSFER");
            message.put("token", token.getTokenData());
            message.put("transfer", transfer);

            // Encrypt and send
            byte[] plaintext = JsonUtil.toBytes(message);
            byte[] encrypted = CryptoUtil.encryptAEAD(sessionKey, plaintext, null);

            characteristic.setValue(encrypted);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            if (!gatt.writeCharacteristic(characteristic)) {
                Log.e(TAG, "Failed to initiate characteristic write.");
                if (callback != null) {
                    callback.onError("Failed to send payment: Write operation failed.");
                }
            } else {
                Log.d(TAG, "Token transfer write initiated.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to send token transfer", e);
            if (callback != null) {
                callback.onError("Failed to send payment: " + e.getMessage());
            }
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            // Always remove the timeout callback on any state change
            if (connectionTimeoutRunnable != null) {
                timeoutHandler.removeCallbacks(connectionTimeoutRunnable);
                connectionTimeoutRunnable = null;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                currentState = State.CONNECTED;
                Log.d(TAG, "Connected to GATT server. Discovering services...");
                if (callback != null) {
                    callback.onConnected();
                }
                gatt.discoverServices();

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.");
                // Ensure disconnect resources are cleaned up
                if (currentState != State.DISCONNECTED) {
                    disconnect(); // Use our method to centralize cleanup
                    if (callback != null) {
                        callback.onDisconnected();
                    }
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(BleUtils.CBDC_SERVICE_UUID);
                if (service != null) {
                    characteristic = service.getCharacteristic(BleUtils.CBDC_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        // Enable notifications to receive data from the merchant
                        gatt.setCharacteristicNotification(characteristic, true);

                        // Key exchange starts by reading the merchant's ephemeral public key
                        Log.d(TAG, "Service and characteristic found. Reading merchant public key...");
                        gatt.readCharacteristic(characteristic);
                    } else {
                        Log.e(TAG, "CBDC characteristic not found.");
                        callback.onError("Payment characteristic not found.");
                    }
                } else {
                    Log.e(TAG, "CBDC service not found.");
                    callback.onError("Payment service not found.");
                }
            } else {
                Log.e(TAG, "onServicesDiscovered received error status: " + status);
                callback.onError("Service discovery failed.");
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] merchantKeyBytes = characteristic.getValue();
                if (merchantKeyBytes != null && merchantKeyBytes.length > 0) {
                    try {
                        Log.d(TAG, "Received merchant public key. Performing key exchange.");
                        merchantPublicKey = CryptoUtil.decodePublicKey(merchantKeyBytes);

                        // Perform ECDH to get a shared secret
                        byte[] sharedSecret = CryptoUtil.performECDH(
                                ephemeralKeyPair.getPrivate(), merchantPublicKey);

                        // Derive a unique session key from the shared secret
                        byte[] salt = new byte[32]; // For production, a random salt is better
                        byte[] info = "CBDC_SESSION".getBytes();
                        sessionKey = CryptoUtil.deriveSessionKey(sharedSecret, salt, info);

                        Log.d(TAG, "Session key established. Sending our public key.");

                        // Send our ephemeral public key to the merchant to complete the exchange
                        byte[] ourPublicKey = CryptoUtil.encodePublicKey(ephemeralKeyPair.getPublic());
                        characteristic.setValue(ourPublicKey);
                        gatt.writeCharacteristic(characteristic);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to establish session", e);
                        if (callback != null) {
                            callback.onError("Session establishment failed: " + e.getMessage());
                        }
                    }
                }
            } else {
                Log.e(TAG, "onCharacteristicRead failed with status: " + status);
                if (callback != null) {
                    callback.onError("Failed to read from device.");
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] encryptedData = characteristic.getValue();
            if (encryptedData != null && sessionKey != null) {
                try {
                    Log.d(TAG, "Received notification from merchant.");
                    byte[] plaintext = CryptoUtil.decryptAEAD(sessionKey, encryptedData, null);
                    JSONObject message = JsonUtil.fromBytes(plaintext);

                    String messageType = message.getString("type");
                    if ("ACCEPT".equals(messageType)) {
                        Log.d(TAG, "Payment ACCEPT message received.");
                        if (callback != null) {
                            callback.onPaymentAccepted(message);
                        }
                    } else {
                        Log.w(TAG, "Received unknown message type: " + messageType);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to process notification", e);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic write successful.");
                // Note: The logic to differentiate key-exchange write vs payment write is implicit here.
                // A more advanced state machine could handle this explicitly.
                // Assuming any write success after session key is established is a payment sent event.
                if (sessionKey != null) {
                    if (callback != null) {
                        callback.onPaymentSent();
                    }
                }
            } else {
                Log.e(TAG, "onCharacteristicWrite failed with status: " + status);
                if (callback != null) {
                    callback.onError("Failed to write to device.");
                }
            }
        }
    };
}
