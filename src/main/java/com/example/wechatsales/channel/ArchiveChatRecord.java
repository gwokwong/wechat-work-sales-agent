package com.example.wechatsales.channel;

/** getchatdata 返回的单条会话存档记录（加密态，字段与企微官方一致） */
public record ArchiveChatRecord(
        long seq,
        String msgid,
        int publickeyVer,
        String encryptRandomKey,
        String encryptChatMsg
) {
}
