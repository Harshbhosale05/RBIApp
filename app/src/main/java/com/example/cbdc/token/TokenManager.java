package com.example.cbdc.token;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.example.cbdc.crypto.CryptoUtil;
import com.example.cbdc.crypto.DeviceKeyManager;
import com.example.cbdc.util.Base64Util;
import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class TokenManager {
    private static final String TAG = "TokenManager";
    private static final String PREFS_NAME = "cbdc_tokens";
    private static final String KEY_TOKENS = "tokens";
    private static final String KEY_COUNTER = "consume_counter";
    private static final String KEY_TOKENS_MINTED = "tokens_minted";
    private static final double INITIAL_BALANCE_TARGET = 2500.0;
    private static final double BALANCE_TOLERANCE = 0.01;

    private final Context context;
    private final DeviceKeyManager deviceKeyManager;
    private SharedPreferences prefs;

    public TokenManager(Context context, DeviceKeyManager deviceKeyManager) {
        this.context = context;
        this.deviceKeyManager = deviceKeyManager;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

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

    public List<Token> getTokensForAmount(double amount) {
        Log.d(TAG, "Attempting to find tokens for amount: " + amount);
        List<Token> allTokens = getAllTokens();
        Log.d(TAG, "Available tokens: " + allTokens.stream().map(Token::getAmount).collect(Collectors.toList()));

        Collections.sort(allTokens, (t1, t2) -> Double.compare(t2.getAmount(), t1.getAmount()));

        List<Token> selectedTokens = new ArrayList<>();
        double currentTotal = 0.0;

        for (Token token : allTokens) {
            if (currentTotal + token.getAmount() <= amount) {
                selectedTokens.add(token);
                currentTotal += token.getAmount();
            }
        }

        if (Math.abs(currentTotal - amount) > 0.001) {
            Log.e(TAG, "Could not make exact amount. Wanted: " + amount + ", but could only make: " + currentTotal);
            Log.d(TAG, "Selected tokens for failed attempt: " + selectedTokens.stream().map(Token::getAmount).collect(Collectors.toList()));
            return null; // Cannot make exact amount
        }

        Log.d(TAG, "Successfully found tokens for amount: " + amount);
        Log.d(TAG, "Selected tokens: " + selectedTokens.stream().map(Token::getAmount).collect(Collectors.toList()));
        return selectedTokens;
    }

    public void mintTestTokens() {
        mintTestTokens(false);
    }

    private void mintTestTokens(boolean force) {
        boolean tokensMinted = prefs.getBoolean(KEY_TOKENS_MINTED, false);
        if (!force && tokensMinted) {
            return; // Only mint once unless forced
        }

        if (force) {
            Log.w(TAG, "Forcing re-mint of test tokens to restore initial balance");
            prefs.edit()
                .putString(KEY_TOKENS, "[]")
                .putLong(KEY_COUNTER, 0)
                .apply();
        }

        // Generate 2500 Rs with specific denominations (Total = 2500)
        int[] denominationCounts = {
            500, 3,
            100, 5,
            50, 5,
            20, 10,
            10, 2,
            5, 2,
            2, 5,
            1, 10
        };

        for (int i = 0; i < denominationCounts.length; i += 2) {
            int denomination = denominationCounts[i];
            int count = denominationCounts[i + 1];
            for (int j = 0; j < count; j++) {
                issueToken(denomination, "RBI_ISSUER");
            }
        }

        Log.d(TAG, "Initial wallet of Rs 2500 ensured successfully");
        prefs.edit().putBoolean(KEY_TOKENS_MINTED, true).apply();
    }

    public void ensureInitialWallet() {
        double balance = getBalance();
        if (Math.abs(balance - INITIAL_BALANCE_TARGET) <= BALANCE_TOLERANCE) {
            return;
        }

        Log.w(TAG, "Wallet balance was " + balance + " Rs. Resetting to 2500 Rs test wallet.");
        mintTestTokens(true);
    }

    public Token getTokenBySerial(String serial) {
        List<Token> tokens = getAllTokens();
        for (Token token : tokens) {
            if (token.getSerial().equals(serial)) {
                return token;
            }
        }
        return null;
    }

    public void saveToken(Token token) {
        try {
            List<Token> tokens = getAllTokens();
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

    public long getNextCounter() {
        long counter = prefs.getLong(KEY_COUNTER, 0);
        prefs.edit().putLong(KEY_COUNTER, counter + 1).apply();
        return counter + 1;
    }

    public double getBalance() {
        List<Token> tokens = getAllTokens();
        double balance = 0.0;
        for (Token token : tokens) {
            balance += token.getAmount();
        }
        return balance;
    }

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
