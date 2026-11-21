package com.tridev.familyhub.feature.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Voice notes use the shared file transport so older Firebase rules can accept AAC. */
final class ChatVoiceAttachment {
    private static final Pattern TYPE =
            Pattern.compile("audio/mp4;voice-duration=([1-9][0-9]{0,8});voice-wave=([0-9a-f]{32})");

    final int duration;
    final String waveform;

    private ChatVoiceAttachment(int duration, String waveform) {
        this.duration = duration;
        this.waveform = waveform;
    }

    static String contentType(int duration, String waveform) {
        return "audio/mp4;voice-duration=" + duration + ";voice-wave=" + waveform;
    }

    static ChatVoiceAttachment read(String contentType) {
        if (contentType == null) return null;
        Matcher value = TYPE.matcher(contentType);
        return value.matches() ? new ChatVoiceAttachment(Integer.parseInt(value.group(1)), value.group(2)) : null;
    }
}
