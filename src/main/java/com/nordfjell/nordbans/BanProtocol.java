package com.nordfjell.nordbans;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class BanProtocol {
    static final String CHANNEL = "nordfjell:bans";
    static final String KICK_PREFIX = "NORD_BAN_V1|";

    private BanProtocol() {
    }

    static String kickMessage(long expiresAtMillis, String reason) {
        String encodedReason = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(reason.getBytes(StandardCharsets.UTF_8));
        return KICK_PREFIX + expiresAtMillis + "|" + encodedReason;
    }
}
