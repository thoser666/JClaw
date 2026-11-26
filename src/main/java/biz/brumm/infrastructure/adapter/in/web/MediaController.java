package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.config.MediaProviderProperties;
import biz.brumm.domain.model.SttResult;
import biz.brumm.domain.model.TtsResult;
import biz.brumm.domain.port.out.MediaProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * REST-API für den Speech/Media-Provider (P4-03):
 * {@code POST /api/v1/media/tts} (Text → Audio) und
 * {@code POST /api/v1/media/stt} (Audio → Text).
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final ObjectProvider<MediaProvider> mediaProviderProvider;

    public MediaController(ObjectProvider<MediaProvider> mediaProviderProvider) {
        this.mediaProviderProvider = mediaProviderProvider;
    }

    private MediaProvider mediaProvider() {
        return mediaProviderProvider.getIfAvailable();
    }

    @PostMapping(value = "/tts", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> textToSpeech(@RequestParam("text") String text,
                                               @RequestParam(value = "voice", required = false) String voice,
                                               @RequestParam(value = "format", required = false) String format) {
        MediaProvider mediaProvider = mediaProvider();
        if (mediaProvider == null || !mediaProvider.isAvailable()) {
            return ResponseEntity.badRequest().body(
                    ("Media-Provider nicht verfügbar (jclaw.media.provider.enabled=true).").getBytes());
        }
        try {
            byte[] audio = mediaProvider.textToSpeech(text, voice, format);
            String mime = "audio/" + (format == null || format.isBlank() ? "mp3" : format);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .header("X-Media-Provider", mediaProvider.providerName())
                    .body(audio);
        } catch (MediaProvider.MediaProviderException e) {
            return ResponseEntity.badRequest().body(e.getMessage().getBytes());
        }
    }

    @PostMapping(value = "/stt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> speechToText(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "format", required = false) String format) {
        MediaProvider mediaProvider = mediaProvider();
        if (mediaProvider == null || !mediaProvider.isAvailable()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Media-Provider nicht verfügbar (jclaw.media.provider.enabled=true)."));
        }
        try {
            String text = mediaProvider.speechToText(file.getBytes(),
                    format == null || format.isBlank()
                            ? guessFormat(file.getOriginalFilename(), file.getContentType()) : format);
            return ResponseEntity.ok(Map.of("text", text,
                    "provider", mediaProvider.providerName()));
        } catch (MediaProvider.MediaProviderException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String guessFormat(String fileName, String contentType) {
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".mp3")) return "mp3";
            if (lower.endsWith(".wav")) return "wav";
            if (lower.endsWith(".opus") || lower.endsWith(".ogg")) return "opus";
        }
        if (contentType != null) {
            if (contentType.contains("mp3")) return "mp3";
            if (contentType.contains("wav")) return "wav";
            if (contentType.contains("ogg") || contentType.contains("opus")) return "opus";
        }
        return "mp3";
    }
}