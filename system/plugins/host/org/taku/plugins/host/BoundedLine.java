package org.taku.plugins.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class BoundedLine {
    private BoundedLine() { }

    // The caller supplies a buffered stream and controls the read deadline.
    static String read(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != '\n') {
            if (bytes.size() >= maxBytes) throw new IOException("JSON line exceeds " + maxBytes + " bytes");
            bytes.write(next);
        }
        if (next == -1 && bytes.size() == 0) return null;
        byte[] line = bytes.toByteArray();
        int length = line.length;
        if (length > 0 && line[length - 1] == '\r') length--;
        return new String(line, 0, length, StandardCharsets.UTF_8);
    }
}
