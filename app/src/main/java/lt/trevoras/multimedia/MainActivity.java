package lt.trevoras.multimedia;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.*;
import android.media.MediaMetadata;
import android.media.projection.MediaProjectionManager;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity implements LocationListener {

    TextView speed, trip, maxspeed, time, heading, track, artist;
    TextView castStatus, subtitle, discoveryLog;
    TextView heroDate, heroClock, weatherTemp, weatherCity, weatherDesc;
    TextView tftBadgeText, tftDot, settingsButton;

    WebView mapWeb;
    EditText destination, castIp, castPort;

    LocationManager lm;
    Location lastLocation;

    double tripKm = 0;
    double maxKmh = 0;
    boolean tripRunning = false;
    long tripStart = 0;
    long accumulatedTripSeconds = 0;

    Handler handler = new Handler(Looper.getMainLooper());
    MediaProjectionManager mpm;

    static final int REQ_CAST = 700;
    static final int REQ_LOC = 701;

    long lastWeatherUpdate = 0;
    boolean tftVerifiedConnected = false;
    TrevorasWssClient wssClient;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        speed = findViewById(R.id.speed);
        trip = findViewById(R.id.trip);
        maxspeed = findViewById(R.id.maxspeed);
        time = findViewById(R.id.time);
        heading = findViewById(R.id.heading);
        track = findViewById(R.id.track);
        artist = findViewById(R.id.artist);
        castStatus = findViewById(R.id.castStatus);
        subtitle = findViewById(R.id.subtitle);
        discoveryLog = findViewById(R.id.discoveryLog);

        destination = findViewById(R.id.destination);
        castIp = findViewById(R.id.castIp);
        castPort = findViewById(R.id.castPort);

        heroDate = findViewById(R.id.heroDate);
        heroClock = findViewById(R.id.heroClock);
        weatherTemp = findViewById(R.id.weatherTemp);
        weatherCity = findViewById(R.id.weatherCity);
        weatherDesc = findViewById(R.id.weatherDesc);

        tftBadgeText = findViewById(R.id.tftBadgeText);
        tftDot = findViewById(R.id.tftDot);
        settingsButton = findViewById(R.id.settingsButton);

        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        setupMap();
        setTftDisconnected();
        wssClient = new TrevorasWssClient(new TrevorasWssClient.Listener() {

    @Override
    public void onStatus(String text) {
        runOnUiThread(() -> castStatus.setText(text));
    }

    @Override
    public void onConnected(String deviceIp) {
        runOnUiThread(() -> {
            castIp.setText(deviceIp);
            castPort.setText("9038");
            setTftConnected(deviceIp, 9038);
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> setTftDisconnected());
    }
});
        refreshTrip();
        updateLiveClock();

        findViewById(R.id.googleMaps).setOnClickListener(v -> navigate(false));
        findViewById(R.id.waze).setOnClickListener(v -> navigate(true));
        findViewById(R.id.openSpotify).setOnClickListener(v -> openPackage("com.spotify.music"));

        findViewById(R.id.mediaPermission).setOnClickListener(v ->
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));

        findViewById(R.id.prev).setOnClickListener(v ->
                media(MediaController.TransportControls::skipToPrevious));

        findViewById(R.id.play).setOnClickListener(v -> togglePlay());

        findViewById(R.id.next).setOnClickListener(v ->
                media(MediaController.TransportControls::skipToNext));

        findViewById(R.id.startTrip).setOnClickListener(v -> startTrip());
        findViewById(R.id.resetTrip).setOnClickListener(v -> resetTrip());

        findViewById(R.id.testTft).setOnClickListener(v -> testTft());
findViewById(R.id.autoDiscover).setOnClickListener(v -> {
    setTftDisconnected();
    castStatus.setText("Ieškoma TFT gamykliniu protokolu...");
    wssClient.connectAsync();
});
        findViewById(R.id.deepScan).setOnClickListener(v -> deepScan());

        findViewById(R.id.startCast).setOnClickListener(v -> requestCast());

        findViewById(R.id.stopCast).setOnClickListener(v -> {
            stopService(new Intent(this, CastService.class));
            castStatus.setText("Projekcija sustabdyta");
            setTftDisconnected();
        });

        if (settingsButton != null) {
            settingsButton.setOnClickListener(v -> showSettings());
        }

        requestLocation();
        handler.post(ticker);
    }

    void setupMap() {
        mapWeb = findViewById(R.id.mapWeb);

        WebSettings mapSettings = mapWeb.getSettings();
        mapSettings.setJavaScriptEnabled(true);
        mapSettings.setDomStorageEnabled(true);

        mapWeb.setWebViewClient(new WebViewClient());

        mapWeb.loadUrl(
                "https://www.openstreetmap.org/export/embed.html" +
                "?bbox=21.00%2C55.88%2C21.12%2C55.96" +
                "&layer=mapnik&marker=55.917%2C21.068"
        );
    }

    void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOC
            );
            return;
        }

        try {
            lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0,
                    this
            );

            try {
                lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000,
                        0,
                        this
                );
            } catch (Exception ignored) {}

        } catch (Exception e) {
            Toast.makeText(this,
                    "GPS klaida: " + e.getMessage(),
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);

        if (r == REQ_LOC &&
                g.length > 0 &&
                g[0] == PackageManager.PERMISSION_GRANTED) {

            requestLocation();
        }
    }

    @Override
    public void onLocationChanged(Location l) {

        double kmh = l.hasSpeed() ? l.getSpeed() * 3.6 : 0;

        speed.setText(String.valueOf((int) Math.round(kmh)));
        maxKmh = Math.max(maxKmh, kmh);

        // 4-a statistikos kortelė dabar rodo GPS aukštį.
        if (l.hasAltitude()) {
            heading.setText(Math.round(l.getAltitude()) + " m");
        } else {
            heading.setText("— m");
        }

        if (tripRunning &&
                lastLocation != null &&
                l.hasAccuracy() &&
                lastLocation.hasAccuracy() &&
                l.getAccuracy() < 40 &&
                lastLocation.getAccuracy() < 40) {

            float d = lastLocation.distanceTo(l);

            if (d >= 1 && d < 500) {
                tripKm += d / 1000.0;
            }
        }

        lastLocation = l;
        refreshTrip();

        updateMap(l);

        if (System.currentTimeMillis() - lastWeatherUpdate > 15 * 60 * 1000L) {
            lastWeatherUpdate = System.currentTimeMillis();
            updateWeather(l);
        }
    }

    void updateMap(Location l) {

        double lat = l.getLatitude();
        double lon = l.getLongitude();

        String mapUrl =
                "https://www.openstreetmap.org/export/embed.html?bbox=" +
                (lon - 0.025) + "%2C" +
                (lat - 0.015) + "%2C" +
                (lon + 0.025) + "%2C" +
                (lat + 0.015) +
                "&layer=mapnik&marker=" +
                lat + "%2C" + lon;

        if (mapWeb != null) {
            mapWeb.loadUrl(mapUrl);
        }
    }

    void updateWeather(Location l) {

        final double lat = l.getLatitude();
        final double lon = l.getLongitude();

        if (weatherDesc != null) weatherDesc.setText("Atnaujinama…");

        Executors.newSingleThreadExecutor().execute(() -> {

            HttpURLConnection con = null;

            try {
                String url =
                        "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=" + lat +
                        "&longitude=" + lon +
                        "&current=temperature_2m,weather_code" +
                        "&timezone=auto";

                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestMethod("GET");

                BufferedReader br = new BufferedReader(
                        new InputStreamReader(con.getInputStream())
                );

                StringBuilder sb = new StringBuilder();
                String line;

                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }

                br.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONObject current = root.getJSONObject("current");

                double temp = current.getDouble("temperature_2m");
                int code = current.getInt("weather_code");

                String city = getLocationName(lat, lon);
                String desc = weatherCodeText(code);

                runOnUiThread(() -> {
                    if (weatherTemp != null) {
                        weatherTemp.setText(
                                weatherIcon(code) + "  " +
                                Math.round(temp) + "°C"
                        );
                    }

                    if (weatherCity != null) {
                        weatherCity.setText(city);
                    }

                    if (weatherDesc != null) {
                        weatherDesc.setText(desc);
                    }
                });

            } catch (Exception e) {

                runOnUiThread(() -> {
                    if (weatherTemp != null) weatherTemp.setText("—°C");
                    if (weatherCity != null) weatherCity.setText("Dabartinė vieta");
                    if (weatherDesc != null) weatherDesc.setText("Orų duomenų nėra");
                });

            } finally {
                if (con != null) con.disconnect();
            }
        });
    }

    String getLocationName(double lat, double lon) {

        try {
            Geocoder geocoder = new Geocoder(this, new Locale("lt", "LT"));

            List<Address> addresses =
                    geocoder.getFromLocation(lat, lon, 1);

            if (addresses != null && !addresses.isEmpty()) {

                Address a = addresses.get(0);

                if (a.getLocality() != null) return a.getLocality();
                if (a.getSubAdminArea() != null) return a.getSubAdminArea();
                if (a.getAdminArea() != null) return a.getAdminArea();
            }

        } catch (Exception ignored) {}

        return "Dabartinė vieta";
    }

    String weatherIcon(int code) {

        if (code == 0) return "☀";
        if (code <= 3) return "☁";
        if (code == 45 || code == 48) return "🌫";
        if (code >= 51 && code <= 67) return "🌧";
        if (code >= 71 && code <= 77) return "❄";
        if (code >= 80 && code <= 82) return "🌦";
        if (code >= 95) return "⛈";

        return "☁";
    }

    String weatherCodeText(int code) {

        switch (code) {
            case 0: return "Giedra";
            case 1: return "Daugiausia giedra";
            case 2: return "Dalinis debesuotumas";
            case 3: return "Debesuota";
            case 45:
            case 48: return "Rūkas";
            case 51:
            case 53:
            case 55: return "Dulksna";
            case 56:
            case 57: return "Šąlanti dulksna";
            case 61:
            case 63:
            case 65: return "Lietus";
            case 66:
            case 67: return "Šąlantis lietus";
            case 71:
            case 73:
            case 75:
            case 77: return "Sniegas";
            case 80:
            case 81:
            case 82: return "Lietaus šuorai";
            case 85:
            case 86: return "Sniego šuorai";
            case 95:
            case 96:
            case 99: return "Perkūnija";
            default: return "Orų duomenys";
        }
    }

    void startTrip() {

        if (!tripRunning) {
            tripRunning = true;
            tripStart = System.currentTimeMillis();

            Toast.makeText(this,
                    "Kelionė pradėta",
                    Toast.LENGTH_SHORT
            ).show();

        } else {
            accumulatedTripSeconds +=
                    (System.currentTimeMillis() - tripStart) / 1000;

            tripRunning = false;
            tripStart = 0;

            Toast.makeText(this,
                    "Kelionė pristabdyta",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    void resetTrip() {

        tripKm = 0;
        maxKmh = 0;
        accumulatedTripSeconds = 0;

        if (tripRunning) {
            tripStart = System.currentTimeMillis();
        } else {
            tripStart = 0;
        }

        lastLocation = null;

        refreshTrip();
        updateTripTime();
    }

    void refreshTrip() {

        trip.setText(
                String.format(
                        Locale.US,
                        "%.1f km",
                        tripKm
                )
        );

        maxspeed.setText(
                Math.round(maxKmh) + " km/h"
        );
    }

    void updateTripTime() {

        long sec = accumulatedTripSeconds;

        if (tripRunning && tripStart > 0) {
            sec += (System.currentTimeMillis() - tripStart) / 1000;
        }

        time.setText(
                String.format(
                        Locale.US,
                        "%02d:%02d",
                        sec / 3600,
                        (sec / 60) % 60
                )
        );
    }

    void updateLiveClock() {

        Date now = new Date();

        if (heroClock != null) {
            heroClock.setText(
                    new SimpleDateFormat(
                            "HH:mm",
                            Locale.getDefault()
                    ).format(now)
            );
        }

        if (heroDate != null) {
            heroDate.setText(
                    new SimpleDateFormat(
                            "yyyy-MM-dd",
                            Locale.getDefault()
                    ).format(now)
            );
        }
    }

    Runnable ticker = new Runnable() {
        public void run() {

            updateLiveClock();
            updateTripTime();
            updateMediaInfo();

            handler.postDelayed(this, 1000);
        }
    };

    void showSettings() {

        String[] items = {
                "Datos ir laiko nustatymai",
                "Vietos / GPS nustatymai",
                "Programėlės nustatymai",
                "TFT ryšio būsena"
        };

        new AlertDialog.Builder(this)
                .setTitle("TREVORAS nustatymai")
                .setItems(items, (dialog, which) -> {

                    try {
                        if (which == 0) {
                            startActivity(
                                    new Intent(Settings.ACTION_DATE_SETTINGS)
                            );

                        } else if (which == 1) {
                            startActivity(
                                    new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            );

                        } else if (which == 2) {
                            Intent i = new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())
                            );
                            startActivity(i);

                        } else {
                            new AlertDialog.Builder(this)
                                    .setTitle("TFT")
                                    .setMessage(
                                            tftVerifiedConnected
                                                    ? "TFT ryšys patvirtintas."
                                                    : "TFT šiuo metu neprijungtas / ryšys nepatvirtintas."
                                    )
                                    .setPositiveButton("Gerai", null)
                                    .show();
                        }

                    } catch (Exception e) {
                        Toast.makeText(
                                this,
                                "Nepavyko atidaryti nustatymų",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                })
                .setNegativeButton("Uždaryti", null)
                .show();
    }

    void navigate(boolean waze) {

        String q = destination.getText().toString().trim();

        if (q.isEmpty()) {
            Toast.makeText(
                    this,
                    "Įrašyk kelionės tikslą",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Uri u = waze
                ? Uri.parse(
                        "https://waze.com/ul?q=" +
                        Uri.encode(q) +
                        "&navigate=yes"
                )
                : Uri.parse(
                        "google.navigation:q=" +
                        Uri.encode(q) +
                        "&mode=d"
                );

        Intent i = new Intent(Intent.ACTION_VIEW, u);

        if (waze) {
            i.setPackage("com.waze");
        } else {
            i.setPackage("com.google.android.apps.maps");
        }

        try {
            startActivity(i);

        } catch (Exception e) {

            startActivity(
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                    "https://www.google.com/maps/search/" +
                                    "?api=1&query=" +
                                    Uri.encode(q)
                            )
                    )
            );
        }
    }

    void openPackage(String p) {

        Intent i =
                getPackageManager().getLaunchIntentForPackage(p);

        if (i != null) {
            startActivity(i);
        } else {
            Toast.makeText(
                    this,
                    "Programėlė neįdiegta",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    List<MediaController> controllers() {

        try {
            MediaSessionManager msm =
                    (MediaSessionManager)
                            getSystemService(MEDIA_SESSION_SERVICE);

            return msm.getActiveSessions(
                    new ComponentName(this, MediaListener.class)
            );

        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    MediaController bestController() {

        for (MediaController c : controllers()) {
            if ("com.spotify.music".equals(c.getPackageName())) {
                return c;
            }
        }

        List<MediaController> cs = controllers();

        return cs.isEmpty() ? null : cs.get(0);
    }

    interface MediaAction {
        void run(MediaController.TransportControls c);
    }

    void media(MediaAction a) {

        MediaController c = bestController();

        if (c != null) {
            a.run(c.getTransportControls());
        } else {
            Toast.makeText(
                    this,
                    "Leisk muzikos valdymą",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    void togglePlay() {

        MediaController c = bestController();

        if (c == null) {
            Toast.makeText(
                    this,
                    "Leisk muzikos valdymą",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        PlaybackState s = c.getPlaybackState();

        if (s != null &&
                s.getState() == PlaybackState.STATE_PLAYING) {

            c.getTransportControls().pause();

        } else {
            c.getTransportControls().play();
        }
    }

    void updateMediaInfo() {

        MediaController c = bestController();

        if (c == null) return;

        MediaMetadata m = c.getMetadata();

        if (m == null) return;

        CharSequence t =
                m.getText(MediaMetadata.METADATA_KEY_TITLE);

        CharSequence a =
                m.getText(MediaMetadata.METADATA_KEY_ARTIST);

        if (t != null) track.setText(t);
        if (a != null) artist.setText(a);
    }

    void setTftDisconnected() {

        tftVerifiedConnected = false;

        if (tftBadgeText != null) {
            tftBadgeText.setText("TFT\nNEPRIJUNGTAS");
        }

        if (tftDot != null) {
            tftDot.setTextColor(
                    Color.parseColor("#9AA6AF")
            );
        }
    }

    void setTftConnected(String host, int port) {

        tftVerifiedConnected = true;

        if (tftBadgeText != null) {
            tftBadgeText.setText("TFT\nPRIJUNGTAS");
        }

        if (tftDot != null) {
            tftDot.setTextColor(
                    Color.parseColor("#15C66A")
            );
        }

        castStatus.setText(
                "TFT ryšys patvirtintas: " +
                host + ":" + port
        );
    }

    void testTft() {

        String h =
                castIp.getText().toString().trim();

        int p;

        try {
            p = Integer.parseInt(
                    castPort.getText().toString()
            );
        } catch (Exception e) {
            castStatus.setText(
                    "Įvesk TFT portą"
            );
            setTftDisconnected();
            return;
        }

        final int port = p;

        castStatus.setText(
                "Tikrinamas " + h + ":" + port + "…"
        );

        setTftDisconnected();

        Executors.newSingleThreadExecutor().execute(() -> {

            boolean ok = false;

            try (Socket s = new Socket()) {
                s.connect(
                        new InetSocketAddress(h, port),
                        1000
                );
                ok = true;
            } catch (Exception ignored) {}

            boolean finalOk = ok;

            runOnUiThread(() -> {

                if (finalOk) {
                    setTftConnected(h, port);
                } else {
                    castStatus.setText(
                            "TFT neatsako: " +
                            h + ":" + port
                    );
                    setTftDisconnected();
                }
            });
        });
    }

    void requestCast() {

        Intent capture =
                mpm.createScreenCaptureIntent();

        startActivityForResult(
                capture,
                REQ_CAST
        );
    }

    @Override
    protected void onActivityResult(
            int r,
            int result,
            Intent data
    ) {

        super.onActivityResult(
                r,
                result,
                data
        );

        if (r == REQ_CAST &&
                result == RESULT_OK &&
                data != null) {

            Intent s =
                    new Intent(
                            this,
                            CastService.class
                    );

            s.putExtra("resultCode", result);
            s.putExtra("data", data);

            s.putExtra(
                    "host",
                    castIp.getText()
                            .toString()
                            .trim()
            );

            int p;

            try {
                p = Integer.parseInt(
                        castPort.getText()
                                .toString()
                );
            } catch (Exception ignored) {
                castStatus.setText(
                        "Projekcijai reikia patvirtinto porto"
                );
                return;
            }

            s.putExtra("port", p);

            startForegroundService(s);

            castStatus.setText(
                    "Eksperimentinė H.264 projekcija paleista"
            );
        }
    }

    private void autoDiscover() {

        castStatus.setText(
                "🔍 TFT paieška pradėta..."
        );

        setTftDisconnected();

        new Thread(() -> {

            try {

                LinkedHashSet<String> prefixes =
                        new LinkedHashSet<>();

                Enumeration<NetworkInterface> interfaces =
                        NetworkInterface.getNetworkInterfaces();

                while (interfaces.hasMoreElements()) {

                    NetworkInterface ni =
                            interfaces.nextElement();

                    Enumeration<InetAddress> addresses =
                            ni.getInetAddresses();

                    while (addresses.hasMoreElements()) {

                        InetAddress addr =
                                addresses.nextElement();

                        if (addr instanceof Inet4Address &&
                                !addr.isLoopbackAddress()) {

                            String ip =
                                    addr.getHostAddress();

                            if (ip.startsWith("192.168.") ||
                                    ip.startsWith("10.") ||
                                    ip.startsWith("172.")) {

                                int lastDot =
                                        ip.lastIndexOf(".");

                                if (lastDot > 0) {
                                    prefixes.add(
                                            ip.substring(
                                                    0,
                                                    lastDot
                                            )
                                    );
                                }
                            }
                        }
                    }
                }

                prefixes.add("192.168.43");
                prefixes.add("192.168.1");
                prefixes.add("192.168.0");

                int[] ports = {
                        7236, 8000, 8080,
                        8899, 9000, 9999,
                        10000, 5000, 5555,
                        7000, 8554
                };

                for (String prefix : prefixes) {

                    for (int host = 2;
                         host <= 254;
                         host++) {

                        String ip =
                                prefix + "." + host;

                        if (host % 10 == 0) {
                            runOnUiThread(() ->
                                    castStatus.setText(
                                            "🔍 Tikrinama: " + ip
                                    )
                            );
                        }

                        for (int port : ports) {

                            try (Socket socket =
                                         new Socket()) {

                                socket.connect(
                                        new InetSocketAddress(
                                                ip,
                                                port
                                        ),
                                        100
                                );

                                final String foundIp = ip;
                                final int foundPort = port;

                                runOnUiThread(() -> {

                                    castIp.setText(foundIp);
                                    castPort.setText(
                                            String.valueOf(
                                                    foundPort
                                            )
                                    );

                                    // Svarbu: atviras portas dar nereiškia,
                                    // kad TFT protokolas patvirtintas.
                                    castStatus.setText(
                                            "Rastas galimas įrenginys: " +
                                            foundIp + ":" +
                                            foundPort +
                                            ". Spausk TESTAS."
                                    );

                                    setTftDisconnected();
                                });

                                return;

                            } catch (Exception ignored) {}
                        }
                    }
                }

                runOnUiThread(() -> {

                    castStatus.setText(
                            "TFT kandidatas nerastas."
                    );

                    setTftDisconnected();
                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    castStatus.setText(
                            "Skenavimo klaida: " +
                            e.getMessage()
                    );

                    setTftDisconnected();
                });
            }

        }).start();
    }

    private void deepScan() {

        final String ip =
                castIp.getText()
                        .toString()
                        .trim();

        if (ip.isEmpty()) {
            castStatus.setText(
                    "Įvesk TFT IP adresą"
            );
            return;
        }

        setTftDisconnected();

        castStatus.setText(
                "Gilus skenavimas: " + ip
        );

        discoveryLog.setText(
                "Tikrinami TCP portai 1–12000...\n"
        );

        final int startPort = 1;
        final int endPort = 12000;
        final int workerCount = 48;

        final AtomicInteger nextPort =
                new AtomicInteger(startPort);

        final AtomicInteger activeWorkers =
                new AtomicInteger(workerCount);

        final ConcurrentLinkedQueue<Integer>
                openPorts =
                new ConcurrentLinkedQueue<>();

        ExecutorService pool =
                Executors.newFixedThreadPool(
                        workerCount
                );

        for (int w = 0;
             w < workerCount;
             w++) {

            pool.execute(() -> {

                try {

                    while (true) {

                        int port =
                                nextPort.getAndIncrement();

                        if (port > endPort) break;

                        try (Socket socket =
                                     new Socket()) {

                            socket.connect(
                                    new InetSocketAddress(
                                            ip,
                                            port
                                    ),
                                    80
                            );

                            openPorts.add(port);

                            final int foundPort =
                                    port;

                            runOnUiThread(() -> {

                                discoveryLog.append(
                                        "ATVIRAS: " +
                                        ip + ":" +
                                        foundPort +
                                        "\n"
                                );

                                castPort.setText(
                                        String.valueOf(
                                                foundPort
                                        )
                                );
                            });

                        } catch (Exception ignored) {}

                        if (port % 250 == 0) {

                            final int current =
                                    port;

                            runOnUiThread(() ->
                                    castStatus.setText(
                                            "Tikrinama: " +
                                            Math.min(
                                                    current,
                                                    endPort
                                            ) +
                                            " / " +
                                            endPort
                                    )
                            );
                        }
                    }

                } finally {

                    if (activeWorkers
                            .decrementAndGet() == 0) {

                        pool.shutdown();

                        runOnUiThread(() -> {

                            if (openPorts.isEmpty()) {

                                castStatus.setText(
                                        "Atvirų TCP portų 1–12000 nerasta"
                                );

                                discoveryLog.append(
                                        "\nBaigta. Atvirų TCP portų nerasta."
                                );

                            } else {

                                ArrayList<Integer> sorted =
                                        new ArrayList<>(
                                                openPorts
                                        );

                                Collections.sort(sorted);

                                StringBuilder result =
                                        new StringBuilder(
                                                "\nRasti portai: "
                                        );

                                for (int i = 0;
                                     i < sorted.size();
                                     i++) {

                                    if (i > 0) {
                                        result.append(", ");
                                    }

                                    result.append(
                                            sorted.get(i)
                                    );
                                }

                                discoveryLog.append(
                                        result.toString()
                                );

                                castStatus.setText(
                                        "Rasti " +
                                        sorted.size() +
                                        " atviri portai. " +
                                        "Pasirink ir spausk TESTAS."
                                );
                            }

                            setTftDisconnected();
                        });
                    }
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);

        try {
            lm.removeUpdates(this);
        } catch (Exception ignored) {}

        super.onDestroy();
    }

    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}
}
