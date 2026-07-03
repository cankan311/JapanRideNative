# Japan Ride Native D67-01

Android Studioでこのフォルダを開く用のネイティブアプリ土台です。

## できること

- `app/src/main/assets/index.html` に現在のJapan Ride UIを同梱
- Android WebViewでローカルHTMLとして起動
- Web Bluetoothではなく、Kotlin側の `NativeHeartBridge` から心拍センサーへ接続
- 心拍センサーの登録・保存済みへ接続・切断・登録解除
- BLE標準 Heart Rate Service / Heart Rate Measurement を受信した場合、画面の心拍ゾーンへ反映
- Battery Serviceが取れる場合は電池残量も反映

## 実行手順

1. Android Studioで `JapanRideNative_D67_01` フォルダを開く
2. Sync Now
3. Galaxyなど実機をUSB接続
4. Run
5. 設定 > 心拍センサー > 登録・接続

## 注意

このD67-01は、まずネイティブ化の土台です。
Health Connect連携とFITBOX RPMのネイティブ化は次版で追加する想定です。
