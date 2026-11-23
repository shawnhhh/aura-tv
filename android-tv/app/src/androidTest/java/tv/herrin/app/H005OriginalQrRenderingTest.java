package tv.herrin.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.Test;
import tv.herrin.app.spiderdialog.QRCode;

/**
 * H-005 visual QR regression for the actual relocated FongMi renderer.
 *
 * Uses a synthetic, unprivileged URL: this checks scanability of the bitmap,
 * NOT provider authorization, session validity or a real WANOU login.
 */
public final class H005OriginalQrRenderingTest {
    @Test
    public void originalProviderQrBitmapCanBeDecodedWithoutChangingItsPayload() throws Exception {
        String payload = "https://example.org/authorize?ticket=H005-QR-2026&node=wanou";
        Bitmap rendered = QRCode.getBitmap(payload, 220, 4);
        assertNotNull("Original SpiderDialog must be able to draw a QR bitmap", rendered);
        assertTrue("QR image must have positive dimensions", rendered.getWidth() > 0);
        assertEquals("QR renderer must produce a square image",
                rendered.getWidth(), rendered.getHeight());

        int width = rendered.getWidth();
        int height = rendered.getHeight();
        int[] pixels = new int[width * height];
        rendered.getPixels(pixels, 0, width, 0, 0, width, height);
        BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(width, height, pixels)));

        assertEquals("The rendered QR must retain the exact provider-supplied data",
                payload, new MultiFormatReader().decode(image).getText());
    }
}
