package com.example.cbdc.util;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class JsonUtil {
    
    public static JSONObject parse(String jsonString) throws JSONException {
        return new JSONObject(jsonString);
    }
    
    public static String toJson(JSONObject obj) {
        return obj.toString();
    }
    
    public static byte[] toBytes(JSONObject obj) {
        return obj.toString().getBytes(StandardCharsets.UTF_8);
    }
    
    public static JSONObject fromBytes(byte[] bytes) throws JSONException {
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    
    public static JSONObject createObject() {
        return new JSONObject();
    }
}


