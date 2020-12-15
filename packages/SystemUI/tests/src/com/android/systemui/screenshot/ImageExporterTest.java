/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.screenshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static java.nio.charset.StandardCharsets.US_ASCII;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.testing.AndroidTestingRunner;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.systemui.SysuiTestCase;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@MediumTest // file I/O
public class ImageExporterTest extends SysuiTestCase {

    /** Executes directly in the caller's thread */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final byte[] EXIF_FILE_TAG = "Exif\u0000\u0000".getBytes(US_ASCII);

    private static final ZonedDateTime CAPTURE_TIME =
            ZonedDateTime.of(LocalDateTime.of(2020, 12, 15, 13, 15), ZoneId.of("EST"));

    @Test
    public void testImageFilename() {
        assertEquals("image file name", "Screenshot_20201215-131500.png",
                ImageExporter.createFilename(CAPTURE_TIME, CompressFormat.PNG));
    }

    @Test
    public void testUpdateExifAttributes_timeZoneUTC() throws IOException {
        ExifInterface exifInterface = new ExifInterface(new ByteArrayInputStream(EXIF_FILE_TAG),
                ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY);

        ImageExporter.updateExifAttributes(exifInterface, 100, 100,
                ZonedDateTime.of(LocalDateTime.of(2020, 12, 15, 18, 15), ZoneId.of("UTC")));

        assertEquals("Exif " + ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00",
                exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        assertEquals("Exif " + ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00",
                exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED));
    }

    @Test
    public void testImageExport() throws ExecutionException, InterruptedException, IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        ContentResolver contentResolver = context.getContentResolver();
        ImageExporter exporter = new ImageExporter(contentResolver);

        Bitmap original = createCheckerBitmap(10, 10, 10);

        ListenableFuture<Uri> direct = exporter.export(DIRECT_EXECUTOR, original, CAPTURE_TIME);
        assertTrue("future should be done", direct.isDone());
        assertFalse("future should not be canceled", direct.isCancelled());
        Uri result = direct.get();

        assertNotNull("Uri should not be null", result);
        Bitmap decoded = null;
        try (InputStream in = contentResolver.openInputStream(result)) {
            decoded = BitmapFactory.decodeStream(in);
            assertNotNull("decoded image should not be null", decoded);
            assertTrue("original and decoded image should be identical", original.sameAs(decoded));

            try (ParcelFileDescriptor pfd = contentResolver.openFile(result, "r", null)) {
                assertNotNull(pfd);
                ExifInterface exifInterface = new ExifInterface(pfd.getFileDescriptor());

                assertEquals("Exif " + ExifInterface.TAG_SOFTWARE, "Android " + Build.DISPLAY,
                        exifInterface.getAttribute(ExifInterface.TAG_SOFTWARE));

                assertEquals("Exif " + ExifInterface.TAG_IMAGE_WIDTH, 100,
                        exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0));
                assertEquals("Exif " + ExifInterface.TAG_IMAGE_LENGTH, 100,
                        exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0));

                assertEquals("Exif " + ExifInterface.TAG_DATETIME_ORIGINAL, "2020:12:15 13:15:00",
                        exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
                assertEquals("Exif " + ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "000",
                        exifInterface.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL));
                assertEquals("Exif " + ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-05:00",
                        exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));

                assertEquals("Exif " + ExifInterface.TAG_DATETIME_DIGITIZED, "2020:12:15 13:15:00",
                        exifInterface.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED));
                assertEquals("Exif " + ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, "000",
                        exifInterface.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED));
                assertEquals("Exif " + ExifInterface.TAG_OFFSET_TIME_DIGITIZED, "-05:00",
                        exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED));
            }
        } finally {
            if (decoded != null) {
                decoded.recycle();
            }
            contentResolver.delete(result, null);
        }
    }

    @Test
    public void testMediaStoreMetadata() {
        ContentValues values = ImageExporter.createMetadata(CAPTURE_TIME, CompressFormat.PNG);
        assertEquals("Pictures/Screenshots",
                values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals("Screenshot_20201215-131500.png",
                values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/png", values.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(Long.valueOf(1608056100L),
                values.getAsLong(MediaStore.MediaColumns.DATE_ADDED));
        assertEquals(Long.valueOf(1608056100L),
                values.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED));
        assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals(Long.valueOf(1608056100L + 86400L), // +1 day
                values.getAsLong(MediaStore.MediaColumns.DATE_EXPIRES));
    }

    @SuppressWarnings("SameParameterValue")
    private Bitmap createCheckerBitmap(int tileSize, int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w * tileSize, h * tileSize, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < h; i++) {
            int top = i * tileSize;
            for (int j = 0; j < w; j++) {
                int left = j * tileSize;
                paint.setColor(paint.getColor() == Color.WHITE ? Color.BLACK : Color.WHITE);
                c.drawRect(left, top, left + tileSize, top + tileSize, paint);
            }
        }
        return bitmap;
    }
}
