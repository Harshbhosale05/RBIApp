package com.example.cbdc.token;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.JsonUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TokenManager {
    private static final String TAG = "TokenManager";
    private static final String PREFS_NAME = "cbdc_tokens";
    private static final String KEY_TOKENS = "tokens";
    private static final String KEY_COUNTER = "consume_counter";
    
    private final Context context;
    private final DeviceKeyManager deviceKeyManager;
    private SharedPreferences prefs;
    
    public TokenManager(Context context, DeviceKeyManager deviceKeyManager) {
        this.context = context;
        this.deviceKeyManager = deviceKeyManager;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * Issue a new token (simulated locally)
     */
    public Token issueToken(double amount, String issuerId) {
        try {
            KeyPair deviceKey = deviceKeyManager.getOrCreateDeviceKey();
            
            String tokenSerial = UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();
            
            JSONObject tokenData = new JSONObject();
            tokenData.put("serial", tokenSerial);
            tokenData.put("amount", amount);
            tokenData.put("issuer_id", issuerId);
            tokenData.put("timestamp", timestamp);
            tokenData.put("device_public_key", Base64Util.encode(
                CryptoUtil.encodePublicKey(deviceKey.getPublic())));
            
            // Sign token with device key
            String tokenJson = tokenData.toString();
            byte[] signature = CryptoUtil.sign(deviceKey.getPrivate(), tokenJson.getBytes());
            tokenData.put("signature", Base64Util.encode(signature));
            
            Token token = new Token(tokenData);
            saveToken(token);
            
            Log.d(TAG, "Issued token: " + tokenSerial);
            return token;
        } catch (Exception e) {
            Log.e(TAG, "Failed to issue token", e);
            throw new RuntimeException("Token issuance failed", e);
        }
    }
    
    /**
     * Get all tokens
     */
    public List<Token> getAllTokens() {
        List<Token> tokens = new ArrayList<>();
        try {
            String tokensJson = prefs.getString(KEY_TOKENS, "[]");
            JSONArray tokenArray = new JSONArray(tokensJson);
            
            for (int i = 0; i < tokenArray.length(); i++) {
                JSONObject tokenObj = tokenArray.getJSONObject(i);
                tokens.add(new Token(tokenObj));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load tokens", e);
        }
        return tokens;
    }
    
    /**
     * Get token by serial
     */
    public Token getTokenBySerial(String serial) {
        List<Token> tokens = getAllTokens();
        for (Token token : tokens) {
            if (token.getSerial().equals(serial)) {
                return token;
            }
        }
        return null;
    }
    
    /**
     * Save token to storage
     */
    public void saveToken(Token token) {
        try {
            List<Token> tokens = getAllTokens();
            // Remove if exists
            tokens.removeIf(t -> t.getSerial().equals(token.getSerial()));
            tokens.add(token);
            
            JSONArray tokenArray = new JSONArray();
            for (Token t : tokens) {
                tokenArray.put(t.toJson());
            }
            
            prefs.edit().putString(KEY_TOKENS, tokenArray.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save token", e);
        }
    }
    
    /**
     * Delete token (after consumption)
     */
    public boolean deleteToken(String serial) {
        try {
            List<Token> tokens = getAllTokens();
            boolean removed = tokens.removeIf(t -> t.getSerial().equals(serial));
            
            if (removed) {
                JSONArray tokenArray = new JSONArray();
                for (Token t : tokens) {
                    tokenArray.put(t.toJson());
                }
                prefs.edit().putString(KEY_TOKENS, tokenArray.toString()).apply();
            }
            
            return removed;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete token", e);
            return false;
        }
    }
    
    /**
     * Get next consume counter
     */
    public long getNextCounter() {
        long counter = prefs.getLong(KEY_COUNTER, 0);
        prefs.edit().putLong(KEY_COUNTER, counter + 1).apply();
        return counter + 1;
    }
    
    /**
     * Get total balance
     */
    public double getBalance() {
        List<Token> tokens = getAllTokens();
        double balance = 0.0;
        for (Token token : tokens) {
            balance += token.getAmount();
        }
        return balance;
    }
    
    /**
     * Add received token (from transfer)
     */
    public void addReceivedToken(JSONObject tokenData, ChainProof chainProof) {
        try {
            Token token = new Token(tokenData);
            token.setChainProof(chainProof);
            saveToken(token);
            Log.d(TAG, "Added received token: " + token.getSerial());
        } catch (Exception e) {
            Log.e(TAG, "Failed to add received token", e);
        }
    }
}

