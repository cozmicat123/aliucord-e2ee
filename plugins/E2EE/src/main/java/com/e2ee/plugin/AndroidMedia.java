package com.e2ee.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/** Android/OkHttp pieces plugged into MediaCache. */
final class AndroidMedia {
    private static final long MAX_PIXELS = 200_000_000L;

    private AndroidMedia() {}

    static MediaCache create(Context app) {
        File root = new File(app.getCacheDir(), "media");
        Handler handler = new Handler(Looper.getMainLooper());
        long heap = Runtime.getRuntime().maxMemory();
        long memory = Math.max(8L << 20, Math.min(80L << 20, heap / 6));
        return new MediaCache(
                root,
                new HttpTransport(),
                new BitmapDecoder(),
                handler::post,
                memory,
                MediaCache.DISK_MAX);
    }

    // Modified from upstream: Discord's bundled okhttp3 is obfuscated, so the transport uses HttpURLConnection.
    private static final class HttpTransport implements MediaCache.Transport {
        @Override
        public Connection open(String url, long rangeStart) throws IOException {
            final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(10));
            conn.setReadTimeout((int) TimeUnit.SECONDS.toMillis(30));
            conn.setRequestProperty("User-Agent", SuperProperties.USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            // MediaCache compares the bytes written with Content-Length, so no transparent gzip
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            final int code = conn.getResponseCode();
            return new Connection() {
                @Override
                public int code() {
                    return code;
                }

                @Override
                public long contentLength() {
                    return conn.getContentLengthLong();
                }

                @Override
                public String header(String name) {
                    return conn.getHeaderField(name);
                }

                @Override
                public InputStream body() throws IOException {
                    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    if (in == null) throw new IOException("empty body");
                    return in;
                }

                @Override
                public void cancel() {
                    conn.disconnect();
                }

                @Override
                public void close() {
                    conn.disconnect();
                }
            };
        }
    }

    private static final class BitmapDecoder implements MediaCache.Decoder {
        @Override
        public Bitmap decode(File file, int targetW, int targetH, int[] outDims) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            if ((long) bounds.outWidth * bounds.outHeight > MAX_PIXELS) return null;
            outDims[0] = bounds.outWidth;
            outDims[1] = bounds.outHeight;

            int tw = targetW > 0 ? targetW : 256, th = targetH > 0 ? targetH : 256;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample(bounds.outWidth, bounds.outHeight, tw, th);
            Bitmap decoded = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                    break;
                } catch (OutOfMemoryError e) {
                    opts.inSampleSize *= 2;
                }
            }
            if (decoded == null) return null;

            int orientation = readOrientation(file);
            if (orientation > ExifInterface.ORIENTATION_NORMAL
                    && orientation <= ExifInterface.ORIENTATION_TRANSVERSE) {
                Matrix m = new Matrix();
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        m.postRotate(90);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        m.postRotate(180);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        m.postRotate(270);
                        break;
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                        m.postScale(-1f, 1f);
                        break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                        m.postScale(1f, -1f);
                        break;
                    case ExifInterface.ORIENTATION_TRANSPOSE:
                        m.postRotate(90);
                        m.postScale(-1f, 1f);
                        break;
                    case ExifInterface.ORIENTATION_TRANSVERSE:
                        m.postRotate(270);
                        m.postScale(-1f, 1f);
                        break;
                    default:
                        break;
                }
                try {
                    Bitmap rotated =
                            Bitmap.createBitmap(
                                    decoded,
                                    0,
                                    0,
                                    decoded.getWidth(),
                                    decoded.getHeight(),
                                    m,
                                    true);
                    if (rotated != decoded) {
                        decoded.recycle();
                        decoded = rotated;
                    }
                    boolean swap =
                            orientation == ExifInterface.ORIENTATION_ROTATE_90
                                    || orientation == ExifInterface.ORIENTATION_ROTATE_270
                                    || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                                    || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
                    if (swap) {
                        int temp = outDims[0];
                        outDims[0] = outDims[1];
                        outDims[1] = temp;
                    }
                } catch (OutOfMemoryError | IllegalArgumentException ignored) {
                }
            }
            return decoded;
        }

        @Override
        public Bitmap videoFrame(File file, int targetW, int[] outDims) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(file.getAbsolutePath());
                Bitmap frame = r.getFrameAtTime(0);
                if (frame == null) return null;
                outDims[0] = frame.getWidth();
                outDims[1] = frame.getHeight();
                if (targetW > 0 && frame.getWidth() > targetW) {
                    int h =
                            Math.max(
                                    1,
                                    Math.round(
                                            frame.getHeight()
                                                    * (targetW / (float) frame.getWidth())));
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, targetW, h, true);
                    if (scaled != frame) frame.recycle();
                    return scaled;
                }
                return frame;
            } catch (Exception | OutOfMemoryError e) {
                return null;
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }

        private static int readOrientation(File file) {
            try {
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                return exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception e) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
        }

        private static int sample(int inW, int inH, int reqW, int reqH) {
            int sample = 1;
            while (inW / (sample * 2) >= reqW && inH / (sample * 2) >= reqH) sample *= 2;
            return sample;
        }
    }
}
