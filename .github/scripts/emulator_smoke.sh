#!/bin/bash
# Exécuté dans l'émulateur par reactivecircus/android-emulator-runner.
set -x
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r cd.smartbudget.fc.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.log
mkdir -p screens
adb pull /sdcard/Android/data/cd.smartbudget.fc/files/screens/. screens/ || true
adb logcat -d -s AndroidRuntime:E > crash.log || true
ls -la screens
# Échec si l'instrumentation ne se termine pas par OK
grep -q "^OK (" instrument.log
