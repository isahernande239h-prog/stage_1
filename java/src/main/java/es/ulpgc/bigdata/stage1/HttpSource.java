package es.ulpgc.bigdata.stage1;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;

/** Downloads from gutenberg.org or from a mirror with the same URLs. */
public class HttpSource implements Source {
    static final String USER_AGENT = "ulpgc-bigdata-stage1/1.0 (class project)";
    static final int ATTEMPTS = 3;

    private final String base;
    private final HttpClient client;

    public HttpSource(String base) {
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.base = base;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String url(int bookId) {
        return base + "/cache/epub/" + bookId + "/pg" + bookId + ".txt";
    }

    @Override
    public String fetch(int bookId) throws IOException {
        return Source.decode(fetchRaw(bookId));
    }

    public byte[] fetchRaw(int bookId) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(bookId)))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                // no compression so Content-Length can be compared with what we got
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        String lastError = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            HttpResponse<byte[]> resp;
            try {
                if (attempt > 0) {
                    Thread.sleep(1000L << (attempt - 1));  // 1 s and then 2 s
                }
                resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                lastError = e.toString();
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownloadException("interrupted");
            }
            int status = resp.statusCode();
            if (status == 404) {
                throw new BookNotFoundException(bookId);
            }
            if (status == 429 || status >= 500) {
                lastError = "HTTP " + status;
                continue;
            }
            if (status != 200) {
                throw new DownloadException("HTTP " + status);
            }
            OptionalLong expected = resp.headers().firstValueAsLong("Content-Length");
            if (expected.isPresent() && expected.getAsLong() != resp.body().length) {
                lastError = "incomplete";
                continue;
            }
            return resp.body();
        }
        throw new DownloadException(lastError);
    }
}
