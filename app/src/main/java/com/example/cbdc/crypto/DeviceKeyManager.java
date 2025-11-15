package com.example.cbdc.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import com.example.cbdc.util.Base64Util;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Calendar;
import javax.security.auth.x500.X500Principal;

public class DeviceKeyManager {
    private static final String TAG = "DeviceKeyManager";
    private static final String KEYSTORE_ALIAS = "cbdc_device_key";
    private static final String PREFS_NAME = "cbdc_prefs";
    private static final String KEY_PUBLIC_KEY = "device_public_key";
    private static final String KEY_KEY_ID = "device_key_id";
    
    private final Context context;
    private KeyStore keyStore;
    
    public DeviceKeyManager(Context context) {
        this.context = context;
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize KeyStore", e);
            throw new RuntimeException("KeyStore initialization failed", e);
        }
    }
    
    /**
     * Generate or retrieve hardware-backed device key
     */
    public KeyPair getOrCreateDeviceKey() {
        try {
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                // Key exists, retrieve it
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(
                    KEYSTORE_ALIAS, null);
                PrivateKey privateKey = entry.getPrivateKey();
                PublicKey publicKey = entry.getCertificate().getPublicKey();
                return new KeyPair(publicKey, privateKey);
            } else {
                // Generate new key
                return generateDeviceKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get device key", e);
            throw new RuntimeException("Device key retrieval failed", e);
        }
    }
    
    /**
     * Generate hardware-backed key with StrongBox if available
     */
    private KeyPair generateDeviceKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 10);
            
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY |
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
//                .setAlgorithmParameterSpec(new android.security.keystore.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeyValidityStart(start.getTime())
                .setKeyValidityEnd(end.getTime())
                .setCertificateSubject(new X500Principal("CN=CBDC Device"))
                .setCertificateSerialNumber(java.math.BigInteger.ONE)
                .setCertificateNotBefore(start.getTime())
                .setCertificateNotAfter(end.getTime());
            
            // Try to use StrongBox if available
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setIsStrongBoxBacked(true);
                }
            } catch (Exception e) {
                Log.w(TAG, "StrongBox not available, using regular hardware-backed key", e);
            }
            
            keyPairGenerator.initialize(builder.build());
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            
            // Store public key in SharedPreferences for easy access
            savePublicKey(keyPair.getPublic());
            
            return keyPair;
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate device key", e);
            throw new RuntimeException("Device key generation failed", e);
        }
    }
    
    /**
     * Get public key (from KeyStore or cache)
     */
    public PublicKey getPublicKey() {
        try {
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(
                    KEYSTORE_ALIAS, null);
                return entry.getCertificate().getPublicKey();
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get public key", e);
            return null;
        }
    }
    
    /**
     * Get private key
     */
    public PrivateKey getPrivateKey() {
        try {
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(
                    KEYSTORE_ALIAS, null);
                return entry.getPrivateKey();
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get private key", e);
            return null;
        }
    }
    
    /**
     * Delete device key (for atomic consumption)
     */
    public boolean deleteDeviceKey() {
        try {
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS);
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().remove(KEY_PUBLIC_KEY).apply();
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete device key", e);
            return false;
        }
    }
    
    /**
     * Check if device key exists
     */
    public boolean hasDeviceKey() {
        try {
            return keyStore.containsAlias(KEYSTORE_ALIAS);
        } catch (Exception e) {
            Log.e(TAG, "Failed to check device key", e);
            return false;
        }
    }
    
    private void savePublicKey(PublicKey publicKey) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encoded = Base64Util.encode(publicKey.getEncoded());
        prefs.edit().putString(KEY_PUBLIC_KEY, encoded).apply();
    }
}

