package com.igot.cb.cios.util;

public enum ContentSource {
    CORNELL,
    UPGRAD;

    public static ContentSource fromProviderName(String providerName) {
        switch (providerName.toLowerCase()) {
            case "cornell":
                return CORNELL;
            case "upgrad":
                return UPGRAD;
            default:
                throw new RuntimeException("Unknown provider name: " + providerName);
        }
    }
}
