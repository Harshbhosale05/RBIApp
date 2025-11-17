package com.example.cbdc.util;

/**
 * Represents a discovered merchant endpoint for caching
 * Used for instant reconnection without rediscovery
 */
public class MerchantEndpoint {
    private final String endpointId;
    private final String endpointName;
    private final String posId;
    private final long discoveredAt;
    
    public MerchantEndpoint(String endpointId, String endpointName, String posId, long discoveredAt) {
        this.endpointId = endpointId;
        this.endpointName = endpointName;
        this.posId = posId;
        this.discoveredAt = discoveredAt;
    }
    
    public String getEndpointId() {
        return endpointId;
    }
    
    public String getEndpointName() {
        return endpointName;
    }
    
    public String getPosId() {
        return posId;
    }
    
    public long getDiscoveredAt() {
        return discoveredAt;
    }
    
    /**
     * Check if cached endpoint is still valid (within 2 minutes)
     */
    public boolean isValid() {
        long ageMs = System.currentTimeMillis() - discoveredAt;
        return ageMs < 120000; // 2 minutes
    }
    
    /**
     * Extract POS ID from endpoint name format: "CBDC-Merchant-{posId}"
     */
    public static String extractPosIdFromName(String endpointName) {
        if (endpointName != null && endpointName.startsWith("CBDC-Merchant-")) {
            return endpointName.substring("CBDC-Merchant-".length());
        }
        return null;
    }
}
