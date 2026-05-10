package pl.test1.projectormirrorservice;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.*;
import android.hardware.usb.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.Surface;
import java.io.*;
import java.nio.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MirrorService extends Service {
    public static final String ACTION_START = "pl.test1.projectormirrorservice.START";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    private static final String CHANNEL_ID = "mirror_service";
    private static final int NOTIFICATION_ID = 501;
    private static final int WIDTH=800, HEIGHT=480, FPS=30, BITRATE=1200000, IFRAME=1;

    private UsbManager usbManager;
    private ParcelFileDescriptor fd;
    private FileInputStream in;
    private FileOutputStream out;
    private MediaProjection projection;
    private MediaCodec encoder;
    private Surface inputSurface;
    private VirtualDisplay display;
    private volatile boolean running=false, reading=false;
    private Thread readerThread, encoderThread;
    private File logFile;
    private long hb=0, ack=0, frames=0, bytes=0;

    @Override public void onCreate(){
        super.onCreate(); usbManager=(UsbManager)getSystemService(USB_SERVICE); logFile=new File(getExternalFilesDir(null),"projector_mirror_service_log.txt"); createChannel(); log("MirrorService onCreate");
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, notification("Starting"));
        if(intent==null || !ACTION_START.equals(intent.getAction())){ log("No ACTION_START"); return START_NOT_STICKY; }
        int resultCode=intent.getIntExtra(EXTRA_RESULT_CODE,0); Intent data=intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if(resultCode==0 || data==null){ log("Missing MediaProjection result data"); stopSelf(); return START_NOT_STICKY; }
        try{ startWork(resultCode,data); }catch(Exception e){ log("startWork exception: "+e); stopSelf(); }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy(){ log("MirrorService onDestroy"); stopWork(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i){ return null; }

    private void startWork(int resultCode, Intent data) throws Exception{
        if(!openAccessory()){ log("Cannot open accessory"); stopSelf(); return; }
        MediaProjectionManager pm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=pm.getMediaProjection(resultCode,data);
        projection.registerCallback(new MediaProjection.Callback(){ @Override public void onStop(){ log("MediaProjection stopped"); stopSelf(); } }, null);
        setupEncoder();
        running=true; encoderThread=new Thread(this::encoderLoop,"MirrorService-Encoder"); encoderThread.start();
        log("MirrorService running: "+WIDTH+"x"+HEIGHT+" fps="+FPS+" bitrate="+BITRATE);
        updateNotification("Running");
    }

    private boolean openAccessory(){
        UsbAccessory[] list=usbManager.getAccessoryList(); if(list==null||list.length==0){ log("No USB accessory in service"); return false; }
        UsbAccessory a=choose(list); log("Service selected accessory: "+describe(a));
        if(!usbManager.hasPermission(a)){ log("No USB permission in service"); return false; }
        fd=usbManager.openAccessory(a); if(fd==null){ log("openAccessory null in service"); return false; }
        in=new FileInputStream(fd.getFileDescriptor()); out=new FileOutputStream(fd.getFileDescriptor()); log("openAccessory OK in service"); startReader(); return true;
    }
    private UsbAccessory choose(UsbAccessory[] list){ for(UsbAccessory a:list) if("Mirroring".equalsIgnoreCase(safe(a.getManufacturer()))&&"gan mirroring".equalsIgnoreCase(safe(a.getModel()))) return a; return list[0]; }

    private void startReader(){
        reading=true; readerThread=new Thread(()->{ byte[] buf=new byte[1024]; log("USB reader started in service"); while(reading){ try{ int n=in.read(buf); if(n<0){ log("USB EOF"); break; } byte[] c=Arrays.copyOf(buf,n); if(c.length==4&&c[0]==0&&c[1]==0&&c[2]==0&&c[3]==0){ hb++; log("USB heartbeat #"+hb); try{ writeRaw(new byte[]{0,0,0,0}); ack++; log("USB ACK #"+ack); }catch(IOException e){ log("ACK IOException: "+e); } } else log("USB read "+n+" bytes: "+hex(c,Math.min(n,128))); }catch(Exception e){ if(reading) log("USB read exception: "+e); break; } } reading=false; log("USB reader stopped"); },"USB-Reader"); readerThread.start();
    }

    private void setupEncoder() throws IOException{
        MediaFormat f=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,WIDTH,HEIGHT);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE,BITRATE); f.setInteger(MediaFormat.KEY_FRAME_RATE,FPS); f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,IFRAME);
        encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); encoder.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); inputSurface=encoder.createInputSurface(); encoder.start();
        display=projection.createVirtualDisplay("service-mirroring",WIDTH,HEIGHT,1,DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,inputSurface,null,null);
        log("Encoder configured and VirtualDisplay created");
    }

    private void encoderLoop(){
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(running){ try{ int idx=encoder.dequeueOutputBuffer(info,10000); if(idx==MediaCodec.INFO_TRY_AGAIN_LATER) continue; if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){ MediaFormat f=encoder.getOutputFormat(); log("Output format changed: "+f); writeCsd(f); continue; } if(idx<0) continue; ByteBuffer b=encoder.getOutputBuffer(idx); if(b!=null&&info.size>0){ b.position(info.offset); b.limit(info.offset+info.size); byte[] data=new byte[info.size]; b.get(data); writeRaw(data); frames++; if(frames%30==0){ log("Sent frames="+frames+", bytes="+bytes); updateNotification("Sent frames="+frames); } } encoder.releaseOutputBuffer(idx,false); } catch(Exception e){ log("Encoder loop exception: "+e); break; } }
        log("Encoder loop stopped"); stopSelf();
    }
    private void writeCsd(MediaFormat f){ try{ ByteBuffer c0=f.getByteBuffer("csd-0"), c1=f.getByteBuffer("csd-1"); if(c0!=null){ byte[] d=toBytes(c0); writeRaw(d); log("Wrote csd-0 "+d.length+" bytes: "+hex(d,64)); } if(c1!=null){ byte[] d=toBytes(c1); writeRaw(d); log("Wrote csd-1 "+d.length+" bytes: "+hex(d,64)); } }catch(Exception e){ log("writeCsd exception: "+e); } }
    private byte[] toBytes(ByteBuffer bb){ ByteBuffer d=bb.duplicate(); d.position(0); byte[] x=new byte[d.remaining()]; d.get(x); return x; }
    private synchronized void writeRaw(byte[] d)throws IOException{ if(out==null) throw new IOException("usbOutput null"); out.write(d); out.flush(); bytes+=d.length; }

    private void stopWork(){ running=false; reading=false; try{if(readerThread!=null)readerThread.interrupt();}catch(Exception ignored){} try{if(encoderThread!=null)encoderThread.interrupt();}catch(Exception ignored){} try{if(display!=null)display.release();}catch(Exception ignored){} try{if(encoder!=null){encoder.stop();encoder.release();}}catch(Exception ignored){} try{if(inputSurface!=null)inputSurface.release();}catch(Exception ignored){} try{if(projection!=null)projection.stop();}catch(Exception ignored){} try{if(in!=null)in.close();}catch(Exception ignored){} try{if(out!=null)out.close();}catch(Exception ignored){} try{if(fd!=null)fd.close();}catch(Exception ignored){} }

    private Notification notification(String text){ Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this); return b.setContentTitle("Projector Mirror Service").setContentText(text).setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build(); }
    private void updateNotification(String text){ ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification(text)); }
    private void createChannel(){ if(Build.VERSION.SDK_INT>=26){ NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"Projector mirror",NotificationManager.IMPORTANCE_LOW); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch); } }
    private void log(String s){ String line=ts()+"  "+s; try(FileOutputStream fos=new FileOutputStream(logFile,true)){ fos.write((line+"\n").getBytes()); }catch(IOException ignored){} Intent i=new Intent("pl.test1.projectormirrorservice.LOG"); i.setPackage(getPackageName()); i.putExtra("line",line); sendBroadcast(i); }
    private String ts(){ return new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date()); }
    private String describe(UsbAccessory a){ if(a==null)return"<null>"; return "manufacturer="+safe(a.getManufacturer())+", model="+safe(a.getModel())+", description="+safe(a.getDescription())+", version="+safe(a.getVersion())+", uri="+safe(a.getUri())+", serial="+safe(a.getSerial()); }
    private String safe(String s){return s==null?"":s;} private String hex(byte[] d,int max){ StringBuilder sb=new StringBuilder(); int lim=Math.min(d.length,max); for(int i=0;i<lim;i++){ if(i>0)sb.append(' '); sb.append(String.format(Locale.US,"%02X",d[i]&255)); } if(d.length>lim) sb.append(" ... +").append(d.length-lim).append(" bytes"); return sb.toString(); }
}
