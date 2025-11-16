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
import java.security.spec.ECGenParameterSpec;
import java.util.Calendar;
import javax.security.auth.x500.X500Principal;

public class DeviceKeyManager {

    private static final String TAG = "DeviceKeyManager";
    private static final String KEYSTORE_ALIAS = "cbdc_device_key";
    private static final String PREFS_NAME = "cbdc_prefs";
    private static final String KEY_PUBLIC_KEY = "device_public_key";

    private final Context context;
    private final KeyStore keyStore;

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
     * Get or create hardware-backed EC keypair
     */
    public KeyPair getOrCreateDeviceKey() {
        try {
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyStore.PrivateKeyEntry entry =
                        (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null);

                return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
            } else {
                return generateDeviceKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get device key", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Generate StrongBox or TEE EC KeyPair (secp256r1)
     */
    private KeyPair generateDeviceKey() {
        try {
            KeyPairGenerator keyPairGenerator =
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");

            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 10);

            KeyGenParameterSpec.Builder builder =
                    new KeyGenParameterSpec.Builder(
                            KEYSTORE_ALIAS,
                            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
                    )
                            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setCertificateSubject(new X500Principal("CN=CBDC Device"))
                            .setCertificateSerialNumber(java.math.BigInteger.ONE)
                            .setCertificateNotBefore(start.getTime())
                            .setCertificateNotAfter(end.getTime());

            // Try StrongBox first
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setIsStrongBoxBacked(true);
                }
            } catch (Exception e) {
                Log.w(TAG, "StrongBox not available, falling back to TEE");
            }

            keyPairGenerator.initialize(builder.build());
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            savePublicKey(keyPair.getPublic());

            return keyPair;

        } catch (Exception e) {
            Log.e(TAG, "Failed to generate key", e);
            throw new RuntimeException("Key generation failed", e);
        }
    }

    /**
     * Get public key
     */
    public PublicKey getPublicKey() {
        try {
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyStore.PrivateKeyEntry entry =
                        (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null);

                return entry.getCertificate().getPublicKey();
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get public key", e);
            return null;
        }
    }

    /**
     * Get private key (secure hardware)
     */
    public PrivateKey getPrivateKey() {
        try {
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyStore.PrivateKeyEntry entry =
                        (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null);

                return entry.getPrivateKey();
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get private key", e);
            return null;
        }
    }

    /**
     * Delete key (useful for one-time tokens)
     */
    public boolean deleteDeviceKey() {
        try {
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS);
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(KEY_PUBLIC_KEY)
                        .apply();
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete key", e);
            return false;
        }
    }

    /**
     * Check if EC key exists
     */
    public boolean hasDeviceKey() {
        try {
            return keyStore.containsAlias(KEYSTORE_ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    private void savePublicKey(PublicKey publicKey) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_PUBLIC_KEY, Base64Util.encode(publicKey.getEncoded()))
                .apply();
    }
}
