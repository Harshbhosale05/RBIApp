package com.example.cbdc.crypto;

import android.util.Log;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.HexUtil;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public class CryptoUtil {
    private static final String TAG = "CryptoUtil";
    private static final String HKDF_ALGORITHM = "HmacSHA256";
    private static final int AES_KEY_SIZE = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    
    // For X25519, we use EC with curve25519 if available, otherwise fallback to EC P-256
    private static final String EC_ALGORITHM = "EC";
    private static final String EC_CURVE = "secp256r1"; // Fallback curve
    
    /**
     * Generate X25519 key pair (using EC as fallback)
     */
    public static KeyPair generateX25519KeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(EC_ALGORITHM);
            ECGenParameterSpec ecSpec = new ECGenParameterSpec(EC_CURVE);
            keyGen.initialize(ecSpec);
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate X25519 key pair", e);
            throw new RuntimeException("Key generation failed", e);
        }
    }
    
    /**
     * Perform ECDH key agreement
     */
    public static byte[] performECDH(PrivateKey privateKey, PublicKey publicKey) {
        try {
            KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
            keyAgreement.init(privateKey);
            keyAgreement.doPhase(publicKey, true);
            return keyAgreement.generateSecret();
        } catch (Exception e) {
            Log.e(TAG, "ECDH key agreement failed", e);
            throw new RuntimeException("ECDH failed", e);
        }
    }
    
    /**
     * Derive session key using HKDF
     */
    public static SecretKey deriveSessionKey(byte[] sharedSecret, byte[] salt, byte[] info) {
        try {
            // HKDF-Extract
            Mac hmac = Mac.getInstance(HKDF_ALGORITHM);
            SecretKeySpec prkKey = new SecretKeySpec(salt != null ? salt : new byte[32], HKDF_ALGORITHM);
            hmac.init(prkKey);
            byte[] prk = hmac.doFinal(sharedSecret);
            
            // HKDF-Expand
            hmac.init(new SecretKeySpec(prk, HKDF_ALGORITHM));
            ByteBuffer buffer = ByteBuffer.allocate(32 + info.length + 1);
            buffer.put(info);
            buffer.put((byte) 0x01);
            byte[] okm = hmac.doFinal(buffer.array());
            
            return new SecretKeySpec(Arrays.copyOf(okm, 32), "AES");
        } catch (Exception e) {
            Log.e(TAG, "HKDF derivation failed", e);
            throw new RuntimeException("HKDF failed", e);
        }
    }
    
    /**
     * Encrypt using AES-GCM (ChaCha20-Poly1305 equivalent)
     */
    public static byte[] encryptAEAD(SecretKey key, byte[] plaintext, byte[] associatedData) {
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            byte[] iv = generateNonce();
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            if (associatedData != null) {
                cipher.updateAAD(associatedData);
            }
            
            byte[] ciphertext = cipher.doFinal(plaintext);
            
            // Prepend IV to ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return buffer.array();
        } catch (Exception e) {
            Log.e(TAG, "AEAD encryption failed", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    /**
     * Decrypt using AES-GCM
     */
    public static byte[] decryptAEAD(SecretKey key, byte[] ciphertextWithIv, byte[] associatedData) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(ciphertextWithIv);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            if (associatedData != null) {
                cipher.updateAAD(associatedData);
            }
            
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            Log.e(TAG, "AEAD decryption failed", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }
    
    /**
     * Generate a random nonce
     */
    public static byte[] generateNonce() {
        SecureRandom random = new SecureRandom();
        byte[] nonce = new byte[GCM_IV_LENGTH];
        random.nextBytes(nonce);
        return nonce;
    }
    
    /**
     * Sign data using Ed25519 (using EC as fallback)
     */
    public static byte[] sign(PrivateKey privateKey, byte[] data) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            Log.e(TAG, "Signing failed", e);
            throw new RuntimeException("Signing failed", e);
        }
    }
    
    /**
     * Verify signature
     */
    public static boolean verify(PublicKey publicKey, byte[] data, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            Log.e(TAG, "Verification failed", e);
            return false;
        }
    }
    
    /**
     * Encode public key to bytes
     */
    public static byte[] encodePublicKey(PublicKey publicKey) {
        return publicKey.getEncoded();
    }
    
    /**
     * Decode public key from bytes
     */
    public static PublicKey decodePublicKey(byte[] keyBytes) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode public key", e);
            throw new RuntimeException("Key decoding failed", e);
        }
    }
}

