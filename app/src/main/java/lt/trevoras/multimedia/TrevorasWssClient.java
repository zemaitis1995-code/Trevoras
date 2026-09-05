package lt.trevoras.multimedia;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * TREVORAS TFT/WSS klientas atkurtas pagal originalios
 * com.deepwei.electricbicycle / Duowei programėlės ryšio seką.
 *
 * Discovery:
 *   local UDP 9034 -> broadcast UDP 9033: "IP_FOUND"
 *   TFT -> local UDP 9034: "IP_FOUND_ACK..."
 *
 * Control/video socket:
 *   phone binds local UDP 9039
 *   packets are sent to TFT UDP 9038
 *
 * Control frames:
 *   START      5A 01 01 0D
 *   START_ACK  5F 01 01 0D
 *   STOP       5A 02 01 0D
 *   STOP_ACK   5F 02 01 0D
 *   HEARTBEAT  5A 03 01 0D
 *   HB_ACK     5F 03 01 0D
 */
public class TrevorasWssClient {

    public interface Listener {
        void onStatus(String text);
        void onConnected(String deviceIp);
        void onDisconnected();
    }

    private static final int DISCOVERY_TARGET_PORT = 9033;
    private static final int DISCOVERY_LOCAL_PORT = 9034;
    private static final int DEVICE_PORT = 9038;
    private static final int LOCAL_STREAM_PORT = 9039;

    private static final byte[] IP_FOUND =
            "IP_FOUND".getBytes();

    private static final byte[] WSS_START =
            new byte[]{0x5A, 0x01, 0x01, 0x0D};

    private static final byte[] WSS_START_ACK =
            new byte[]{0x5F, 0x01, 0x01, 0x0D};

    private static final byte[] WSS_STOP =
            new byte[]{0x5A, 0x02, 0x01, 0x0D};

    private static final byte[] WSS_STOP_ACK =
            new byte[]{0x5F, 0x02, 0x01, 0x0D};

    private static final byte[] WSS_HEARTBEAT =
            new byte[]{0x5A, 0x03, 0x01, 0x0D};

    private static final byte[] WSS_HEARTBEAT_ACK =
            new byte[]{0x5F, 0x03, 0x01, 0x0D};

    private final Listener listener;

    private volatile boolean running = false;
    private volatile boolean connected = false;

    private String deviceIp = "";

    private DatagramSocket discoverySocket;
    private DatagramSocket streamSocket;

    private Thread worker;

    public TrevorasWssClient(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getDeviceIp() {
        return deviceIp;
    }

    public int getDevicePort() {
        return DEVICE_PORT;
    }

    public DatagramSocket getStreamSocket() {
        return streamSocket;
    }

    public void connectAsync() {
        stop();

        running = true;

        worker = new Thread(() -> {
            try {
                status("Ieškoma Trevoras TFT...");

                String ip = discoverDevice();

                if (!running) return;

                if (ip == null || ip.isEmpty()) {
                    status("TFT nerastas.");
                    disconnectState();
                    return;
                }

                deviceIp = ip;
                status("TFT rastas: " + deviceIp);

                prepareStreamSocket();

                status("Siunčiama projekcijos START užklausa...");

                boolean startOk = sendAndCheck(
                        WSS_START,
                        WSS_START_ACK,
                        1500
                );

                if (!startOk) {
                    status("TFT rastas, bet START_ACK negautas.");
                    disconnectState();
                    closeSockets();
                    return;
                }

                connected = true;
                status("TFT prijungtas: " + deviceIp + ":" + DEVICE_PORT);

                if (listener != null) {
                    listener.onConnected(deviceIp);
                }

                heartbeatLoop();

            } catch (Exception e) {
                status("TFT ryšio klaida: " + e.getMessage());
                disconnectState();
                closeSockets();
            }
        }, "Trevoras-WSS");

        worker.start();
    }

    private String discoverDevice() throws Exception {

        discoverySocket = new DatagramSocket(
                DISCOVERY_LOCAL_PORT,
                InetAddress.getByName("0.0.0.0")
        );

        discoverySocket.setBroadcast(true);
        discoverySocket.setSoTimeout(2000);

        List<InetAddress> broadcasts = getBroadcastAddresses();

        if (broadcasts.isEmpty()) {
            broadcasts.add(InetAddress.getByName("255.255.255.255"));
        }

        for (InetAddress broadcast : broadcasts) {

            if (!running) return null;

            status("TFT paieška: " + broadcast.getHostAddress());

            DatagramPacket request = new DatagramPacket(
                    IP_FOUND,
                    IP_FOUND.length,
                    broadcast,
                    DISCOVERY_TARGET_PORT
            );

            discoverySocket.send(request);

            // Originali programėlė bando kelis kartus.
            for (int attempt = 0; attempt < 3; attempt++) {

                try {
                    byte[] buffer = new byte[128];

                    DatagramPacket response =
                            new DatagramPacket(buffer, buffer.length);

                    discoverySocket.receive(response);

                    String text = new String(
                            response.getData(),
                            0,
                            response.getLength()
                    ).trim();

                    if (text.startsWith("IP_FOUND_ACK")) {
                        return response
                                .getAddress()
                                .getHostAddress();
                    }

                } catch (SocketTimeoutException ignored) {
                    if (attempt < 2) {
                        discoverySocket.send(request);
                    }
                }
            }
        }

        return null;
    }

    private void prepareStreamSocket() throws Exception {

        if (discoverySocket != null) {
            try {
                discoverySocket.close();
            } catch (Exception ignored) {}

            discoverySocket = null;
        }

        streamSocket = new DatagramSocket(
                LOCAL_STREAM_PORT,
                InetAddress.getByName("0.0.0.0")
        );

        // Originali programėlė naudoja 1 MiB send buffer.
        streamSocket.setSendBufferSize(1024 * 1024);
    }

    private boolean sendAndCheck(
            byte[] request,
            byte[] expectedReply,
            int timeoutMs
    ) throws Exception {

        if (streamSocket == null ||
                streamSocket.isClosed() ||
                deviceIp == null ||
                deviceIp.isEmpty()) {
            return false;
        }

        DatagramPacket send = new DatagramPacket(
                request,
                request.length,
                InetAddress.getByName(deviceIp),
                DEVICE_PORT
        );

        streamSocket.setSoTimeout(timeoutMs);
        streamSocket.send(send);

        byte[] buffer = new byte[256];

        DatagramPacket reply =
                new DatagramPacket(buffer, buffer.length);

        try {
            streamSocket.receive(reply);
        } catch (SocketTimeoutException e) {
            return false;
        }

        return beginsWith(
                reply.getData(),
                reply.getLength(),
                expectedReply
        );
    }

    private void heartbeatLoop() {

        int missed = 0;

        while (running && connected) {

            try {
                Thread.sleep(2000);

                if (!running || !connected) break;

                boolean ok = sendAndCheck(
                        WSS_HEARTBEAT,
                        WSS_HEARTBEAT_ACK,
                        1500
                );

                if (ok) {
                    if (missed > 0) {
                        status("TFT ryšys aktyvus — heartbeat vėl atsako.");
                    }
                    missed = 0;

                } else {
                    missed++;

                    // DIAGNOSTINIS REŽIMAS:
                    // START_ACK jau patvirtino, kad TFT priėmė WSS sesiją.
                    // Vien heartbeat timeout nebelaikome tikru atsijungimu,
                    // nes projekcijos / vaizdo protokolą dar atkuriame.
                    status("TFT prijungtas, heartbeat neatsakė (" + missed + ").");

                    // Neatjungiame ir neuždarome socket'o.
                    // Skaitiklį apribojame tik tam, kad statusas neaugtų be galo.
                    if (missed > 99) missed = 99;
                }

            } catch (InterruptedException e) {
                break;

            } catch (Exception e) {
                missed++;
                status("TFT prijungtas, heartbeat klaida (" + missed + "): " +
                        (e.getMessage() == null ? "be atsako" : e.getMessage()));

                if (missed > 99) missed = 99;
            }
        }

        // Čia patenkame tik kai vartotojas sustabdo klientą
        // arba sesija nutraukiama kitu aiškiu būdu.
        disconnectState();
        closeSockets();
    }

    public void stop() {

        running = false;

        if (connected && streamSocket != null && !streamSocket.isClosed()) {
            try {
                sendAndCheck(
                        WSS_STOP,
                        WSS_STOP_ACK,
                        700
                );
            } catch (Exception ignored) {}
        }

        connected = false;

        if (worker != null) {
            worker.interrupt();
            worker = null;
        }

        closeSockets();

        if (listener != null) {
            listener.onDisconnected();
        }
    }

    /**
     * Siunčia jau suformuotą UDP vaizdo paketą į TFT:9038.
     * Kitame etape čia jungsime MediaCodec H.264 fragmentavimo logiką.
     */
    public synchronized void sendVideoPacket(byte[] packet) throws Exception {

        if (!connected ||
                streamSocket == null ||
                streamSocket.isClosed()) {
            throw new IllegalStateException("TFT neprijungtas");
        }

        DatagramPacket dp = new DatagramPacket(
                packet,
                packet.length,
                InetAddress.getByName(deviceIp),
                DEVICE_PORT
        );

        streamSocket.send(dp);
    }

    private List<InetAddress> getBroadcastAddresses() {

        List<InetAddress> result = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            if (interfaces == null) return result;

            for (NetworkInterface ni :
                    Collections.list(interfaces)) {

                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception ignored) {
                    continue;
                }

                for (InterfaceAddress ia :
                        ni.getInterfaceAddresses()) {

                    InetAddress broadcast = ia.getBroadcast();

                    if (broadcast instanceof Inet4Address) {
                        result.add(broadcast);
                    }
                }
            }

        } catch (Exception ignored) {}

        return result;
    }

    private boolean beginsWith(
            byte[] data,
            int dataLength,
            byte[] expected
    ) {

        if (data == null ||
                expected == null ||
                dataLength < expected.length) {
            return false;
        }

        for (int i = 0; i < expected.length; i++) {
            if (data[i] != expected[i]) return false;
        }

        return true;
    }

    private void disconnectState() {

        boolean wasConnected = connected;
        connected = false;

        if (wasConnected && listener != null) {
            listener.onDisconnected();
        }
    }

    private void closeSockets() {

        if (discoverySocket != null) {
            try {
                discoverySocket.close();
            } catch (Exception ignored) {}

            discoverySocket = null;
        }

        if (streamSocket != null) {
            try {
                streamSocket.close();
            } catch (Exception ignored) {}

            streamSocket = null;
        }
    }

    private void status(String text) {
        if (listener != null) {
            listener.onStatus(text);
        }
    }
}
