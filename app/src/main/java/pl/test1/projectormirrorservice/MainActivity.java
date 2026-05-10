package pl.test1.projectormirrorservice;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.text.method.ScrollingMovementMethod;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "pl.test1.projectormirrorservice.USB_PERMISSION";
    private static final int REQ_MEDIA_PROJECTION = 2001;
    private UsbManager usbManager;
    private UsbAccessory currentAccessory;
    private MediaProjectionManager projectionManager;
    private TextView logView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("pl.test1.projectormirrorservice.LOG".equals(intent.getAction())) appendLog(intent.getStringExtra("line"));
        }
    };

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbAccessory a = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            appendLog("USB permission result: granted=" + granted + ", accessory=" + describe(a));
            if (granted && a != null) currentAccessory = a;
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        usbManager = (UsbManager)getSystemService(USB_SERVICE);
        projectionManager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        registerReceivers();
        buildUi();
        appendLog("ProjectorMirrorService 0.5 Activity start");
        appendLog("Intent action: " + getIntent().getAction());
        UsbAccessory a = getIntent().getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if (a != null) { currentAccessory = a; appendLog("Accessory from intent: " + describe(a)); }
        requestNotificationsIfNeeded();
        detectUsb();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i); setIntent(i);
        appendLog("onNewIntent action: " + i.getAction());
        UsbAccessory a = i.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if (a != null) { currentAccessory = a; appendLog("Accessory from new intent: " + describe(a)); }
        detectUsb();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(logReceiver); } catch(Exception ignored) {}
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored) {}
    }

    private void registerReceivers() {
        IntentFilter lf = new IntentFilter("pl.test1.projectormirrorservice.LOG");
        IntentFilter uf = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, lf, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(usbPermissionReceiver, uf, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, lf); registerReceiver(usbPermissionReceiver, uf);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(12),dp(12),dp(12),dp(12));
        TextView title = new TextView(this); title.setText("Projector Mirror Service 0.6"); title.setTextSize(18); root.addView(title);
        TextView note = new TextView(this); note.setText("Praca idzie w Foreground Service. Po zgodzie ekran może zniknąć, ale log ma zapisywać się dalej."); note.setTextSize(12); root.addView(note);
        LinearLayout r1 = new LinearLayout(this); r1.setOrientation(LinearLayout.HORIZONTAL);
        Button detect = btn("Wykryj USB"); detect.setOnClickListener(v -> detectUsb()); r1.addView(detect, wt());
        Button usb = btn("Zgoda USB"); usb.setOnClickListener(v -> requestUsbPermission()); r1.addView(usb, wt());
        Button start = btn("START ekran"); start.setOnClickListener(v -> requestProjection()); r1.addView(start, wt());
        root.addView(r1);
        LinearLayout r2 = new LinearLayout(this); r2.setOrientation(LinearLayout.HORIZONTAL);
        Button stop = btn("STOP usługi"); stop.setOnClickListener(v -> stopService(new Intent(this, MirrorService.class))); r2.addView(stop, wt());
        Button load = btn("Wczytaj log"); load.setOnClickListener(v -> loadLog()); r2.addView(load, wt());
        Button copy = btn("Kopiuj log"); copy.setOnClickListener(v -> copyLog()); r2.addView(copy, wt());
        root.addView(r2);
        logView = new TextView(this); logView.setTextSize(12); logView.setTextIsSelectable(true); logView.setMovementMethod(new ScrollingMovementMethod());
        ScrollView sv = new ScrollView(this); sv.addView(logView); root.addView(sv, new LinearLayout.LayoutParams(-1,0,1f));
        setContentView(root);
    }

    private Button btn(String s) { Button b = new Button(this); b.setText(s); return b; }
    private LinearLayout.LayoutParams wt() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2002);
        }
    }

    private void detectUsb() {
        UsbAccessory[] list = usbManager.getAccessoryList();
        if (list == null || list.length == 0) { appendLog("No USB accessories detected."); return; }
        appendLog("USB accessories detected: " + list.length);
        for (int i=0;i<list.length;i++) appendLog("["+i+"] " + describe(list[i]));
        currentAccessory = choose(list);
        appendLog("Selected accessory: " + describe(currentAccessory));
        if (currentAccessory != null && usbManager.hasPermission(currentAccessory)) appendLog("Already has USB permission.");
    }

    private UsbAccessory choose(UsbAccessory[] list) {
        for (UsbAccessory a: list) if ("Mirroring".equalsIgnoreCase(safe(a.getManufacturer())) && "gan mirroring".equalsIgnoreCase(safe(a.getModel()))) return a;
        return list[0];
    }

    private void requestUsbPermission() {
        if (currentAccessory == null) detectUsb();
        if (currentAccessory == null) { appendLog("Cannot request USB permission: no accessory."); return; }
        if (usbManager.hasPermission(currentAccessory)) { appendLog("USB permission already granted."); return; }
        Intent intent = new Intent(ACTION_USB_PERMISSION); intent.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT; if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        usbManager.requestPermission(currentAccessory, PendingIntent.getBroadcast(this,0,intent,flags));
        appendLog("Requested USB permission.");
    }

    private void requestProjection() {
        if (currentAccessory == null) detectUsb();
        if (currentAccessory == null) { appendLog("START blocked: no USB accessory."); return; }
        if (!usbManager.hasPermission(currentAccessory)) { appendLog("START blocked: no USB permission."); requestUsbPermission(); return; }
        appendLog("Requesting MediaProjection permission...");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                appendLog("MediaProjection granted. Starting MirrorService.");
                Intent svc = new Intent(this, MirrorService.class); svc.setAction(MirrorService.ACTION_START);
                svc.putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode); svc.putExtra(MirrorService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
            } else appendLog("MediaProjection denied/cancelled.");
            return;
        }
        super.onActivityResult(requestCode,resultCode,data);
    }

    private void loadLog() {
        try {
            File f = new File(getExternalFilesDir(null), "projector_mirror_service_log.txt");
            if (!f.exists()) { appendLog("Service log missing: " + f.getAbsolutePath()); return; }
            if (Build.VERSION.SDK_INT >= 26) logView.setText(new String(Files.readAllBytes(f.toPath())));
            else appendLog("Pull by ADB: " + f.getAbsolutePath());
        } catch(Exception e) { appendLog("loadLog error: " + e); }
    }

    private void copyLog() { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ProjectorMirrorService log", logView.getText())); appendLog("Visible log copied."); }
    private void appendLog(String s) { if (s == null) return; handler.post(() -> { logView.append(s+"\n"); int sc = logView.getLayout()==null?0:logView.getLayout().getLineTop(logView.getLineCount())-logView.getHeight(); logView.scrollTo(0,Math.max(0,sc)); }); }
    private String describe(UsbAccessory a) { if (a==null) return "<null>"; return "manufacturer="+safe(a.getManufacturer())+", model="+safe(a.getModel())+", description="+safe(a.getDescription())+", version="+safe(a.getVersion())+", uri="+safe(a.getUri())+", serial="+safe(a.getSerial()); }
    private String safe(String s) { return s==null?"":s; }
}
