package com.igot.cb.cios.util;

public enum ContentSource {
    CORNELL,
    UPGRAD;

    public static ContentSource fromProviderName(String providerName) {
        switch (providerName) {
            case "eCornell":
                return CORNELL;
            case "upGrad":
                return UPGRAD;
            default:
                throw new RuntimeException("Unknown provider name: " + providerName);
        }
    }
}
