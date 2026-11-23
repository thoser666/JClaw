package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaAttachmentTest {

    @Test
    void defaultsForEmptyValues() {
        MediaAttachment attachment = new MediaAttachment(null, null, null, null);
        assertThat(attachment.kind()).isEqualTo(MediaAttachment.MediaKind.DOCUMENT);
        assertThat(attachment.mimeType()).isEqualTo("application/octet-stream");
        assertThat(attachment.data()).isEmpty();
        assertThat(attachment.isEmpty()).isTrue();
    }

    @Test
    void voiceNoteForOggAudio() {
        MediaAttachment ogg = new MediaAttachment(MediaAttachment.MediaKind.AUDIO, "audio/ogg", null, new byte[] {1});
        MediaAttachment mp3 = new MediaAttachment(MediaAttachment.MediaKind.AUDIO, "audio/mpeg", null, new byte[] {1});
        MediaAttachment opus = new MediaAttachment(MediaAttachment.MediaKind.AUDIO, "audio/opus", null, new byte[] {1});

        assertThat(ogg.isVoiceNote()).isTrue();
        assertThat(opus.isVoiceNote()).isTrue();
        assertThat(mp3.isVoiceNote()).isFalse();
    }

    @Test
    void blankFileNameBecomesNull() {
        MediaAttachment attachment = new MediaAttachment(MediaAttachment.MediaKind.IMAGE, "image/png", "  ", new byte[] {1});
        assertThat(attachment.fileName()).isNull();
    }
}