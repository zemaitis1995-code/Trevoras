package lt.trevoras.multimedia;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.Surface;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

public class CastService extends Service {
    MediaProjection projection;
    MediaCodec codec;
    Thread worker;
    volatile boolean running;
    Socket socket;
    static final String CH="trevoras_cast";

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH,"TREVORAS projekcija",NotificationManager.IMPORTANCE_LOW));
        Notification n=new Notification.Builder(this,CH)
            .setContentTitle("TREVORAS").setContentText("Ekrano projekcija aktyvi")
            .setSmallIcon(android.R.drawable.stat_sys_upload).build();
        if(Build.VERSION.SDK_INT>=29) startForeground(42,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(42,n);
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        int result=i.getIntExtra("resultCode",Activity.RESULT_CANCELED);
        Intent data=i.getParcelableExtra("data");
        String host=i.getStringExtra("host");
        int port=i.getIntExtra("port",8888);
        if(data==null || host==null){stopSelf();return START_NOT_STICKY;}
        try{ startProjection(result,data,host,port); }catch(Exception e){stopSelf();}
        return START_NOT_STICKY;
    }

    void startProjection(int result,Intent data,String host,int port)throws Exception{
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=m.getMediaProjection(result,data);

        // 1280x720 is intentionally conservative for an unknown motorcycle TFT decoder.
        int width=1280,height=720,dpi=240;
        MediaFormat f=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE,2_500_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE,30);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);

        codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface=codec.createInputSurface();
        codec.start();

        projection.createVirtualDisplay("TREVORAS",width,height,dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null);

        running=true;
        worker=new Thread(()->encodeLoop(host,port),"TrevorasH264");
        worker.start();
    }

    void encodeLoop(String host,int port){
        try{
            socket=new Socket();
            socket.connect(new InetSocketAddress(host,port),1500);
            OutputStream out=new BufferedOutputStream(socket.getOutputStream(),256*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();

            while(running){
                int ix=codec.dequeueOutputBuffer(info,10000);
                if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat of=codec.getOutputFormat();
                    ByteBuffer csd0=of.getByteBuffer("csd-0");
                    ByteBuffer csd1=of.getByteBuffer("csd-1");
                    if(csd0!=null)writeBuf(out,csd0);
                    if(csd1!=null)writeBuf(out,csd1);
                    out.flush();
                }else if(ix>=0){
                    ByteBuffer b=codec.getOutputBuffer(ix);
                    if(b!=null && info.size>0){
                        b.position(info.offset); b.limit(info.offset+info.size);
                        byte[] bytes=new byte[info.size]; b.get(bytes);
                        out.write(bytes); out.flush();
                    }
                    codec.releaseOutputBuffer(ix,false);
                }
            }
        }catch(Exception ignored){}
        finally{ closeAll(); }
    }

    void writeBuf(OutputStream out,ByteBuffer b)throws IOException{
        ByteBuffer d=b.duplicate(); byte[] x=new byte[d.remaining()]; d.get(x); out.write(x);
    }

    void closeAll(){
        running=false;
        try{if(socket!=null)socket.close();}catch(Exception ignored){}
        try{if(codec!=null){codec.stop();codec.release();}}catch(Exception ignored){}
        try{if(projection!=null)projection.stop();}catch(Exception ignored){}
    }

    @Override public void onDestroy(){closeAll();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
