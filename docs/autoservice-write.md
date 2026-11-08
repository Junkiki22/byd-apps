# autoservice への書き込み（ドルフィン実車検証）

## 結論（2026-11-08）
shell uid（ADB）から `service call autoservice 6 i32 <dev> i32 <fid> i32 <値>`（tx=6 = setInt）で**書き込める**。
ADB無しのアプリ権限（HAL直叩き）では書き込みが無効化されて空振りしたが、shell 権限なら通る。
car-ha は 1.2.0 から自己ADB（AdbShell）で shell 権限を持っているので、car-ha からも同じことができる。

## 検証内容
- 車: DOLPHIN 2024（DiLink3）、駐車中・電源ON、PC から遠隔ADB（Tailscale 経由 `100.94.159.98:5555`）
- 対象: 内外気切替 write fid `dev1000 / 501219355`（1=内気, 0=外気）。読み戻しは `ac_circ` = `dev1000 / 1077936148`
- write fid を tx=5 で読むと -10011（書き込み専用）

```
service call autoservice 5 i32 1000 i32 1077936148   → 0
service call autoservice 6 i32 1000 i32 501219355 i32 1 → Parcel(00000001)
service call autoservice 5 i32 1000 i32 1077936148   → 1   ← 変わった
service call autoservice 6 i32 1000 i32 501219355 i32 0 → 元に戻した（読み戻し 0）
```

## 書き込み先の出どころ
BYDMate（`app/src/main/assets/competitor-actions.json` と `WriteAllowlist.kt` の LIVE_VALIDATED）。
- エアコン: dev1000（ac_power 501219364 / 温度 501219368 / 風量 501219340 / 内外気 501219355 …）
- 窓: dev1001 fid 1125122104(運転席) / 1125122107(助手席) / 1125122112(左後) / 1125122115(右後)、値 1=開 2=閉 3=停止 4=半開 5=換気
- LIVE_VALIDATED は Leopard 3 / Seagull での確認。**ドルフィンで確認済みは内外気のみ**。
- BYDMate は dev 1004/1006/1007/1009/1011/1012/1013/1014/1016/1023/1032（ライト・駆動・ギア・充電・BMS・タイヤ・電源・ドアロック）への書き込みを禁止している。car-ha も同じ方針にする。

## 制約
- ACC OFF 中は BYD が car-ha を止めるので、電源OFF中の遠隔操作（乗車前エアコン等）はこの方式では不可。
- アプリをインストール/更新すると、純正「オートスタートを無効にする」でそのアプリが ON（起動禁止）に戻る。更新後は毎回 OFF に戻す（`am start -n com.byd.appstartmanagement/.frame.AppStartManagement`）。
