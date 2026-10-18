package com.deploymenthost.jetbrains;

import com.intellij.openapi.vfs.VirtualFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

final class ReadOnlyTransferService {
    private static final Path CONFIG_PATH = Path.of(".skia", "read-only-url.txt");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_REDIRECTS = 10;
    private static final String BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    ReadOnlySource resolve(VirtualFile file) throws IOException {
        Path itemPath = Path.of(file.getPath()).toAbsolutePath().normalize();
        Path currentPath = file.isDirectory() ? itemPath : itemPath.getParent();

        while (currentPath != null) {
            Path configFile = currentPath.resolve(CONFIG_PATH);

            if (Files.isRegularFile(configFile)) {
                String configuredUrl = Files.readString(configFile, StandardCharsets.UTF_8).trim();
                if (configuredUrl.isBlank()) {
                    throw new IllegalStateException(CONFIG_PATH + " is empty.");
                }

                URI baseUri;
                try {
                    baseUri = URI.create(configuredUrl);
                } catch (IllegalArgumentException error) {
                    throw new IllegalStateException(
                        CONFIG_PATH + " must contain a valid HTTP or HTTPS URL."
                    );
                }

                String scheme = baseUri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    throw new IllegalStateException(CONFIG_PATH + " must contain an HTTP or HTTPS URL.");
                }

                Path relativePath = currentPath.relativize(itemPath);
                if (relativePath.startsWith("..") || relativePath.isAbsolute()) {
                    throw new IllegalStateException("Could not make a project-relative file path.");
                }

                String relativeText = relativePath.toString();
                URI encodedRelativeUri;
                try {
                    encodedRelativeUri = new URI(null, null, relativeText.replace('\\', '/'), null);
                } catch (URISyntaxException error) {
                    throw new IllegalStateException("Could not make a website URL for " + relativeText + ".");
                }

                String baseText = baseUri.toString();
                URI downloadUri = URI.create(baseText.endsWith("/") ? baseText : baseText + "/")
                    .resolve(encodedRelativeUri);

                return new ReadOnlySource(currentPath, relativeText, downloadUri);
            }

            currentPath = currentPath.getParent();
        }

        return null;
    }

    byte[] download(ReadOnlySource source, Consumer<String> reportProgress)
        throws IOException, InterruptedException {
        return download(source.url(), source.relativePath(), reportProgress, 0);
    }

    private byte[] download(
        URI url,
        String itemLabel,
        Consumer<String> reportProgress,
        int redirectCount
    ) throws IOException, InterruptedException {
        if (redirectCount > MAX_REDIRECTS) {
            throw new IllegalStateException("HTTP download failed: too many redirects.");
        }

        HttpRequest request = HttpRequest.newBuilder(url)
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .GET()
            .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException error) {
            throw new IllegalStateException("HTTP download failed: request timed out.");
        } catch (IOException error) {
            throw new IllegalStateException("HTTP download failed: " + error.getMessage());
        }

        int statusCode = response.statusCode();
        try (InputStream body = response.body()) {
            if (statusCode >= 300 && statusCode < 400) {
                String location = response.headers().firstValue("location").orElse("");
                if (location.isBlank()) {
                    throw new IllegalStateException(
                        "HTTP download failed: " + statusCode + " redirect without a location."
                    );
                }
                return download(url.resolve(location), itemLabel, reportProgress, redirectCount + 1);
            }

            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException(
                    "HTTP download failed: " + statusCode + statusMessage(statusCode) + "."
                );
            }

            long totalBytes = response.headers().firstValueAsLong("content-length").orElse(0L);
            long downloadedBytes = 0L;
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int read;

            try {
                while ((read = body.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    downloadedBytes += read;

                    String amount = totalBytes > 0
                        ? Math.min(100L, Math.round((downloadedBytes * 100.0) / totalBytes)) + "%"
                        : formatBytes(downloadedBytes);
                    reportProgress.accept("Downloading via HTTP: " + itemLabel + " " + amount);
                }
            } catch (IOException error) {
                throw new IllegalStateException("HTTP download failed: server closed the response early.");
            }

            if (totalBytes > 0 && downloadedBytes < totalBytes) {
                throw new IllegalStateException("HTTP download failed: server closed the response early.");
            }

            return output.toByteArray();
        }
    }

    private String statusMessage(int statusCode) {
        return switch (statusCode) {
            case 400 -> " Bad Request";
            case 401 -> " Unauthorized";
            case 403 -> " Forbidden";
            case 404 -> " Not Found";
            case 405 -> " Method Not Allowed";
            case 408 -> " Request Timeout";
            case 409 -> " Conflict";
            case 410 -> " Gone";
            case 413 -> " Content Too Large";
            case 414 -> " URI Too Long";
            case 415 -> " Unsupported Media Type";
            case 429 -> " Too Many Requests";
            case 500 -> " Internal Server Error";
            case 501 -> " Not Implemented";
            case 502 -> " Bad Gateway";
            case 503 -> " Service Unavailable";
            case 504 -> " Gateway Timeout";
            default -> "";
        };
    }

    private String formatBytes(long value) {
        if (value == 0) {
            return "0 B";
        }

        String[] units = {"B", "KB", "MB", "GB"};
        double size = value;
        int unitIndex = 0;

        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        return size >= 10
            ? Math.round(size) + " " + units[unitIndex]
            : String.format(java.util.Locale.ROOT, "%.1f %s", size, units[unitIndex]);
    }

    record ReadOnlySource(Path rootPath, String relativePath, URI url) {
    }
}
