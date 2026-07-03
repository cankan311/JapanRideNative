package jp.japanride.spinbike;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler bleHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt bikeGatt;
    private BluetoothDevice bikeDevice;
    private boolean scanning = false;
    private final Map<String, BluetoothDevice> scanDevices = new LinkedHashMap<>();

    private SharedPreferences prefs;

    private static final String PREFS = "japan_ride_native";
    private static final String KEY_BIKE_NAME = "bike_name";
    private static final String KEY_BIKE_ADDRESS = "bike_address";
    private static final String KEY_HEART_NAME = "heart_name";
    private static final String KEY_HEART_ADDRESS = "heart_address";

    private BluetoothGatt heartGatt;
    private BluetoothDevice heartDevice;
    private boolean heartScanning = false;
    private final Map<String, BluetoothDevice> heartScanDevices = new LinkedHashMap<>();
    private Integer lastHeart = null;
    private Integer lastHeartBattery = null;

    private static final UUID UUID_HEART_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_HEART_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");

    private static final UUID UUID_FTMS_SERVICE = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_INDOOR_BIKE_DATA = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CSC_SERVICE = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CSC_MEASUREMENT = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_POWER_SERVICE = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_POWER_MEASUREMENT = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_MANUFACTURER_NAME = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_MODEL_NUMBER = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private int lastCscCrankRev = -1;
    private int lastCscCrankTime = -1;
    private int lastPowerCrankRev = -1;
    private int lastPowerCrankTime = -1;
    private Integer lastBattery = null;
    private String bikeType = "未判定";
    private String manufacturer = "—";
    private String modelNumber = "—";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bm == null ? null : bm.getAdapter();

        WebView.setWebContentsDebuggingEnabled(true);
        webView = new WebView(this);
        try { webView.clearCache(true); webView.clearHistory(); } catch (Exception ignored) {}
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);

        webView.addJavascriptInterface(new NativeHeartBridge(), "NativeHeartBridge");
        webView.addJavascriptInterface(new NativeBikeBridge(), "NativeBikeBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !(url.startsWith("file:///android_asset/") || url.startsWith("https://") || url.startsWith("http://"));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installNativeBikeScript();
                installNativeHeartScript();
                sendHeartState(getSavedHeartNameOrDefault(), false, hasSavedHeart() ? "保存済み / 未接続" : "未登録", null, lastHeartBattery, null);
                sendBikeState(false, getSavedBikeNameOrDefault(), hasSavedBike() ? "保存済み / 未接続" : "未登録", null, null, lastBattery, bikeType, null);
                tryAutoReconnectBikeDelayed();
                tryAutoReconnectHeartDelayed();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        requestBasicPermissionsIfNeeded();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestBasicPermissionsIfNeeded() {
        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!req.isEmpty()) requestPermissions(req.toArray(new String[0]), 6704);
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasSavedBike() {
        return prefs.getString(KEY_BIKE_ADDRESS, null) != null;
    }

    private String getSavedBikeNameOrDefault() {
        return prefs.getString(KEY_BIKE_NAME, "未登録");
    }

    private void saveBike(BluetoothDevice device) {
        if (device == null) return;
        String name = safeDeviceName(device);
        prefs.edit().putString(KEY_BIKE_ADDRESS, device.getAddress()).putString(KEY_BIKE_NAME, name).apply();
    }

    private void clearBike() {
        prefs.edit().remove(KEY_BIKE_ADDRESS).remove(KEY_BIKE_NAME).apply();
    }

    @SuppressLint("MissingPermission")
    private String safeDeviceName(BluetoothDevice d) {
        if (d == null) return "名称なし";
        try {
            String n = d.getName();
            return (n == null || n.trim().isEmpty()) ? "名称なし" : n;
        } catch (Exception e) {
            return "名称なし";
        }
    }

    private void installNativeBikeScript() {
        String js = "(function(){\n" +
                " if(window.__JapanRideNativeBikeInstalled)return; window.__JapanRideNativeBikeInstalled=true;\n" +
                " function q(id){return document.getElementById(id)}\n" +
                " function setTxt(id,v){var e=q(id); if(e)e.textContent=v}\n" +
                " window.JapanRideNativeBikeUpdate=function(p){try{\n" +
                "   p=p||{};\n" +
                "   if(typeof setRideInputMode==='function' && p.connected) setRideInputMode('sensor');\n" +
                "   if(window.jrSensorDiag){\n" +
                "     jrSensorDiag.connected=!!p.connected;\n" +
                "     jrSensorDiag.error=p.error||'';\n" +
                "     jrSensorDiag.metrics=jrSensorDiag.metrics||{};\n" +
                "     jrSensorDiag.metrics.type=p.type||jrSensorDiag.metrics.type||'Native BLE';\n" +
                "     jrSensorDiag.metrics.battery=(p.battery==null?jrSensorDiag.metrics.battery:p.battery);\n" +
                "     jrSensorDiag.metrics.manufacturer=p.manufacturer||jrSensorDiag.metrics.manufacturer||'—';\n" +
                "     jrSensorDiag.metrics.model=p.model||jrSensorDiag.metrics.model||'—';\n" +
                "     if(p.rpm!=null) jrSensorDiag.metrics.rpm=Number(p.rpm);\n" +
                "     if(p.speed!=null) jrSensorDiag.metrics.speed=Number(p.speed);\n" +
                "     jrSensorDiag.metrics.lastRx=p.lastRx||jrSensorDiag.metrics.lastRx||'—';\n" +
                "   }\n" +
                "   if(window.jrPhysics && p.rpm!=null){var r=Number(p.rpm); if(isFinite(r)){jrPhysics.lastSensorRpm=r; jrPhysics.lastSensorRx=performance.now();}}\n" +
                "   if(window.jrPhysics && p.speed!=null){var s=Number(p.speed); if(isFinite(s))jrPhysics.lastSensorSpeed=s;}\n" +
                "   try{if(p.name&&p.name!=='未登録')localStorage.setItem('japanRide.sensorName.v1',p.name)}catch(e){}\n" +
                "   setTxt('sensorDeviceName',p.name||'ネイティブセンサー');\n" +
                "   setTxt('sensorDetectedType',p.state||p.type||'Native BLE');\n" +
                "   setTxt('sensorRpm',p.rpm==null?'—':Number(p.rpm).toFixed(1));\n" +
                "   setTxt('sensorSpeed',p.speed==null?'—':Number(p.speed).toFixed(1));\n" +
                "   setTxt('sensorBattery',p.battery==null?'—':Math.round(Number(p.battery))+'%');\n" +
                "   setTxt('sensorLastRx',p.lastRx||'—');\n" +
                "   var stat=q('sensorDiagStatus'); if(stat){stat.textContent=p.connected?'接続中':(p.state||'未接続'); stat.classList.toggle('connected',!!p.connected);}\n" +
                "   try{if(typeof renderSensorDiagUI==='function')renderSensorDiagUI()}catch(e){}\n" +
                "   try{if(typeof renderUserSensorSettings==='function')renderUserSensorSettings()}catch(e){}\n" +
                "   try{if(typeof renderPhysicsSettings==='function')renderPhysicsSettings()}catch(e){}\n" +
                "   try{if(typeof renderUI==='function')renderUI()}catch(e){}\n" +
                " }catch(e){console.warn('NativeBikeUpdate failed',e)}};\n" +
                " window.addEventListener('click',function(e){var t=e.target&&e.target.closest&&e.target.closest('#sensorConnectBtn,#sensorAutoConnectBtn,#sensorDisconnectBtn,#sensorForgetBtn'); if(!t||!window.NativeBikeBridge)return; e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); if(t.id==='sensorConnectBtn')NativeBikeBridge.pairBike(); else if(t.id==='sensorAutoConnectBtn')NativeBikeBridge.reconnectBike(); else if(t.id==='sensorDisconnectBtn')NativeBikeBridge.disconnectBike(); else if(t.id==='sensorForgetBtn')NativeBikeBridge.forgetBike();},true);\n" +
                " try{if(window.NativeBikeBridge)NativeBikeBridge.requestState()}catch(e){}\n" +
                "})();";
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    private void installNativeHeartScript() {
        String js = "(function(){\n" +
                " if(window.__JapanRideNativeHeartInstalled)return; window.__JapanRideNativeHeartInstalled=true;\n" +
                " function q(id){return document.getElementById(id)}\n" +
                " function setTxt(id,v){var e=q(id); if(e)e.textContent=v}\n" +
                " window.JapanRideNativeHeartUpdate=function(p){try{\n" +
                "   p=p||{};\n" +
                "   setTxt('heartSensorNameD6679',p.name||'未登録');\n" +
                "   setTxt('heartSensorStateD6679',(p.state||(p.connected?'接続中':'未接続'))+(p.error?(' / '+p.error):''));\n" +
                "   setTxt('heartSensorHrD6679',p.hr==null?'—':String(Math.round(Number(p.hr))));\n" +
                "   setTxt('heartSensorBatteryD6679',p.battery==null?'—':Math.round(Number(p.battery))+'%');\n" +
                "   if(p.connected){var d=new Date(); setTxt('heartSensorLastRxD6679',('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)+':'+('0'+d.getSeconds()).slice(-2));}\n" +
                "   var pill=q('heartSensorStatusD6679'); if(pill){pill.textContent=p.connected?'接続中':(p.state||'未接続');}\n" +
                "   if(window.jrPhysics && p.hr!=null){var h=Number(p.hr); if(isFinite(h)) jrPhysics.lastHeartRate=h;}\n" +
                "   try{if(typeof renderUI==='function')renderUI()}catch(e){}\n" +
                " }catch(e){console.warn('NativeHeartUpdate failed',e)}};\n" +
                " window.addEventListener('click',function(e){\n" +
                "   var sel='#heartSensorPairBtnD6686,#heartSensorSavedBtnD6686,#heartSensorDisconnectBtnD6686,#heartSensorForgetBtnD6686,'+\n" +
                "           '#heartSensorConnectBtnD6679,#heartSensorAutoBtnD6679,#heartSensorDisconnectBtnD6679,#heartSensorForgetBtnD6679,'+\n" +
                "           '#heartSensorStandardBtnD6685,#heartSensorCompatibleBtnD6685,#heartSensorReconnectBtnD6685,#heartSensorDisconnectBtnD6685';\n" +
                "   var t=e.target&&e.target.closest&&e.target.closest(sel);\n" +
                "   if(!t||!window.NativeHeartBridge)return;\n" +
                "   e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();\n" +
                "   var id=t.id;\n" +
                "   if(id==='heartSensorPairBtnD6686'||id==='heartSensorConnectBtnD6679'||id==='heartSensorStandardBtnD6685'||id==='heartSensorCompatibleBtnD6685') NativeHeartBridge.pairHeart();\n" +
                "   else if(id==='heartSensorSavedBtnD6686'||id==='heartSensorAutoBtnD6679'||id==='heartSensorReconnectBtnD6685') NativeHeartBridge.reconnectHeart();\n" +
                "   else if(id.indexOf('Disconnect')>=0) NativeHeartBridge.disconnectHeart();\n" +
                "   else if(id.indexOf('Forget')>=0) NativeHeartBridge.forgetHeart();\n" +
                " },true);\n" +
                " try{if(window.NativeHeartBridge)NativeHeartBridge.requestHeartState()}catch(e){}\n" +
                "})();";
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    private void tryAutoReconnectBikeDelayed() {
        if (hasSavedBike()) {
            mainHandler.postDelayed(() -> reconnectBikeInternal(false), 900);
        }
    }

    public class NativeHeartBridge {
        @JavascriptInterface public void pairHeart() { mainHandler.post(() -> startHeartScan()); }
        @JavascriptInterface public void reconnectHeart() { mainHandler.post(() -> reconnectHeartInternal(true)); }
        @JavascriptInterface public void disconnectHeart() { mainHandler.post(() -> disconnectHeartInternal("切断しました")); }
        @JavascriptInterface public void forgetHeart() { mainHandler.post(() -> { disconnectHeartInternal("登録解除しました"); clearHeart(); sendHeartState("未登録", false, "登録解除しました", null, null, null); }); }
        @JavascriptInterface public void requestHeartState() { mainHandler.post(() -> sendHeartState(getCurrentHeartName(), isHeartConnected(), isHeartConnected()?"接続中":(hasSavedHeart()?"保存済み / 未接続":"未登録"), lastHeart, lastHeartBattery, null)); }
    }


    private boolean hasSavedHeart() {
        String addr = prefs.getString(KEY_HEART_ADDRESS, null);
        return addr != null && !addr.isEmpty();
    }

    private String getSavedHeartNameOrDefault() {
        return prefs.getString(KEY_HEART_NAME, "未登録");
    }

    private void saveHeart(BluetoothDevice device) {
        if (device == null) return;
        prefs.edit().putString(KEY_HEART_ADDRESS, device.getAddress()).putString(KEY_HEART_NAME, safeDeviceName(device)).apply();
    }

    private void clearHeart() {
        prefs.edit().remove(KEY_HEART_ADDRESS).remove(KEY_HEART_NAME).apply();
        lastHeart = null;
        lastHeartBattery = null;
    }

    private boolean isHeartConnected() {
        return heartGatt != null && heartDevice != null;
    }

    private String getCurrentHeartName() {
        if (heartDevice != null) return safeDeviceName(heartDevice);
        return getSavedHeartNameOrDefault();
    }

    private void tryAutoReconnectHeartDelayed() {
        if (hasSavedHeart()) mainHandler.postDelayed(() -> reconnectHeartInternal(false), 1400);
    }
