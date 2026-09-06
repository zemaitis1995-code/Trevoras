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

    static final String CH = "trevoras_cast";

    // Parametrai pagal originalią kinišką programėlę
    static final int WIDTH = 1280;
    static final int HEIGHT = 768;
    static final int FPS = 15;
    static final int IFRAME_INTERVAL = 1;

    // Maksimali vieno UDP fragmento H.264 duomenų dalis
    static final int MAX_CHUNK = 65500;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm =
                getSystemService(NotificationManager.class);

        nm.createNotificationChannel(
                new NotificationChannel(
                        CH,
                        "TREVORAS projekcija",
                        NotificationManager.IMPORTANCE_LOW
                )
        );

        Notification n =
                new Notification.Builder(this, CH)
                        .setContentTitle("TREVORAS")
                        .setContentText("Ekrano projekcija aktyvi")
                        .setSmallIcon(
                                android.R.drawable.stat_sys_upload
                        )
                        .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    42,
                    n,
                    ServiceInfo
                            .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(42, n);
        }
    }

    @Override
    public int onStartCommand(
            Intent i,
            int flags,
            int id
    ) {

        int result =
                i.getIntExtra(
                        "resultCode",
                        Activity.RESULT_CANCELED
                );

        Intent data =
                i.getParcelableExtra("data");

        String host =
                i.getStringExtra("host");

        int port =
                i.getIntExtra("port", 9038);

        if (data == null ||
                host == null ||
                host.trim().isEmpty()) {

            stopSelf();
            return START_NOT_STICKY;
        }

        try {

            startProjection(
                    result,
                    data,
                    host.trim(),
                    port
            );

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
                        getSystemService(
                                MEDIA_PROJECTION_SERVICE
                        );

        projection =
                m.getMediaProjection(
                        result,
                        data
                );

        MediaFormat f =
                MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        WIDTH,
                        HEIGHT
                );

        f.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatSurface
        );

        f.setInteger(
                MediaFormat.KEY_BIT_RATE,
                2_500_000
        );

        f.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                FPS
        );

        f.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                IFRAME_INTERVAL
        );

        // AVC profilis / lygis pagal originalią programėlę
        if (Build.VERSION.SDK_INT >= 21) {

            f.setInteger(
                    MediaFormat.KEY_PROFILE,
                    8
            );

            f.setInteger(
                    MediaFormat.KEY_LEVEL,
                    512
            );
        }

        codec =
                MediaCodec.createEncoderByType(
                        MediaFormat.MIMETYPE_VIDEO_AVC
                );

        codec.configure(
                f,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        Surface surface =
                codec.createInputSurface();

        codec.start();

        projection.createVirtualDisplay(
                "TREVORAS",
                WIDTH,
                HEIGHT,
                240,
                DisplayManager
                        .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
        );

        tftAddress =
                InetAddress.getByName(host);

        /*
         * SVARBU:
         * čia nekuriame atskiro UDP socket.
         *
         * Video siunčiamas per tą pačią
         * TrevorasWssClient sesiją:
         *
         * telefonas :9039
         * TFT       :9038
         */

        running = true;

        worker =
                new Thread(
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

                int ix =
                        codec.dequeueOutputBuffer(
                                info,
                                10000
                        );

                if (ix ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    /*
                     * Nieko papildomai nesiunčiame.
                     *
                     * Jokio atskiro TCP CSD/SPS/PPS.
                     */

                } else if (ix >= 0) {

                    ByteBuffer b =
                            codec.getOutputBuffer(ix);

                    if (b != null &&
                            info.size > 0) {

                        b.position(info.offset);

                        b.limit(
                                info.offset +
                                        info.size
                        );

                        byte[] encoded =
                                new byte[info.size];

                        b.get(encoded);

                        /*
                         * LABAI SVARBU.
                         *
                         * MediaCodec išvesties
                         * NEKEIČIAME.
                         *
                         * Nekonvertuojame į Annex-B.
                         * Neklijuojame SPS/PPS.
                         * Nekeičiame NAL blokų.
                         *
                         * Baitai keliauja tokie,
                         * kokius pateikė MediaCodec.
                         */

                        sendEncodedFrame(
                                encoded,
                                port
                        );
                    }

                    codec.releaseOutputBuffer(
                            ix,
                            false
                    );
                }
            }

        } catch (Exception ignored) {

        } finally {

            closeAll();
        }
    }

    /*
     * Kiniškos programėlės UDP
     * H.264 fragmentavimo schema.
     *
     * PIRMO FRAGMENTO HEADER:
     *
     * [0] visas frame dydis 0..7 bit
     * [1] visas frame dydis 8..15 bit
     * [2] visas frame dydis 16..23 bit
     * [3] 0
     *
     *
     * KITŲ FRAGMENTŲ HEADER:
     *
     * [0] fragmento dydis 0..7 bit
     * [1] fragmento dydis 8..15 bit
     * [2] 0
     * [3] fragmento numeris
     *
     * H.264 prasideda packet[4].
     */

    void sendEncodedFrame(
            byte[] frame,
            int port
    ) throws Exception {

        if (frame == null ||
                frame.length == 0) {

            return;
        }

        // Protokolas frame dydžiui turi 24 bitus
        if (frame.length > 0xFFFFFF) {
            return;
        }

        int offset = 0;
        int fragmentIndex = 0;

        while (offset < frame.length &&
                running) {

            int chunkLength =
                    Math.min(
                            MAX_CHUNK,
                            frame.length - offset
                    );

            byte[] packet =
                    new byte[
                            chunkLength + 4
                            ];

            if (fragmentIndex == 0) {

                int frameLength =
                        frame.length;

                packet[0] =
                        (byte)
                                (frameLength & 0xFF);

                packet[1] =
                        (byte)
                                ((frameLength >> 8)
                                        & 0xFF);

                packet[2] =
                        (byte)
                                ((frameLength >> 16)
                                        & 0xFF);

                packet[3] = 0;

            } else {

                packet[0] =
                        (byte)
                                (chunkLength & 0xFF);

                packet[1] =
                        (byte)
                                ((chunkLength >> 8)
                                        & 0xFF);

                packet[2] = 0;

                packet[3] =
                        (byte)
                                (fragmentIndex & 0xFF);
            }

            System.arraycopy(
                    frame,
                    offset,
                    packet,
                    4,
                    chunkLength
            );

            /*
             * Siunčiame per WSS klientą,
             * t. y. per aktyvų UDP :9039 socket.
             */

            TrevorasWssClient
                    .sendVideoPacketViaActiveSession(
                            packet
                    );

            offset += chunkLength;

            fragmentIndex++;
        }
    }

    void closeAll() {

        running = false;

        if (worker != null &&
                worker !=
                        Thread.currentThread()) {

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
