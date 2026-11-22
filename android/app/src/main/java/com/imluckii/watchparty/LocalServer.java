package com.imluckii.watchparty;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One loopback origin for the whole app: http://127.0.0.1:11471
 *
 * <ul>
 *   <li>/app/** is the stremio-web bundle, read straight out of the APK.</li>
 *   <li>everything else is proxied to the Stremio engine on 127.0.0.1:11470.</li>
 * </ul>
 *
 * Serving the UI from loopback makes it the same origin as the streaming server, which is
 * what the engine requires: it only answers cross-origin requests from strem.io hosts.
 * It also keeps the page in a secure context (workers, wasm, storage) and out of Chrome's
 * private-network rules for public origins.
 */
final class LocalServer {

    private static final String TAG = "Watchparty";

    static final int PORT = 11471;
    static final String ORIGIN = "http://127.0.0.1:" + PORT;
    // The core's built-in default streaming server is repointed to this origin when the
    // bundle is packaged, so the app starts already pointed at the proxy.
    static final String APP_URL = ORIGIN + "/app/index.html";

    private static final String ENGINE_HOST = "127.0.0.1";
    private static final int ENGINE_PORT = 11470;
    private static final String ASSETS_ROOT = "web/";

    private final AssetManager assets;
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "watchparty-http");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running = true;

    LocalServer(AssetManager assets) throws IOException {
        this.assets = assets;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 128);
        Thread accept = new Thread(this::acceptLoop, "watchparty-http-accept");
        accept.setDaemon(true);
        accept.start();
    }

    void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = server.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                Log.w(TAG, "accept failed: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ request

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream(), 16384);
            BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream(), 32768);

            String requestLine = readLine(in, 8192);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            List<String[]> headers = readHeaders(in);

            if ("OPTIONS".equals(method)) {
                respondPreflight(out, headers);
                return;
            }

            String path = target;
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            path = percentDecode(path);

            if (path.equals("/app") || path.equals("/")) {
                writeSimple(out, "302 Found", "text/plain", "text".getBytes(StandardCharsets.UTF_8),
                        "Location: /app/index.html\r\n");
            } else if (path.startsWith("/app/")) {
                serveAsset(method, path.substring("/app/".length()), out);
            } else {
                proxy(method, target, headers, in, out);
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "request failed: " + e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serveAsset(String method, String path, OutputStream out) throws IOException {
        String relative = path.isEmpty() ? "index.html" : path;
        if (relative.contains("..")) {
            writeSimple(out, "404 Not Found", "text/plain", new byte[0], null);
            return;
        }

        InputStream stream;
        String diskPath = ASSETS_ROOT + relative;
        try {
            stream = assets.open(diskPath, AssetManager.ACCESS_STREAMING);
        } catch (FileNotFoundException e) {
            writeSimple(out, "404 Not Found", "text/plain",
                    ("not found: " + relative).getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        byte[] body = null;
        int length = -1;
        try {
            long declared = -1;
            try {
                declared = assets.openFd(diskPath).getLength();
            } catch (IOException ignored) {
                // fall back to buffering
            }
            if (declared < 0) {
                body = readAll(stream);
                stream.close();
                length = body.length;
            } else {
                length = (int) declared;
            }
        } catch (IOException e) {
            stream.close();
            writeSimple(out, "500 Internal Server Error", "text/plain", new byte[0], null);
            return;
        }

        String headers = "Cache-Control: " + cacheControlFor(relative) + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n";
        String status = "GET".equals(method) || "HEAD".equals(method) ? "200 OK" : "200 OK";
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append("\r\n");
        head.append("Content-Type: ").append(mimeTypeFor(relative)).append("\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append(headers);
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));

        if (!"HEAD".equals(method)) {
            if (body != null) {
                out.write(body);
            } else {
                copy(stream, out);
                stream.close();
            }
        } else {
            stream.close();
        }
    }

    // ------------------------------------------------------------------ proxy

    private void proxy(String method, String target, List<String[]> headers, InputStream clientIn,
            OutputStream clientOut) throws IOException {
        String connectionError = null;
        Socket upstream = new Socket();
        try {
            upstream.connect(new InetSocketAddress(ENGINE_HOST, ENGINE_PORT), 1500);
            upstream.setTcpNoDelay(true);

            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
            long contentLength = -1L;
            boolean chunked = false;
            for (String[] header : headers) {
                String name = header[0];
                String value = header[1];
                if (name.equalsIgnoreCase("Host")) {
                    // Keep the page's own host so any absolute URL the engine writes back
                    // (HLS playlists, subtitle tracks) points at this origin, not at 11470.
                    request.append("Host: ").append(ENGINE_HOST).append(':').append(PORT).append("\r\n");
                } else if (name.equalsIgnoreCase("Origin") || name.equalsIgnoreCase("Referer")
                        || name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Keep-Alive")) {
                    // The engine's CORS allowlist only trusts strem.io; a plain server-to-server
                    // request with no Origin is what its own clients send.
                } else {
                    if (name.equalsIgnoreCase("Content-Length")) {
                        try {
                            contentLength = Long.parseLong(value.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
                        chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
                    }
                    request.append(name).append(": ").append(value).append("\r\n");
                }
            }
            request.append("Connection: close\r\n\r\n");

            OutputStream upstreamOut = new BufferedOutputStream(upstream.getOutputStream(), 32768);
            upstreamOut.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (chunked) {
                relayChunked(clientIn, upstreamOut);
            } else if (contentLength > 0) {
                copyExactly(clientIn, upstreamOut, contentLength);
            }
            upstreamOut.flush();

            InputStream upstreamIn = new BufferedInputStream(upstream.getInputStream(), 32768);
            String statusLine = readLine(upstreamIn, 8192);
            if (statusLine == null) {
                throw new IOException("empty upstream response");
            }
            List<String[]> responseHeaders = readHeaders(upstreamIn);

            StringBuilder response = new StringBuilder();
            response.append(statusLine).append("\r\n");
            for (String[] header : responseHeaders) {
                String name = header[0];
                if (name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Keep-Alive")
                        || name.startsWith("Access-Control-")) {
                    continue;
                }
                response.append(name).append(": ").append(header[1]).append("\r\n");
            }
            response.append("Access-Control-Allow-Origin: *\r\n");
            response.append("Connection: close\r\n\r\n");
            clientOut.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            copy(upstreamIn, clientOut);
            clientOut.flush();
        } catch (IOException e) {
            connectionError = e.toString();
        } finally {
            try {
                upstream.close();
            } catch (IOException ignored) {
            }
        }

        if (connectionError != null) {
            Log.w(TAG, "engine unreachable: " + connectionError);
            byte[] body = ("{\"error\":\"engine_unreachable\"}").getBytes(StandardCharsets.UTF_8);
            writeSimple(clientOut, "502 Bad Gateway", "application/json", body,
                    "Access-Control-Allow-Origin: *\r\n");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void respondPreflight(OutputStream out, List<String[]> headers) throws IOException {
        String requested = "*";
        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase("Access-Control-Request-Headers")) {
                requested = header[1];
            }
        }
        String head = "HTTP/1.1 204 No Content\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, HEAD, POST, PUT, DELETE, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: " + requested + "\r\n"
                + "Access-Control-Max-Age: 600\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void writeSimple(OutputStream out, String status, String contentType, byte[] body,
            String extraHeaders) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        if (extraHeaders != null) {
            head.append(extraHeaders);
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String cacheControlFor(String path) {
        if (path.endsWith("index.html") || path.endsWith("manifest.json")) {
            return "no-cache, no-store, must-revalidate";
        }
        return "public, max-age=31536000, immutable";
    }

    private static String readLine(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (buffer.size() >= limit) {
                throw new IOException("line too long");
            }
            buffer.write(b);
        }
        return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static List<String[]> readHeaders(InputStream in) throws IOException {
        List<String[]> headers = new ArrayList<>();
        String line;
        while ((line = readLine(in, 16384)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            headers.add(new String[] {
                    line.substring(0, colon).trim(),
                    line.substring(colon + 1).trim()
            });
        }
        return headers;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[32768];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                throw new IOException("request body ended early");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void relayChunked(InputStream in, OutputStream out) throws IOException {
        while (true) {
            String sizeLine = readLine(in, 1024);
            if (sizeLine == null) {
                return;
            }
            out.write((sizeLine + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                return;
            }
            if (size == 0) {
                String trailer;
                while ((trailer = readLine(in, 1024)) != null) {
                    out.write((trailer + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    if (trailer.isEmpty()) {
                        break;
                    }
                }
                return;
            }
            copyExactly(in, out, size);
            out.write('\r');
            out.write('\n');
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        copy(in, out);
        return out.toByteArray();
    }

    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                try {
                    out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            out.write((byte) c);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String mimeTypeFor(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "html":
                return "text/html; charset=utf-8";
            case "js":
            case "mjs":
                return "application/javascript; charset=utf-8";
            case "css":
                return "text/css; charset=utf-8";
            case "json":
            case "map":
                return "application/json; charset=utf-8";
            case "webmanifest":
                return "application/manifest+json";
            case "wasm":
                return "application/wasm";
            case "woff2":
                return "font/woff2";
            case "woff":
                return "font/woff";
            case "ttf":
                return "font/ttf";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            case "svg":
                return "image/svg+xml";
            case "ico":
                return "image/x-icon";
            case "txt":
                return "text/plain; charset=utf-8";
            default:
                return "application/octet-stream";
        }
    }
}
