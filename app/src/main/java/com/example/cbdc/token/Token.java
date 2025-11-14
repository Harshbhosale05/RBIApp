package com.example.cbdc.token;

import com.example.cbdc.util.Base64Util;
import com.example.cbdc.util.JsonUtil;
import org.json.JSONException;
import org.json.JSONObject;

public class Token {
    private JSONObject tokenData;
    private ChainProof chainProof;
    
    public Token(JSONObject tokenData) {
        this.tokenData = tokenData;
    }
    
    public String getSerial() {
        try {
            return tokenData.getString("serial");
        } catch (JSONException e) {
            return null;
        }
    }
    
    public double getAmount() {
        try {
            return tokenData.getDouble("amount");
        } catch (JSONException e) {
            return 0.0;
        }
    }
    
    public String getIssuerId() {
        try {
            return tokenData.getString("issuer_id");
        } catch (JSONException e) {
            return null;
        }
    }
    
    public long getTimestamp() {
        try {
            return tokenData.getLong("timestamp");
        } catch (JSONException e) {
            return 0;
        }
    }
    
    public ChainProof getChainProof() {
        return chainProof;
    }
    
    public void setChainProof(ChainProof chainProof) {
        this.chainProof = chainProof;
    }
    
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("token", tokenData);
            if (chainProof != null) {
                json.put("chain_proof", chainProof.toJson());
            }
        } catch (JSONException e) {
            // Ignore
        }
        return json;
    }
    
    public JSONObject getTokenData() {
        return tokenData;
    }
}

