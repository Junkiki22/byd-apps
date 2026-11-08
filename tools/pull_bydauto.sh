#!/bin/bash
# 車から BYD 純正 HAL(android.hardware.bydauto.*) を含む framework jar を探して取り出す
A=C:/Android/platform-tools/adb.exe; D=100.94.159.98:5555
OUT=${1:-./bydfw}  # 出力先(引数で指定)
mkdir -p $OUT; cd $OUT
$A connect $D | tail -1
$A -s $D shell 'echo $BOOTCLASSPATH; echo; echo $SYSTEMSERVERCLASSPATH' > classpath.txt
cat classpath.txt
# クラスパス上の jar と、名前に byd/auto を含む jar を全部候補にする
CANDS=$( (tr ':' '\n' < classpath.txt; $A -s $D shell 'ls /system/framework/*.jar /system/framework/*/*.jar 2>/dev/null' ) | tr -d '\r' | grep '\.jar$' | sort -u)
for j in $CANDS; do $A -s $D pull "$j" . >/dev/null 2>&1; done
# 手元で bydauto を含む jar を探す
for f in *.jar; do unzip -p "$f" "classes*.dex" 2>/dev/null | grep -a -q "Landroid/hardware/bydauto" && echo "命中: $f"; done
ls -la
