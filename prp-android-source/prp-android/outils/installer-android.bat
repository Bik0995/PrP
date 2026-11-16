@echo off
chcp 65001 >nul
setlocal
rem ------------------------------------------------------------------
rem  PrP Android - installation depuis le PC (une seule fois)
rem  Usage : installer-android.bat [chemin\vers\prp.apk]
rem  Avant : 1) Options pour les developpeurs > Debogage USB active
rem          2) telephone branche en USB, autorisation acceptee a l'ecran
rem          3) TOUS les comptes retires du telephone (Reglages > Comptes),
rem             on les remet juste apres. Android l'exige pour l'etape 3.
rem ------------------------------------------------------------------
set PKG=org.prp.android
set APK=%~1
if "%APK%"=="" set APK=%~dp0prp.apk

where adb >nul 2>&1
if errorlevel 1 (
  echo [ERREUR] adb est introuvable. Telecharge "SDK Platform-Tools" de Google,
  echo          decompresse-le et lance ce script depuis ce dossier.
  exit /b 1
)
if not exist "%APK%" (
  echo [ERREUR] Fichier introuvable : %APK%
  exit /b 1
)

echo [1/4] Installation de PrP...
adb install -r -t "%APK%"
if errorlevel 1 goto echec

echo [2/4] Permission de reactiver la garde toute seule...
adb shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
if errorlevel 1 echo [ATTENTION] Permission refusee. Sur Xiaomi, active aussi "Debogage USB (parametres de securite)".

echo [3/4] PrP devient proprietaire de l'appareil...
adb shell dpm set-device-owner %PKG%/.AdminReceiver
if errorlevel 1 (
  echo.
  echo [ERREUR] Android a refuse. Cause presque toujours : il reste un compte
  echo          sur le telephone ^(Google, Samsung, Xiaomi, Telegram, WhatsApp...^).
  echo          Retire-les tous dans Reglages ^> Comptes, puis relance ce script.
  exit /b 1
)

echo [4/4] Ouverture de PrP...
adb shell am start -n %PKG%/.MainActivity >nul
echo.
echo Termine. Tu peux remettre tes comptes, puis choisir la duree dans PrP.
exit /b 0

:echec
echo [ERREUR] L'installation a echoue. Verifie le cable et l'autorisation USB.
exit /b 1
