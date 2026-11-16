@echo off
rem Version d'ESSAI uniquement : retire PrP meme pendant un verrou.
rem (Impossible avec la version reelle : c'est le but.)
adb shell dpm remove-active-admin org.prp.android/.AdminReceiver
adb uninstall org.prp.android
