package lt.trevoras.multimedia;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements LocationListener {
    TextView speed, trip, maxspeed, time, heading, track, artist, castStatus, subtitle;
    EditText destination, castIp, castPort;
    LocationManager lm;
    Location lastLocation;
    double tripKm = 0, maxKmh = 0;
    boolean tripRunning = false;
    long tripStart = 0;
    Handler handler = new Handler(Looper.getMainLooper());
    MediaProjectionManager mpm;
    static final int REQ_CAST=700;
    static final int REQ_LOC=701;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        speed=findViewById(R.id.speed); trip=findViewById(R.id.trip); maxspeed=findViewById(R.id.maxspeed);
        time=findViewById(R.id.time); heading=findViewById(R.id.heading); track=findViewById(R.id.track);
        artist=findViewById(R.id.artist); castStatus=findViewById(R.id.castStatus); subtitle=findViewById(R.id.subtitle);
        destination=findViewById(R.id.destination); castIp=findViewById(R.id.castIp); castPort=findViewById(R.id.castPort);
        lm=(LocationManager)getSystemService(LOCATION_SERVICE);
        mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        WebView mapWeb = findViewById(R.id.mapWeb);
WebSettings mapSettings = mapWeb.getSettings();
mapSettings.setJavaScriptEnabled(true);
mapSettings.setDomStorageEnabled(true);
mapWeb.setWebViewClient(new WebViewClient());

mapWeb.loadUrl("https://www.openstreetmap.org/export/embed.html?bbox=21.00%2C55.88%2C21.12%2C55.96&layer=mapnik&marker=55.917%2C21.068");

mapWeb.loadUrl("https://www.openstreetmap.org/export/embed.html?bbox=21.00%2C55.88%2C21.12%2C55.96&layer=mapnik&marker=55.917%2C21.068");
        findViewById(R.id.googleMaps).setOnClickListener(v->navigate(false));
        findViewById(R.id.waze).setOnClickListener(v->navigate(true));
        findViewById(R.id.openSpotify).setOnClickListener(v->openPackage("com.spotify.music"));
        findViewById(R.id.mediaPermission).setOnClickListener(v->
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        findViewById(R.id.prev).setOnClickListener(v->media(MediaController.TransportControls::skipToPrevious));
        findViewById(R.id.play).setOnClickListener(v->togglePlay());
        findViewById(R.id.next).setOnClickListener(v->media(MediaController.TransportControls::skipToNext));
        findViewById(R.id.startTrip).setOnClickListener(v->startTrip());
        findViewById(R.id.resetTrip).setOnClickListener(v->resetTrip());
        findViewById(R.id.testTft).setOnClickListener(v->testTft());
        findViewById(R.id.autoDiscover).setOnClickListener(v->autoDiscover());
        findViewById(R.id.startCast).setOnClickListener(v->requestCast());
        findViewById(R.id.stopCast).setOnClickListener(v->{
            stopService(new Intent(this, CastService.class));
            castStatus.setText("Projekcija: sustabdyta");
        });

        requestLocation();
        handler.post(ticker);
    }

    void requestLocation(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC); return;
        }
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, this); }
        catch(Exception e){ subtitle.setText("GPS klaida: "+e.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ_LOC && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) requestLocation();
    }

    @Override public void onLocationChanged(Location l){
        double kmh=l.hasSpeed()? l.getSpeed()*3.6 : 0;
        speed.setText(String.valueOf((int)Math.round(kmh)));
        maxKmh=Math.max(maxKmh,kmh);
        if(l.hasBearing()) heading.setText("Kryptis  "+bearingName(l.getBearing()));
        if(tripRunning && lastLocation!=null && l.getAccuracy()<40 && lastLocation.getAccuracy()<40){
            float d=lastLocation.distanceTo(l);
            if(d<500) tripKm += d/1000.0;
        }
        lastLocation=l; refreshTrip();
    }

    String bearingName(float b){
        String[] n={"Š","ŠR","R","PR","P","PV","V","ŠV"};
        return n[(int)Math.round(b/45.0)%8]+"  "+Math.round(b)+"°";
    }

    void startTrip(){ tripRunning=!tripRunning; if(tripRunning && tripStart==0) tripStart=System.currentTimeMillis(); }
    void resetTrip(){ tripKm=0; maxKmh=0; tripStart=tripRunning?System.currentTimeMillis():0; lastLocation=null; refreshTrip(); }
    void refreshTrip(){
        trip.setText(String.format(Locale.US,"Kelionė  %.1f km",tripKm));
        maxspeed.setText("Max  "+Math.round(maxKmh)+" km/h");
    }

    Runnable ticker=new Runnable(){ public void run(){
        if(tripStart>0){
            long sec=(System.currentTimeMillis()-tripStart)/1000;
            time.setText(String.format(Locale.US,"Laikas  %02d:%02d",sec/3600,(sec/60)%60));
        }
        updateMediaInfo();
        handler.postDelayed(this,1000);
    }};

    void navigate(boolean waze){
        String q=destination.getText().toString().trim();
        if(q.isEmpty()){ Toast.makeText(this,"Įrašyk kelionės tikslą",Toast.LENGTH_SHORT).show(); return; }
        Uri u = waze
            ? Uri.parse("https://waze.com/ul?q="+Uri.encode(q)+"&navigate=yes")
            : Uri.parse("google.navigation:q="+Uri.encode(q)+"&mode=d");
        Intent i=new Intent(Intent.ACTION_VIEW,u);
        if(waze) i.setPackage("com.waze"); else i.setPackage("com.google.android.apps.maps");
        try{ startActivity(i); }catch(Exception e){ startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/?api=1&query="+Uri.encode(q)))); }
    }

    void openPackage(String p){
        Intent i=getPackageManager().getLaunchIntentForPackage(p);
        if(i!=null) startActivity(i); else Toast.makeText(this,"Programėlė neįdiegta",Toast.LENGTH_SHORT).show();
    }

    List<MediaController> controllers(){
        try{
            MediaSessionManager msm=(MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
            return msm.getActiveSessions(new ComponentName(this,MediaListener.class));
        }catch(Exception e){ return Collections.emptyList(); }
    }

    MediaController bestController(){
        for(MediaController c:controllers()) if("com.spotify.music".equals(c.getPackageName())) return c;
        List<MediaController> cs=controllers(); return cs.isEmpty()?null:cs.get(0);
    }

    interface MediaAction { void run(MediaController.TransportControls c); }
    void media(MediaAction a){ MediaController c=bestController(); if(c!=null)a.run(c.getTransportControls());
        else Toast.makeText(this,"Leisk muzikos valdymą",Toast.LENGTH_SHORT).show(); }

    void togglePlay(){
        MediaController c=bestController(); if(c==null){Toast.makeText(this,"Leisk muzikos valdymą",Toast.LENGTH_SHORT).show();return;}
        PlaybackState s=c.getPlaybackState();
        if(s!=null && s.getState()==PlaybackState.STATE_PLAYING)c.getTransportControls().pause();
        else c.getTransportControls().play();
    }

    void updateMediaInfo(){
        MediaController c=bestController(); if(c==null)return;
        MediaMetadata m=c.getMetadata(); if(m==null)return;
        CharSequence t=m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence a=m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if(t!=null)track.setText(t); if(a!=null)artist.setText(a);
    }

    void testTft(){
        String h=castIp.getText().toString().trim(); int p;
        try{p=Integer.parseInt(castPort.getText().toString());}catch(Exception e){p=8888;}
        final int port=p; castStatus.setText("Tikrinamas "+h+":"+port+"…");
        Executors.newSingleThreadExecutor().execute(()->{
            boolean ok=false; try(Socket s=new Socket()){s.connect(new InetSocketAddress(h,port),800);ok=true;}catch(Exception ignored){}
            boolean finalOk=ok; runOnUiThread(()->castStatus.setText(finalOk?
                "TFT atsako TCP "+h+":"+port:"TFT neatsako "+h+":"+port));
        });
    }

    void requestCast(){
        Intent capture=mpm.createScreenCaptureIntent();
        startActivityForResult(capture,REQ_CAST);
    }

    @Override protected void onActivityResult(int r,int result,Intent data){
        super.onActivityResult(r,result,data);
        if(r==REQ_CAST && result==RESULT_OK && data!=null){
            Intent s=new Intent(this,CastService.class);
            s.putExtra("resultCode",result); s.putExtra("data",data);
            s.putExtra("host",castIp.getText().toString().trim());
            int p=8888; try{p=Integer.parseInt(castPort.getText().toString());}catch(Exception ignored){}
            s.putExtra("port",p);
            startForegroundService(s);
          castStatus.setText("Eksperimentinė H.264 projekcija paleista");
    }
}

private void autoDiscover() {
    castStatus.setText("🔍 TFT skenavimas pradėtas...");

    new Thread(() -> {
        try {
            LinkedHashSet<String> prefixes = new LinkedHashSet<>();

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();

                        if (ip.startsWith("192.168.") ||
                                ip.startsWith("10.") ||
                                ip.startsWith("172.")) {

                            int lastDot = ip.lastIndexOf(".");
                            if (lastDot > 0) {
                                prefixes.add(ip.substring(0, lastDot));
                            }
                        }
                    }
                }
            }

            // Dažniausi Android hotspot tinklai
            prefixes.add("192.168.43");
            prefixes.add("192.168.1");
            prefixes.add("192.168.0");

            int[] ports = {
                    7236, 8000, 8080, 8888,
                    8899, 9000, 9999, 10000,
                    5000, 5555, 7000, 8554
            };

            for (String prefix : prefixes) {

                for (int host = 2; host <= 254; host++) {

                    String ip = prefix + "." + host;

                    if (host % 10 == 0) {
                        runOnUiThread(() ->
                                castStatus.setText("🔍 Tikrinama: " + ip)
                        );
                    }

                    for (int port : ports) {

                        try {
                            Socket socket = new Socket();
                            socket.connect(
                                    new InetSocketAddress(ip, port),
                                    100
                            );
                            socket.close();

                            final String foundIp = ip;
                            final int foundPort = port;

                            runOnUiThread(() -> {
                                castIp.setText(foundIp);
                                castPort.setText(String.valueOf(foundPort));
                                castStatus.setText(
                                        "✅ Rastas įrenginys: " +
                                                foundIp + ":" + foundPort
                                );
                            });

                            return;

                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            runOnUiThread(() ->
                    castStatus.setText(
                            "❌ TFT nerastas. Hotspot palik įjungtą ir Trevorą prijungtą."
                    )
            );

        } catch (Exception e) {

            runOnUiThread(() ->
                    castStatus.setText(
                            "❌ Skenavimo klaida: " + e.getMessage()
                    )
            );
        }
    }).start();
}

    @Override public void onProviderEnabled(String p){}
    @Override public void onProviderDisabled(String p){}
    @Override public void onStatusChanged(String p,int s,Bundle b){}
}
