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
@SuppressLint("MissingPermission")
    private void startHeartScan() {
        requestBasicPermissionsIfNeeded();
        if (!hasBlePermissions()) {
            sendHeartState(getSavedHeartNameOrDefault(), false, "Bluetooth権限待ち", lastHeart, lastHeartBattery, "Bluetooth権限を許可してください");
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            sendHeartState(getSavedHeartNameOrDefault(), false, "Bluetooth OFF", lastHeart, lastHeartBattery, "BluetoothをONにしてください");
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            sendHeartState(getSavedHeartNameOrDefault(), false, "スキャン不可", lastHeart, lastHeartBattery, "BLEスキャナーを開始できません");
            return;
        }
        heartScanDevices.clear();
        heartScanning = true;
        sendHeartState(getSavedHeartNameOrDefault(), false, "心拍センサー検索中", lastHeart, lastHeartBattery, null);
        try {
            scanner.startScan(heartScanCallback);
            bleHandler.postDelayed(() -> stopHeartScanAndShowDialog(), 9000);
        } catch (Exception e) {
            heartScanning = false;
            sendHeartState(getSavedHeartNameOrDefault(), false, "スキャン失敗", lastHeart, lastHeartBattery, e.getMessage());
        }
    }

    private final ScanCallback heartScanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result == null ? null : result.getDevice();
            if (d == null) return;
            // 名前がキャッシュに無くても、スキャンレスポンス側(ScanRecord)に名前がある場合があるため
            // ここでは弾かず全デバイスを候補に残す。表示名はsafeDeviceName/ScanRecordの両方を試す。
            heartScanDevices.put(d.getAddress(), d);
        }
        @Override public void onScanFailed(int errorCode) {
            heartScanning = false;
            sendHeartState(getSavedHeartNameOrDefault(), false, "スキャン失敗", lastHeart, lastHeartBattery, "BLEスキャン失敗: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void stopHeartScanAndShowDialog() {
        if (!heartScanning) return;
        heartScanning = false;
        try { if (scanner != null) scanner.stopScan(heartScanCallback); } catch (Exception ignored) {}
        if (heartScanDevices.isEmpty()) {
            sendHeartState(getSavedHeartNameOrDefault(), false, "見つかりません", lastHeart, lastHeartBattery, "心拍計・スマートウォッチを近くに置いて再検索してください");
            return;
        }
        List<BluetoothDevice> sorted = new ArrayList<>(heartScanDevices.values());
        sorted.sort((a, b) -> scoreHeartDevice(b) - scoreHeartDevice(a));
        final List<BluetoothDevice> devices = sorted.size() > 20 ? sorted.subList(0, 20) : sorted;
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            String n = safeDeviceName(devices.get(i));
            labels[i] = (n.equals("名称なし") ? "（名称非公開の機器）" : n) + "\n" + devices.get(i).getAddress();
        }
        mainHandler.post(() -> new AlertDialog.Builder(this)
                .setTitle("心拍センサーを選択")
                .setItems(labels, (dialog, which) -> connectHeartDevice(devices.get(which)))
                .setNegativeButton("キャンセル", (dialog, which) -> sendHeartState(getSavedHeartNameOrDefault(), false, "未接続", lastHeart, lastHeartBattery, null))
                .show());
    }

    @SuppressLint("MissingPermission")
    private int scoreHeartDevice(BluetoothDevice d) {
        String n = safeDeviceName(d).toLowerCase();
        int s = 0;
        if (n.contains("amazfit") || n.contains("bip") || n.contains("gtr") || n.contains("gts") || n.contains("balance") || n.contains("active") || n.contains("cheetah") || n.contains("t-rex")) s += 100;
        if (n.contains("mi band") || n.contains("miband") || n.contains("xiaomi")) s += 90;
        if (n.contains("polar") || n.contains("garmin") || n.contains("wahoo") || n.contains("suunto") || n.contains("coros")) s += 90;
        if (n.contains("heart") || n.contains("hr") || n.contains("watch") || n.contains("band")) s += 40;
        if (!n.equals("名称なし")) s += 10;
        return s;
    }

    @SuppressLint("MissingPermission")
    private void connectHeartDevice(BluetoothDevice device) {
        if (device == null) return;
        disconnectHeartInternal(null);
        heartDevice = device;
        saveHeart(device);
        sendHeartState(safeDeviceName(device), false, "接続中...", lastHeart, lastHeartBattery, null);
        try { heartGatt = device.connectGatt(this, false, heartGattCallback); }
        catch (Exception e) { sendHeartState(safeDeviceName(device), false, "接続失敗", lastHeart, lastHeartBattery, e.getMessage()); }
    }

    @SuppressLint("MissingPermission")
    private void reconnectHeartInternal(boolean userAction) {
        requestBasicPermissionsIfNeeded();
        if (!hasBlePermissions()) {
            sendHeartState(getSavedHeartNameOrDefault(), false, "Bluetooth権限待ち", lastHeart, lastHeartBattery, "Bluetooth権限を許可してください");
            return;
        }
        String addr = prefs.getString(KEY_HEART_ADDRESS, null);
        if (addr == null || addr.isEmpty()) {
            sendHeartState("未登録", false, "保存済みなし", lastHeart, lastHeartBattery, userAction ? "先に心拍センサーを登録してください" : null);
            return;
        }
        try {
            BluetoothDevice d = bluetoothAdapter.getRemoteDevice(addr);
            connectHeartDevice(d);
        } catch (Exception e) {
            sendHeartState(getSavedHeartNameOrDefault(), false, "再接続失敗", lastHeart, lastHeartBattery, e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void disconnectHeartInternal(String state) {
        try { if (heartGatt != null) heartGatt.disconnect(); } catch (Exception ignored) {}
        try { if (heartGatt != null) heartGatt.close(); } catch (Exception ignored) {}
        heartGatt = null;
        heartDevice = null;
        if (state != null) sendHeartState(getSavedHeartNameOrDefault(), false, state, lastHeart, lastHeartBattery, null);
    }

    private final BluetoothGattCallback heartGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                heartGatt = gatt;
                heartDevice = gatt.getDevice();
                saveHeart(heartDevice);
                sendHeartState(safeDeviceName(heartDevice), true, "サービス確認中", lastHeart, lastHeartBattery, null);
                try { gatt.discoverServices(); } catch (Exception e) { sendHeartState(safeDeviceName(heartDevice), false, "サービス確認失敗", lastHeart, lastHeartBattery, e.getMessage()); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sendHeartState(getCurrentHeartName(), false, "未接続", lastHeart, lastHeartBattery, null);
                try { gatt.close(); } catch (Exception ignored) {}
                if (gatt == heartGatt) heartGatt = null;
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                sendHeartState(safeDeviceName(gatt.getDevice()), true, "接続に失敗しました", lastHeart, lastHeartBattery, "端末を近づけて、もう一度お試しください（詳細コード: " + status + "）");
                return;
            }
            startHeartServices(gatt);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleHeartCharacteristic(gatt, characteristic);
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleHeartCharacteristic(gatt, characteristic);
        }
    };

    @SuppressLint("MissingPermission")
    private void startHeartServices(BluetoothGatt gatt) {
        lastHeartBattery = readUint8(gatt, UUID_BATTERY_SERVICE, UUID_BATTERY_LEVEL, lastHeartBattery);
        boolean ok = subscribe(gatt, UUID_HEART_SERVICE, UUID_HEART_MEASUREMENT);
        if (ok) sendHeartState(safeDeviceName(gatt.getDevice()), true, "接続中 / 心拍待ち", lastHeart, lastHeartBattery, null);
        else sendHeartState(safeDeviceName(gatt.getDevice()), true, "接続済み / 心拍通知なし", lastHeart, lastHeartBattery, "この機種は本アプリ向けに心拍数を送信していない可能性があります（機種の仕様による場合があります）");
    }

    private void handleHeartCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic c) {
        UUID u = c.getUuid();
        byte[] v = c.getValue();
        if (v == null) return;
        if (UUID_HEART_MEASUREMENT.equals(u)) parseHeartRate(gatt, v);
        else if (UUID_BATTERY_LEVEL.equals(u) && v.length > 0) {
            lastHeartBattery = v[0] & 0xff;
            sendHeartState(trueName(gatt), true, lastHeart == null ? "接続中 / 心拍待ち" : "心拍受信中", lastHeart, lastHeartBattery, null);
        }
    }

    private String trueName(BluetoothGatt gatt) {
        return gatt == null || gatt.getDevice() == null ? getCurrentHeartName() : safeDeviceName(gatt.getDevice());
    }

    private void parseHeartRate(BluetoothGatt gatt, byte[] b) {
        try {
            if (b.length < 2) return;
            int flags = u8(b, 0);
            int hr = ((flags & 0x01) != 0 && b.length >= 3) ? u16(b, 1) : u8(b, 1);
            if (hr < 25 || hr > 240) return;
            lastHeart = hr;
            sendHeartState(trueName(gatt), true, "心拍受信中", lastHeart, lastHeartBattery, null);
        } catch (Exception e) {
            sendHeartState(trueName(gatt), true, "心拍解析エラー", lastHeart, lastHeartBattery, e.getMessage());
        }
    }

    public class NativeBikeBridge {
        @JavascriptInterface public void pairBike() { mainHandler.post(() -> startBikeScan()); }
        @JavascriptInterface public void reconnectBike() { mainHandler.post(() -> reconnectBikeInternal(true)); }
        @JavascriptInterface public void disconnectBike() { mainHandler.post(() -> disconnectBikeInternal("切断しました")); }
        @JavascriptInterface public void forgetBike() { mainHandler.post(() -> { disconnectBikeInternal("登録解除しました"); clearBike(); sendBikeState(false, "未登録", "登録解除しました", null, null, null, "未接続", null); }); }
        @JavascriptInterface public void requestState() { mainHandler.post(() -> sendBikeState(isBikeConnected(), getCurrentBikeName(), isBikeConnected()?"接続中":(hasSavedBike()?"保存済み / 未接続":"未登録"), null, null, lastBattery, bikeType, null)); }
    }

    private boolean isBikeConnected() {
        return bikeGatt != null && bikeDevice != null;
    }

    private String getCurrentBikeName() {
        if (bikeDevice != null) return safeDeviceName(bikeDevice);
        return getSavedBikeNameOrDefault();
    }

    @SuppressLint("MissingPermission")
    private void startBikeScan() {
        requestBasicPermissionsIfNeeded();
        if (!hasBlePermissions()) {
            sendBikeState(false, getSavedBikeNameOrDefault(), "Bluetooth権限待ち", null, null, lastBattery, bikeType, "Bluetooth権限を許可してください");
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            sendBikeState(false, getSavedBikeNameOrDefault(), "Bluetooth OFF", null, null, lastBattery, bikeType, "BluetoothをONにしてください");
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            sendBikeState(false, getSavedBikeNameOrDefault(), "スキャン不可", null, null, lastBattery, bikeType, "BLEスキャナーを開始できません");
            return;
        }
        scanDevices.clear();
        scanning = true;
        Toast.makeText(this, "センサー検索中。FITBOXを漕いで起こしてください", Toast.LENGTH_SHORT).show();
        sendBikeState(false, getSavedBikeNameOrDefault(), "センサー検索中", null, null, lastBattery, bikeType, null);
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        scanner.startScan(scanCallback);
        bleHandler.postDelayed(() -> stopScanAndShowChoices(), 8000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { addScanResult(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { if (results != null) for (ScanResult r : results) addScanResult(r); }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            sendBikeState(false, getSavedBikeNameOrDefault(), "スキャン失敗", null, null, lastBattery, bikeType, "BLEスキャン失敗: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void addScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        BluetoothDevice d = result.getDevice();
        String name = safeDeviceName(d);
        String addr = d.getAddress();
        if (addr == null) return;
        // 名前なしも残すが、運動系/既知名っぽいものを上位に出しやすくするため、Mapは追加順を維持。
        scanDevices.put(addr, d);
    }

    @SuppressLint("MissingPermission")
    private void stopScanAndShowChoices() {
        if (!scanning) return;
        scanning = false;
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        if (scanDevices.isEmpty()) {
            sendBikeState(false, getSavedBikeNameOrDefault(), "見つかりません", null, null, lastBattery, bikeType, "センサーが見つかりません。FITBOXを漕ぎながら再検索してください");
            Toast.makeText(this, "センサーが見つかりません", Toast.LENGTH_SHORT).show();
            return;
        }
        List<BluetoothDevice> list = new ArrayList<>(scanDevices.values());
        list.sort((a, b) -> scoreDevice(b) - scoreDevice(a));
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            BluetoothDevice d = list.get(i);
            labels[i] = safeDeviceName(d) + "\n" + d.getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("センサーを選択")
                .setItems(labels, (dialog, which) -> connectBike(list.get(which), true))
                .setNegativeButton("キャンセル", (dialog, which) -> sendBikeState(false, getSavedBikeNameOrDefault(), "未接続", null, null, lastBattery, bikeType, null))
                .show();
    }

    @SuppressLint("MissingPermission")
    private int scoreDevice(BluetoothDevice d) {
        String n = safeDeviceName(d).toLowerCase();
        int s = 0;
        if (n.contains("fitbox")) s += 100;
        if (n.contains("bike") || n.contains("fitness") || n.contains("ftms")) s += 40;
        if (!n.equals("名称なし")) s += 10;
        return s;
    }

    @SuppressLint("MissingPermission")
    private void reconnectBikeInternal(boolean userAction) {
        requestBasicPermissionsIfNeeded();
        if (!hasBlePermissions()) {
            sendBikeState(false, getSavedBikeNameOrDefault(), "Bluetooth権限待ち", null, null, lastBattery, bikeType, "Bluetooth権限を許可してください");
            return;
        }
String address = prefs.getString(KEY_BIKE_ADDRESS, null);
        if (address == null || bluetoothAdapter == null) {
            sendBikeState(false, getSavedBikeNameOrDefault(), "保存済みなし", null, null, lastBattery, bikeType, userAction ? "先にセンサー登録してください" : null);
            return;
        }
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            connectBike(device, false);
        } catch (Exception e) {
            sendBikeState(false, getSavedBikeNameOrDefault(), "再接続失敗", null, null, lastBattery, bikeType, e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void connectBike(BluetoothDevice device, boolean save) {
        if (device == null) return;
        disconnectBikeInternal(null);
        bikeDevice = device;
        lastCscCrankRev = lastCscCrankTime = lastPowerCrankRev = lastPowerCrankTime = -1;
        bikeType = "接続中";
        if (save) saveBike(device);
        sendBikeState(false, safeDeviceName(device), "接続中...", null, null, lastBattery, bikeType, null);
        try {
            bikeGatt = device.connectGatt(this, false, bikeGattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            sendBikeState(false, safeDeviceName(device), "接続失敗", null, null, lastBattery, bikeType, e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void disconnectBikeInternal(String state) {
        try { if (bikeGatt != null) bikeGatt.disconnect(); } catch (Exception ignored) {}
        try { if (bikeGatt != null) bikeGatt.close(); } catch (Exception ignored) {}
        bikeGatt = null;
        bikeDevice = null;
        if (state != null) sendBikeState(false, getSavedBikeNameOrDefault(), state, 0.0, null, lastBattery, bikeType, null);
    }

    private final BluetoothGattCallback bikeGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bikeGatt = gatt;
                bikeDevice = gatt.getDevice();
                saveBike(bikeDevice);
                sendBikeState(true, safeDeviceName(bikeDevice), "サービス確認中", null, null, lastBattery, bikeType, null);
                try { gatt.discoverServices(); } catch (Exception e) { sendBikeState(false, safeDeviceName(bikeDevice), "サービス確認失敗", null, null, lastBattery, bikeType, e.getMessage()); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sendBikeState(false, getCurrentBikeName(), "未接続", 0.0, null, lastBattery, bikeType, null);
                try { gatt.close(); } catch (Exception ignored) {}
                if (gatt == bikeGatt) bikeGatt = null;
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                sendBikeState(true, safeDeviceName(gatt.getDevice()), "サービス取得失敗", null, null, lastBattery, bikeType, "GATT status " + status);
                return;
            }
            startBikeServices(gatt);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleCharacteristic(gatt, characteristic);
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristic(gatt, characteristic);
        }
    };

    @SuppressLint("MissingPermission")
    private void startBikeServices(BluetoothGatt gatt) {
        boolean subscribed = false;
        manufacturer = readUtf8(gatt, UUID_DEVICE_INFO_SERVICE, UUID_MANUFACTURER_NAME, manufacturer);
        modelNumber = readUtf8(gatt, UUID_DEVICE_INFO_SERVICE, UUID_MODEL_NUMBER, modelNumber);
        lastBattery = readUint8(gatt, UUID_BATTERY_SERVICE, UUID_BATTERY_LEVEL, lastBattery);

        if (subscribe(gatt, UUID_FTMS_SERVICE, UUID_INDOOR_BIKE_DATA)) { bikeType = "FTMS / Indoor Bike"; subscribed = true; }
        if (subscribe(gatt, UUID_CSC_SERVICE, UUID_CSC_MEASUREMENT)) { if (!subscribed) bikeType = "CSC / Cadence"; subscribed = true; }
        if (subscribe(gatt, UUID_POWER_SERVICE, UUID_POWER_MEASUREMENT)) { if (!subscribed) bikeType = "Cycling Power"; subscribed = true; }

        if (subscribed) {
            sendBikeState(true, safeDeviceName(gatt.getDevice()), "接続中 / 漕ぎ待ち", null, null, lastBattery, bikeType, null);
        } else {
            sendBikeState(true, safeDeviceName(gatt.getDevice()), "接続済み / RPM通知なし", null, null, lastBattery, "未対応", "FTMS/CSC/Cycling Powerの通知が見つかりません");
        }
    }

    @SuppressLint("MissingPermission")
    private boolean subscribe(BluetoothGatt gatt, UUID serviceId, UUID charId) {
        try {
            BluetoothGattService s = gatt.getService(serviceId);
            if (s == null) return false;
            BluetoothGattCharacteristic c = s.getCharacteristic(charId);
            if (c == null) return false;
            gatt.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(UUID_CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(d);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private String readUtf8(BluetoothGatt gatt, UUID serviceId, UUID charId, String fallback) {
        try {
            BluetoothGattService s = gatt.getService(serviceId);
            if (s == null) return fallback;
            BluetoothGattCharacteristic c = s.getCharacteristic(charId);
            if (c == null) return fallback;
            byte[] v = c.getValue();
            if (v != null && v.length > 0) return new String(v, StandardCharsets.UTF_8);
            gatt.readCharacteristic(c);
        } catch (Exception ignored) {}
        return fallback;
    }

    @SuppressLint("MissingPermission")
    private Integer readUint8(BluetoothGatt gatt, UUID serviceId, UUID charId, Integer fallback) {
        try {
            BluetoothGattService s = gatt.getService(serviceId);
            if (s == null) return fallback;
            BluetoothGattCharacteristic c = s.getCharacteristic(charId);
            if (c == null) return fallback;
            byte[] v = c.getValue();
            if (v != null && v.length > 0) return v[0] & 0xff;
            gatt.readCharacteristic(c);
        } catch (Exception ignored) {}
        return fallback;
    }

    private void handleCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic c) {
        UUID u = c.getUuid();
        byte[] v = c.getValue();
        if (v == null) return;
        if (UUID_INDOOR_BIKE_DATA.equals(u)) parseIndoorBike(gatt, v);
        else if (UUID_CSC_MEASUREMENT.equals(u)) parseCsc(gatt, v);
        else if (UUID_POWER_MEASUREMENT.equals(u)) parsePower(gatt, v);
        else if (UUID_BATTERY_LEVEL.equals(u) && v.length > 0) {
            lastBattery = v[0] & 0xff;
            sendBikeState(true, safeDeviceName(gatt.getDevice()), "接続中", null, null, lastBattery, bikeType, null);
        }
    }

    private int u8(byte[] b, int o) { return b[o] & 0xff; }
    private int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private int s16(byte[] b, int o) { int v = u16(b, o); return v > 32767 ? v - 65536 : v; }

    private void parseIndoorBike(BluetoothGatt gatt, byte[] b) {
        try {
            if (b.length < 2) return;
            int flags = u16(b, 0);
            int o = 2;
            Double speed = null;
            Double rpm = null;
            if ((flags & 0x0001) == 0 && b.length >= o + 2) { speed = u16(b, o) / 100.0; o += 2; }
            if ((flags & 0x0002) != 0 && b.length >= o + 2) o += 2; // average speed
            if ((flags & 0x0004) != 0 && b.length >= o + 2) { rpm = u16(b, o) / 2.0; o += 2; }
            if ((flags & 0x0008) != 0 && b.length >= o + 2) o += 2;
            if ((flags & 0x0010) != 0 && b.length >= o + 3) o += 3;
            if ((flags & 0x0020) != 0 && b.length >= o + 2) o += 2;
            if ((flags & 0x0040) != 0 && b.length >= o + 2) o += 2;
            if ((flags & 0x0080) != 0 && b.length >= o + 2) o += 2;
            bikeType = "FTMS / Indoor Bike";
            if (rpm != null || speed != null) sendBikeState(true, safeDeviceName(gatt.getDevice()), "RPM受信中", rpm, speed, lastBattery, bikeType, null);
        } catch (Exception e) {
            sendBikeState(true, safeDeviceName(gatt.getDevice()), "FTMS解析エラー", null, null, lastBattery, bikeType, e.getMessage());
        }
    }

    private void parseCsc(BluetoothGatt gatt, byte[] b) {
        try {
            if (b.length < 1) return;
            int flags = u8(b, 0);
            int o = 1;
            if ((flags & 0x01) != 0 && b.length >= o + 6) o += 6; // wheel data
            if ((flags & 0x02) != 0 && b.length >= o + 4) {
                int rev = u16(b, o); o += 2;
                int time = u16(b, o);
                Double rpm = calcRpm(lastCscCrankRev, rev, lastCscCrankTime, time, 65536);
                lastCscCrankRev = rev;
                lastCscCrankTime = time;
                bikeType = "CSC / Cadence";
                if (rpm != null) sendBikeState(true, safeDeviceName(gatt.getDevice()), "RPM受信中", rpm, null, lastBattery, bikeType, null);
            }
        } catch (Exception e) {
            sendBikeState(true, safeDeviceName(gatt.getDevice()), "CSC解析エラー", null, null, lastBattery, bikeType, e.getMessage());
        }
    }

    private void parsePower(BluetoothGatt gatt, byte[] b) {
        try {
            if (b.length < 4) return;
            int flags = u16(b, 0);
            int o = 2;
            int power = s16(b, o); o += 2;
            if ((flags & 0x0001) != 0 && b.length >= o + 1) o += 1;
            if ((flags & 0x0004) != 0 && b.length >= o + 2) o += 2;
            if ((flags & 0x0010) != 0 && b.length >= o + 6) o += 6;
            Double rpm = null;
            if ((flags & 0x0020) != 0 && b.length >= o + 4) {
                int rev = u16(b, o); o += 2;
                int time = u16(b, o);
                rpm = calcRpm(lastPowerCrankRev, rev, lastPowerCrankTime, time, 65536);
                lastPowerCrankRev = rev;
                lastPowerCrankTime = time;
            }
            bikeType = "Cycling Power";
            if (rpm != null) sendBikeState(true, safeDeviceName(gatt.getDevice()), "RPM受信中", rpm, null, lastBattery, bikeType, null);
            else sendBikeState(true, safeDeviceName(gatt.getDevice()), "Power受信中 " + power + "W", null, null, lastBattery, bikeType, null);
        } catch (Exception e) {
            sendBikeState(true, safeDeviceName(gatt.getDevice()), "Power解析エラー", null, null, lastBattery, bikeType, e.getMessage());
        }
    }

    private Double calcRpm(int prevRev, int rev, int prevTime, int time, int mod) {
        if (prevRev < 0 || prevTime < 0) return null;
        int revDiff = rev - prevRev;
        if (revDiff < 0) revDiff += mod;
        int timeDiff = time - prevTime;
        if (timeDiff <= 0) timeDiff += 65536;
        if (timeDiff <= 0 || revDiff < 0) return null;
        double rpm = revDiff * 60.0 / (timeDiff / 1024.0);
        if (!Double.isFinite(rpm) || rpm < 0 || rpm > 300) return null;
        return rpm;
    }

    private void sendHeartState(String name, boolean connected, String state, Integer hr, Integer battery, String error) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("native", true);
            payload.put("name", name == null ? "未登録" : name);
            payload.put("connected", connected);
            payload.put("state", state == null ? "未接続" : state);
            if (hr != null) payload.put("hr", hr);
            if (battery != null) payload.put("battery", battery);
            if (error != null && !error.isEmpty()) payload.put("error", error);
            String js = "window.JapanRideNativeHeartUpdate && window.JapanRideNativeHeartUpdate(" + payload.toString() + ");";
            mainHandler.post(() -> webView.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    private void sendBikeState(boolean connected, String name, String state, Double rpm, Double speed, Integer battery, String type, String error) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("native", true);
            payload.put("connected", connected);
            payload.put("name", name == null ? "未登録" : name);
            payload.put("state", state == null ? "未接続" : state);
            if (rpm != null) payload.put("rpm", rpm);
            if (speed != null) payload.put("speed", speed);
            if (battery != null) payload.put("battery", battery);
            if (type != null) payload.put("type", type);
            payload.put("manufacturer", manufacturer);
            payload.put("model", modelNumber);
            payload.put("lastRx", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN).format(new java.util.Date()));
            if (error != null && !error.isEmpty()) payload.put("error", error);
            String js = "window.JapanRideNativeBikeUpdate && window.JapanRideNativeBikeUpdate(" + payload.toString() + ");";
            mainHandler.post(() -> webView.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        disconnectBikeInternal(null);
        disconnectHeartInternal(null);
        super.onDestroy();
    }
}
