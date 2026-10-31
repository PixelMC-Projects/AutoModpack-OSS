package pl.skidam.automodpack_core.oss.client;

import pl.skidam.automodpack_core.oss.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URI;
import java.time.Duration;

/** The sole client download path. No redirects, mirrors, HTTP, upstream host or fallback. */
public final class OssDownloads {
    private OssDownloads() {}
    private static HttpsURLConnection open(PackTrust trust, URI canonical, URI transport) throws IOException {
        trust.validateTransportUrl(canonical, transport);
        var connection = (HttpsURLConnection) transport.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("OSS object request refused");
            String encoding = connection.getContentEncoding();
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Encoded object refused");
            return connection;
        } catch (IOException error) { connection.disconnect(); throw error; }
    }
    public static byte[] manifest(PackTrust trust, URI canonical, URI transport) throws IOException {
        var connection = open(trust, canonical, transport);
        try (InputStream input = boundedTime(connection.getInputStream(), Duration.ofSeconds(60))) {
            byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IOException("Manifest exceeds size limit");
            return bytes;
        } finally { connection.disconnect(); }
    }
    public static void file(PackTrust trust, StagedInstaller.Stage stage, ManifestFile file, URI transport) throws IOException {
        var connection = open(trust, file.url(), transport);
        try (InputStream input = boundedTime(connection.getInputStream(), Duration.ofMinutes(10))) {
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != file.size()) throw new IOException("Object length does not match signed manifest");
            stage.accept(file, input);
        } finally { connection.disconnect(); }
    }
    private static InputStream boundedTime(InputStream source, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        return new FilterInputStream(source) {
            private void check() throws IOException {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw new IOException("Download cancelled or timed out");
            }
            @Override public int read() throws IOException { check(); return super.read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException { check(); return in.read(b, off, len); }
        };
    }
}
