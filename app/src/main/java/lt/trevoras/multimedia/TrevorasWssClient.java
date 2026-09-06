package lt.trevoras.multimedia;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrevorasWssClient {

    public interface Listener {
        void onStatus(String text);
        void onConnected(String deviceIp);
        void onDisconnected();
    }

    private static final int DISCOVERY_SEND_PORT = 9033;
    private static final int DISCOVERY_RECEIVE_PORT = 9034;

    private static final int LOCAL_SESSION_PORT = 9039;
    private static final int TFT_PORT = 9038;

    private static final byte[] START =
            new byte[]{0x5A, 0x01, 0x01, 0x0D};

    private static final byte[] START_ACK =
            new byte[]{0x5F, 0x01, 0x01, 0x0D};

    private static final byte[] STOP =
            new byte[]{0x5A, 0x02, 0x01, 0x0D};

    private static final byte[] STOP_ACK =
            new byte[]{0x5F, 0x02, 0x01, 0x0D};

    private static final byte[] HEARTBEAT =
            new byte[]{0x5A, 0x03, 0x01, 0x0D};

    private static final byte[] HEARTBEAT_ACK =
            new byte[]{0x5F, 0x03, 0x01, 0x0D};

    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private static final Object socketLock = new Object();

    private static volatile DatagramSocket sessionSocket;
    private static volatile InetAddress deviceAddress;
    private static volatile String deviceIpStr;

    private static volatile boolean connected = false;
    private static volatile boolean running = false;

    private static volatile Listener listener;

    private static int missedHeartbeats = 0;
    // Suderinamumas su esamu MainActivity
public TrevorasWssClient() {
}

public TrevorasWssClient(Listener newListener) {
    listener = newListener;
}

public void connectAsync() {
    connect();
}

public void stop() {
    disconnect();
}

    public static void setListener(Listener newListener) {
        listener = newListener;
    }

    public static boolean isConnected() {
        return connected;
    }

    public static String getDeviceIp() {
        return deviceIpStr;
    }

    public static void connect() {
        if (running) {
            status("TFT ryšys jau paleistas");
            return;
        }

        running = true;

        executor.execute(() -> {
            try {
                status("Ieškomas TFT...");

                String ip = discoverDevice();

                if (ip == null) {
                    status("TFT nerastas");
                    running = false;
                    disconnected();
                    return;
                }

                deviceIpStr = ip;
                deviceAddress = InetAddress.getByName(ip);

                status("TFT rastas: " + ip);

                openSessionSocket();

                boolean startOk = sendAndCheck(START, START_ACK);

                if (!startOk) {
                    status("TFT nepatvirtino START");
                    closeInternal();
                    return;
                }

                connected = true;
                missedHeartbeats = 0;

                status("TFT prijungtas: " + deviceIpStr);

                Listener l = listener;
                if (l != null) {
                    l.onConnected(deviceIpStr);
                }

                heartbeatLoop();

            } catch (Exception e) {
                status("TFT klaida: " + safeMessage(e));
                closeInternal();
            }
        });
    }

    public static void disconnect() {
        running = false;

        executor.execute(() -> {
            try {
                if (sessionSocket != null &&
                        !sessionSocket.isClosed() &&
                        deviceAddress != null) {

                    sendAndCheck(STOP, STOP_ACK);
                }
            } catch (Exception ignored) {
            }

            closeInternal();
        });
    }

    private static String discoverDevice() {
        DatagramSocket discoverySocket = null;

        try {
            discoverySocket = new DatagramSocket(null);
            discoverySocket.setReuseAddress(true);

            discoverySocket.bind(
                    new InetSocketAddress("0.0.0.0", DISCOVERY_RECEIVE_PORT)
            );

            discoverySocket.setBroadcast(true);
            discoverySocket.setSoTimeout(1500);

            byte[] request = "IP_FOUND".getBytes("US-ASCII");

            DatagramPacket sendPacket = new DatagramPacket(
                    request,
                    request.length,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_SEND_PORT
            );

            for (int attempt = 1; attempt <= 4; attempt++) {

                status("TFT paieška " + attempt + "/4");

                discoverySocket.send(sendPacket);

                long end = System.currentTimeMillis() + 1500;

                while (System.currentTimeMillis() < end) {
                    try {
                        byte[] buffer = new byte[512];

                        DatagramPacket response = new DatagramPacket(
                                buffer,
                                buffer.length
                        );

                        discoverySocket.receive(response);

                        String text = new String(
                                response.getData(),
                                0,
                                response.getLength(),
                                "US-ASCII"
                        ).trim();

                        if (text.startsWith("IP_FOUND_ACK")) {
                            return response.getAddress().getHostAddress();
                        }

                    } catch (SocketTimeoutException timeout) {
                        break;
                    }
                }
            }

        } catch (Exception e) {
            status("TFT paieškos klaida: " + safeMessage(e));

        } finally {
            if (discoverySocket != null) {
                try {
                    discoverySocket.close();
                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    private static void openSessionSocket() throws Exception {
        synchronized (socketLock) {

            if (sessionSocket != null) {
                try {
                    sessionSocket.close();
                } catch (Exception ignored) {
                }
            }

            sessionSocket = new DatagramSocket(null);
            sessionSocket.setReuseAddress(true);

            sessionSocket.bind(
                    new InetSocketAddress("0.0.0.0", LOCAL_SESSION_PORT)
            );

            sessionSocket.setSendBufferSize(1024 * 1024);
            sessionSocket.setReceiveBufferSize(1024 * 1024);
            sessionSocket.setSoTimeout(1500);
        }
    }

    private static boolean sendAndCheck(
            byte[] command,
            byte[] expectedReply
    ) {
        synchronized (socketLock) {
            try {
                if (sessionSocket == null ||
                        sessionSocket.isClosed() ||
                        deviceAddress == null) {
                    return false;
                }

                DatagramPacket sendPacket = new DatagramPacket(
                        command,
                        command.length,
                        deviceAddress,
                        TFT_PORT
                );

                sessionSocket.send(sendPacket);

                byte[] buffer = new byte[512];

                DatagramPacket receivePacket =
                        new DatagramPacket(buffer, buffer.length);

                sessionSocket.receive(receivePacket);

                byte[] actual = Arrays.copyOf(
                        receivePacket.getData(),
                        receivePacket.getLength()
                );

                return Arrays.equals(actual, expectedReply);

            } catch (SocketTimeoutException timeout) {
                return false;

            } catch (Exception e) {
                status("UDP klaida: " + safeMessage(e));
                return false;
            }
        }
    }

    private static void heartbeatLoop() {

        while (running && connected) {

            try {
                Thread.sleep(2000);

                if (!running || !connected) {
                    break;
                }

                boolean heartbeatOk =
                        sendAndCheck(HEARTBEAT, HEARTBEAT_ACK);

                if (heartbeatOk) {

                    if (missedHeartbeats > 0) {
                        status("TFT ryšys atkurtas");
                    }

                    missedHeartbeats = 0;

                } else {

                    missedHeartbeats++;

                    /*
                     * DIAG režimas:
                     *
                     * SmartRide logikoje heartbeat praradimas gali būti
                     * laikomas ryšio problema, tačiau projekcijos testavimo
                     * metu socketo neuždarome.
                     *
                     * Taip H.264 siuntimas nenutraukiamas vien dėl to,
                     * kad TFT neatsakė į heartbeat.
                     */
                    status(
                            "TFT prijungtas, heartbeat neatsakė (" +
                                    missedHeartbeats + ")"
                    );
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                status("Heartbeat klaida: " + safeMessage(e));
            }
        }
    }

    /**
     * SVARBIAUSIAS METODAS CASTSERVICE.
     *
     * H.264/video paketas siunčiamas per TĄ PATĮ aktyvų
     * UDP socketą, kuris lokaliai prijungtas prie 9039.
     *
     * TFT paskirties portas: 9038.
     */
    public static boolean sendVideoPacketViaActiveSession(byte[] packet) {

        if (packet == null || packet.length == 0) {
            return false;
        }

        synchronized (socketLock) {
            try {
                if (!connected ||
                        sessionSocket == null ||
                        sessionSocket.isClosed() ||
                        deviceAddress == null) {
                    return false;
                }

                DatagramPacket datagramPacket =
                        new DatagramPacket(
                                packet,
                                packet.length,
                                deviceAddress,
                                TFT_PORT
                        );

                sessionSocket.send(datagramPacket);

                return true;

            } catch (Exception e) {
                status("Video UDP klaida: " + safeMessage(e));
                return false;
            }
        }
    }

    /**
     * Paliekamas ir trumpesnis alias, jeigu kuri nors
     * ankstesnė TREVORAS klasė naudoja šį pavadinimą.
     */
    public static boolean sendVideoPacket(byte[] packet) {
        return sendVideoPacketViaActiveSession(packet);
    }

    private static void closeInternal() {

        connected = false;
        running = false;
        missedHeartbeats = 0;

        synchronized (socketLock) {

            if (sessionSocket != null) {
                try {
                    sessionSocket.close();
                } catch (Exception ignored) {
                }

                sessionSocket = null;
            }
        }

        deviceAddress = null;
        deviceIpStr = null;

        disconnected();
    }

    private static void disconnected() {
        Listener l = listener;

        if (l != null) {
            l.onDisconnected();
        }
    }

    private static void status(String text) {
        Listener l = listener;

        if (l != null) {
            l.onStatus(text);
        }
    }

    private static String safeMessage(Throwable throwable) {

        if (throwable == null) {
            return "nežinoma";
        }

        String message = throwable.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return throwable.getClass().getSimpleName();
        }

        return message;
    }
}
