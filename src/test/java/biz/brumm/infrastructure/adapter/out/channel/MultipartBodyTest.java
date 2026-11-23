package biz.brumm.infrastructure.adapter.out.channel;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultipartBodyTest {

    @Test
    void buildContainsBoundaryAndParts() {
        String boundary = MultipartBody.newBoundary();
        byte[] body = MultipartBody.build(boundary,
                Map.of("chat_id", "42"),
                List.of(MultipartBody.Part.file("voice", "note.ogg", "audio/ogg", "abc".getBytes())));

        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertThat(text).contains("--" + boundary);
        assertThat(text).contains("Content-Disposition: form-data; name=\"chat_id\"");
        assertThat(text).contains("42");
        assertThat(text).contains("name=\"voice\"; filename=\"note.ogg\"");
        assertThat(text).contains("Content-Type: audio/ogg");
        assertThat(text).contains("--" + boundary + "--");
    }

    @Test
    void buildWithoutFieldsOnlyFile() {
        String boundary = "b1";
        byte[] body = MultipartBody.build(boundary, Map.of(),
                List.of(MultipartBody.Part.file("document", "a.txt", "text/plain", new byte[] {1, 2, 3})));

        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("name=\"chat_id\"");
        assertThat(text).contains("filename=\"a.txt\"");
        assertThat(text).contains("--b1--");
    }

    @Test
    void contentTypeIncludesBoundary() {
        assertThat(MultipartBody.contentType("b2")).isEqualTo("multipart/form-data; boundary=b2");
    }

    @Test
    void newBoundaryIsUnique() {
        assertThat(MultipartBody.newBoundary()).isNotEqualTo(MultipartBody.newBoundary());
    }
}