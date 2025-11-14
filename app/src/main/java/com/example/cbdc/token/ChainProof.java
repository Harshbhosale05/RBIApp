package com.example.cbdc.token;

import com.example.cbdc.util.JsonUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ChainProof {
    private List<JSONObject> transferChain;
    private JSONObject acceptReceipt;
    
    public ChainProof() {
        this.transferChain = new ArrayList<>();
    }
    
    public void addTransfer(JSONObject transfer) {
        transferChain.add(transfer);
    }
    
    public void setAcceptReceipt(JSONObject acceptReceipt) {
        this.acceptReceipt = acceptReceipt;
    }
    
    public List<JSONObject> getTransferChain() {
        return transferChain;
    }
    
    public JSONObject getAcceptReceipt() {
        return acceptReceipt;
    }
    
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            JSONArray chainArray = new JSONArray();
            for (JSONObject transfer : transferChain) {
                chainArray.put(transfer);
            }
            json.put("transfer_chain", chainArray);
            if (acceptReceipt != null) {
                json.put("accept_receipt", acceptReceipt);
            }
        } catch (JSONException e) {
            // Ignore
        }
        return json;
    }
    
    public static ChainProof fromJson(JSONObject json) {
        ChainProof proof = new ChainProof();
        try {
            if (json.has("transfer_chain")) {
                JSONArray chainArray = json.getJSONArray("transfer_chain");
                for (int i = 0; i < chainArray.length(); i++) {
                    proof.addTransfer(chainArray.getJSONObject(i));
                }
            }
            if (json.has("accept_receipt")) {
                proof.setAcceptReceipt(json.getJSONObject("accept_receipt"));
            }
        } catch (JSONException e) {
            // Ignore
        }
        return proof;
    }
}

