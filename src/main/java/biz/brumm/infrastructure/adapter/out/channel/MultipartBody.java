package biz.brumm.infrastructure.adapter.out.channel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Baut einen {@code multipart/form-data}-Body (RFC 7578) für Medien-Uploads.
 * <p>
 * Reines JDK – keine zusätzliche Abhängigkeit. Felder werden als Text-Parts,
 * Binärdaten als Datei-Part serialisiert.
 */
public final class MultipartBody {

    private MultipartBody() {
    }

    /**
     * Ein Text- oder Datei-Part.
     *
     * @param name        Formularfeld-Name
     * @param fileName    Dateiname (nur bei Datei-Parts)
     * @param contentType Content-Type (nur bei Datei-Parts)
     * @param value       Text bzw. Rohdaten
     */
    public record Part(String name, String fileName, String contentType, byte[] value) {

        public static Part text(String name, String value) {
            return new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8));
        }

        public static Part file(String name, String fileName, String contentType, byte[] data) {
            return new Part(name, fileName, contentType, data == null ? new byte[0] : data);
        }
    }

    /**
     * @return zufällige Boundary, die im Body nicht vorkommen sollte
     */
    public static String newBoundary() {
        return "----JClawBoundary" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Serialisiert Felder und Datei-Parts zu einem multipart-Body.
     *
     * @param boundary Boundary-String (z. B. aus {@link #newBoundary()})
     * @param fields   Textfelder
     * @param files    Datei-Parts
     * @return vollständiger Body inkl. schließender Boundary
     */
    public static byte[] build(String boundary, Map<String, String> fields, List<Part> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (fields != null) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                writeLine(out, "--" + boundary);
                writeLine(out, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"");
                writeLine(out, "");
                out.writeBytes(field.getValue().getBytes(StandardCharsets.UTF_8));
                writeLine(out, "");
            }
        }
        if (files != null) {
            for (Part file : files) {
                writeLine(out, "--" + boundary);
                String disposition = "Content-Disposition: form-data; name=\"" + file.name() + "\"";
                if (file.fileName() != null) {
                    disposition += "; filename=\"" + file.fileName() + "\"";
                }
                writeLine(out, disposition);
                if (file.contentType() != null) {
                    writeLine(out, "Content-Type: " + file.contentType());
                }
                writeLine(out, "");
                out.writeBytes(file.value());
                writeLine(out, "");
            }
        }
        writeLine(out, "--" + boundary + "--");
        writeLine(out, "");
        return out.toByteArray();
    }

    /**
     * @return Content-Type-Headerwert inkl. Boundary
     */
    public static String contentType(String boundary) {
        return "multipart/form-data; boundary=" + boundary;
    }

    private static void writeLine(ByteArrayOutputStream out, String line) {
        out.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(new byte[] {'\r', '\n'});
    }
}