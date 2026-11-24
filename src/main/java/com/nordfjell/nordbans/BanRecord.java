package com.nordfjell.nordbans;

record BanRecord(String playerName, long expiresAtMillis, String reason, String actor) {
    boolean active(long nowMillis) {
        return expiresAtMillis > nowMillis;
    }
}
