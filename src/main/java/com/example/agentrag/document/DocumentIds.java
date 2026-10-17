package com.example.agentrag.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class DocumentIds {
    private DocumentIds() { }

    static String chunkId(String documentId, int version, int index) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest((documentId + ":" + version + ":" + index).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value).substring(0, 32);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
