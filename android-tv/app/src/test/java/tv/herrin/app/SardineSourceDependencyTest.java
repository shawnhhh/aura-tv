package tv.herrin.app;

import static org.junit.Assert.*;

import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;
import com.thegrizzlylabs.sardineandroid.impl.SardineException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

/** Actual public API, XML dependency and HTTP behavior of the source-built v0.9. */
public class SardineSourceDependencyTest {
    private Sardine client() {
        return new OkHttpSardine(new OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS).build());
    }

    @Test public void sourceBuildRetainsPropfindAndXmlModelBinding() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(207)
                .addHeader("Content-Type", "application/xml")
                .setBody("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">"
                    + "<d:response><d:href>/media/film.mp4</d:href><d:propstat><d:prop>"
                    + "<d:displayname>film.mp4</d:displayname><d:getcontentlength>4</d:getcontentlength>"
                    + "<d:getcontenttype>video/mp4</d:getcontenttype><d:resourcetype/>"
                    + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
                    + "</d:response></d:multistatus>"));
            List<DavResource> files = client().list(server.url("/media/").toString());
            assertEquals(1, files.size());
            assertEquals("/media/film.mp4", files.get(0).getPath());
            assertEquals(Long.valueOf(4), files.get(0).getContentLength());
            assertFalse(files.get(0).isDirectory());
            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("PROPFIND", request.getMethod());
            assertEquals("1", request.getHeader("Depth"));
            assertTrue(request.getBody().readUtf8().contains("allprop"));
        }
    }

    @Test public void sourceBuildRetainsMediaHeadersBytesAndFailureStatus() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-3/4").setBody("test"));
            server.enqueue(new MockResponse().setResponseCode(403));
            Sardine sardine = client();
            sardine.setCredentials("fixture", "fixture-password", true);
            try (InputStream stream = sardine.get(server.url("/film.mp4").toString(),
                    Collections.singletonMap("Range", "bytes=0-3"))) {
                assertEquals("test", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("bytes=0-3", request.getHeader("Range"));
            assertEquals(Credentials.basic("fixture", "fixture-password"), request.getHeader("Authorization"));
            try {
                sardine.get(server.url("/denied").toString());
                fail("403 must remain a failure");
            } catch (SardineException expected) {
                assertEquals(403, expected.getStatusCode());
            }
        }
    }
}
