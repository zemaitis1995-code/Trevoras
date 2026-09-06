package lt.trevoras.multimedia;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.Surface;

import java.net.*;
import java.nio.ByteBuffer;

public class CastService extends Service {
    MediaProjection projection;
    MediaCodec codec;
    Thread worker;
    volatile boolean running;

    InetAddress tftAddress;

    // SPS/PPS (codec config), kad kiekvienas IDR kadras būtų savarankiškai dekoduojamas.
    byte[] codecConfig;

    static final String CH = "trevoras_cast";

    // Originalios programėlės parametrai
    static final int WIDTH = 1280;
    static final int HEIGHT = 768;
    static final int FPS = 15;
    static final int IFRAME_INTERVAL = 1;

    // Originaliame protokole vieno H.264 fragmento duomenų dalis.
    static final int MAX_CHUNK = 65500;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(
                        CH,
                        "TREVORAS projekcija",
                        NotificationManager.IMPORTANCE_LOW
                )
        );

        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("TREVORAS")
                .setContentText("Ekrano projekcija aktyvi")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    42,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(42, n);
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        int result = i.getIntExtra(
                "resultCode",
                Activity.RESULT_CANCELED
        );

        Intent data = i.getParcelableExtra("data");
        String host = i.getStringExtra("host");

        // MainActivity jau perduoda 9038 iš WSS aptikimo.
        int port = i.getIntExtra("port", 9038);

        if (data == null || host == null || host.trim().isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startProjection(result, data, host.trim(), port);
        } catch (Exception e) {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    void startProjection(
            int result,
            Intent data,
            String host,
            int port
    ) throws Exception {

        MediaProjectionManager m =
                (MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        projection = m.getMediaProjection(result, data);

        MediaFormat f = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                WIDTH,
                HEIGHT
        );

        f.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        );

        // Bitrate paliekame praktišką; TFT protokolui svarbiausi čia
        // 1280x768, AVC, 15 fps ir fragmentavimo formatas.
        f.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                IFRAME_INTERVAL
        );

        // Originalios programėlės AVC profilis/lygis.
        if (Build.VERSION.SDK_INT >= 21) {
            f.setInteger(MediaFormat.KEY_PROFILE, 8);
            f.setInteger(MediaFormat.KEY_LEVEL, 512);
        }

        codec = MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC
        );

        codec.configure(
                f,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        Surface surface = codec.createInputSurface();
        codec.start();

        projection.createVirtualDisplay(
                "TREVORAS",
                WIDTH,
                HEIGHT,
                240,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
        );

        tftAddress = InetAddress.getByName(host);

        // SVARBU: originali programėlė valdymą IR video siunčia per tą pačią
        // WSS UDP sesiją, kurios telefono pusės portas yra 9039.
        // Todėl čia nekuriame atskiro DatagramSocket su atsitiktiniu source portu.

        running = true;

        worker = new Thread(
                () -> encodeLoop(port),
                "TrevorasH264"
        );

        worker.start();
    }

    void encodeLoop(int port) {
        try {
            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            while (running) {
                int ix = codec.dequeueOutputBuffer(
                        info,
                        10000
                );

                if (ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Codec config paprastai ateina ir encoded sraute.
                    // Sąmoningai nesiunčiame CSD kaip atskirų "raw TCP"
                    // baitų, nes TFT laukia WSS UDP video paketų.

                } else if (ix >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(ix);

                    if (b != null && info.size > 0) {
                        b.position(info.offset);
                        b.limit(info.offset + info.size);

                        byte[] encoded = new byte[info.size];
                        b.get(encoded);

                        // Kai kurie Android encoderiai grąžina AVCC (4 baitų NAL ilgiai),
                        // o TFT dekoderiai paprastai laukia Annex-B start kodų.
                        encoded = ensureAnnexB(encoded);

                        boolean config =
                                (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean keyFrame =
                                (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        if (config) {
                            codecConfig = encoded;
                        } else {
                            byte[] frame = encoded;

                            // SPS/PPS prieš IDR padidina suderinamumą ir leidžia TFT
                            // pradėti dekoduoti net jei pirmas config paketas buvo praleistas.
                            if (keyFrame && codecConfig != null && codecConfig.length > 0) {
                                frame = concat(codecConfig, encoded);
                            }

                            sendEncodedFrame(frame, port);
                        }
                    }

                    codec.releaseOutputBuffer(ix, false);
                }
            }

        } catch (Exception ignored) {
        } finally {
            closeAll();
        }
    }

    /**
     * Normalizuoja MediaCodec H.264 į Annex-B.
     * Jei srautas jau turi 00 00 01 / 00 00 00 01 start kodą, jo neliečia.
     * Kitu atveju bando interpretuoti kaip AVCC: [4-byte BE length][NAL]...
     */
    byte[] ensureAnnexB(byte[] data) {
        if (data == null || data.length < 4) {
            return data;
        }

        if ((data[0] == 0 && data[1] == 0 && data[2] == 1) ||
                (data.length >= 4 && data[0] == 0 && data[1] == 0 &&
                        data[2] == 0 && data[3] == 1)) {
            return data;
        }

        java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream(data.length + 32);

        int p = 0;
        boolean converted = false;

        while (p + 4 <= data.length) {
            int n =
                    ((data[p] & 0xFF) << 24) |
                    ((data[p + 1] & 0xFF) << 16) |
                    ((data[p + 2] & 0xFF) << 8) |
                    (data[p + 3] & 0xFF);
            p += 4;

            if (n <= 0 || p + n > data.length) {
                return data;
            }

            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(data, p, n);

            p += n;
            converted = true;
        }

        if (!converted || p != data.length) {
            return data;
        }

        return out.toByteArray();
    }

    byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Originalios programėlės WSS H.264 fragmentavimo schema.
     *
     * Pirmas fragmentas:
     *   [0] viso kadro ilgis, bits 0..7
     *   [1] viso kadro ilgis, bits 8..15
     *   [2] viso kadro ilgis, bits 16..23
     *   [3] 0
     *
     * Tolesni fragmentai:
     *   [0] šio fragmento duomenų ilgis, bits 0..7
     *   [1] šio fragmento duomenų ilgis, bits 8..15
     *   [2] 0
     *   [3] fragmento indeksas
     *
     * H.264 duomenys prasideda nuo packet[4].
     */
    void sendEncodedFrame(
            byte[] frame,
            int port
    ) throws Exception {

        if (frame == null || frame.length == 0) {
            return;
        }

        // Originalus kodas atmeta > 24 bitų dydžio kadrą.
        if (frame.length > 0xFFFFFF) {
            return;
        }

        int offset = 0;
        int fragmentIndex = 0;

        while (offset < frame.length && running) {
            int chunkLength = Math.min(
                    MAX_CHUNK,
                    frame.length - offset
            );

            byte[] packet =
                    new byte[chunkLength + 4];

            if (fragmentIndex == 0) {
                int frameLength = frame.length;

                packet[0] =
                        (byte) (frameLength & 0xFF);

                packet[1] =
                        (byte) ((frameLength >> 8) & 0xFF);

                packet[2] =
                        (byte) ((frameLength >> 16) & 0xFF);

                packet[3] = 0;

            } else {
                packet[0] =
                        (byte) (chunkLength & 0xFF);

                packet[1] =
                        (byte) ((chunkLength >> 8) & 0xFF);

                packet[2] = 0;

                packet[3] =
                        (byte) (fragmentIndex & 0xFF);
            }

            System.arraycopy(
                    frame,
                    offset,
                    packet,
                    4,
                    chunkLength
            );

            // Siunčiame per TrevorasWssClient jau atidarytą local UDP 9039 socketą.
            TrevorasWssClient.sendVideoPacketViaActiveSession(packet);

            offset += chunkLength;
            fragmentIndex++;
        }
    }

    void closeAll() {
        running = false;

        if (worker != null &&
                worker != Thread.currentThread()) {
            worker.interrupt();
            worker = null;
        }

        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception ignored) {
        }

        codec = null;

        try {
            if (projection != null) {
                projection.stop();
            }
        } catch (Exception ignored) {
        }

        projection = null;
    }

    @Override
    public void onDestroy() {
        closeAll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
