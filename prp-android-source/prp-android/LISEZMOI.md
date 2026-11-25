# PrP — version Android (1.0-a2)

Bloqueur de contenu adulte verrouillé dans le temps. Architecture détaillée :
document `PrP-architecture-android.md` du projet.

## Contenu
- `app/src/main/kotlin/org/prp/android/`
  - `filtre/Filtre.kt` — moteur de décision (mots-clés, liste HaGeZi NSFW, moteurs non filtrables). Code pur, testé.
  - `GardeService.kt` — garde d'accessibilité : lit la barre d'adresse des navigateurs et bloque.
  - `Protections.kt` — couches système (propriétaire de l'appareil) : DNS privé familial, réglages verrouillés, navigateurs configurés.
  - `Drand.kt`, `Verrou.kt`, `Etat.kt` — verrou temporel à sens unique (rondes drand).
  - `Gardien.kt` — remise en place périodique, réactivation de la garde.
  - `MainActivity.kt`, `BloqueActivity.kt` — écrans.
  - Interface (a2) : `MainActivity.kt` (écran en cartes, fenêtres d'aide, durées en puces), `Vues.kt` (cartes, boutons, puces), `Apparence.kt` (langue et thème clair/sombre, couleurs, durées traduites). Langues : français (par défaut), anglais, russe, dans `res/values*/strings.xml`, générés par `outils/chaines.py` (modifier ce script puis le relancer). Choix de la langue et du thème : icône d'engrenage en haut de l'écran.
  - `detection/` — détection d'images à l'écran (nouveau en a2) : `Detection.kt` (capture + boucle + masque), `Detecteur.kt` (modèle ONNX), `Yolo.kt` (décodage), `Masque.kt` (zones et maintien), `MasqueVue.kt` (fenêtre de masque), `Pretraitement.kt`, `Classes.kt` (ce qui est masqué et les seuils).
- `app/src/main/assets/nsfw.bin` — liste HaGeZi NSFW (82 942 domaines, gzip).
- `app/src/main/assets/nudenet_320n.onnx` — modèle NudeNet 320n (détection d'images), 12 Mo.
- `licences/` et `NOTICES.md` — licences des composants embarqués.
- `outils/installer-android.bat` — installation depuis le PC (adb).
- `outils/secours-essai.bat` — retire la version d'essai, même verrouillée.
- `signature/prp-essai.jks` — clé d'ESSAI (mot de passe `prp-essai`). À remplacer avant publication.

## Installation sur le téléphone (a6)
Au premier lancement, PrP propose deux modes :
- **Mode Fort (recommandé)** : sans PC, Android 11+. L'assistant guide pas à pas (Shizuku, débogage sans fil, comptes Google à retirer un instant) puis rend PrP propriétaire de l'appareil. Code : `Installation.kt`.
- **Mode Simple** : garde d'accessibilité + administrateur de l'appareil, en une minute.
`outils/installer-android.bat` (PC) reste possible pour le Mode Fort.

## Compiler
- **Android Studio** : ouvrir ce dossier, puis Build > Build APK(s).
- **Ligne de commande** : `gradlew assembleDebug assembleRelease` (JDK 17 + SDK Android 34).
- **GitHub** : pousser ce dossier dans un dépôt ; l'APK sort dans l'onglet Actions (`.github/workflows/android.yml`).

Deux variantes :
| Variante | Fichier | Usage |
|---|---|---|
| essai (`debug`) | `app-debug.apk` | tests : débogage USB conservé, retirable avec `secours-essai.bat` |
| réelle (`release`) | `app-release.apk` | le vrai verrou : aucune sortie avant l'échéance |

## Tester (dans cet ordre)
1. Émulateur Android Studio (image « Google APIs », Android 13 ou 14, sans compte) ou téléphone de secours.
2. `outils\installer-android.bat app-debug.apk`
3. Dans PrP : les 5 lignes « Protections » ; ouvrir Chrome → un site visé doit donner « Site bloqué par PrP ».
4. Verrou de 5 minutes, délai de 2 minutes → vérifier : réglages bloqués, garde qui se réactive si on la coupe, redémarrage, puis demande, attente, libération.
5. Seulement ensuite : version réelle sur le vrai téléphone.

## Mettre à jour une version déjà installée (a1 vers a2)
`adb install -r -t app-debug.apk` : PrP garde son rôle de propriétaire, aucun compte à retirer. Ouvre ensuite PrP : la garde se réactive toute seule ; si elle reste éteinte, rallume-la dans Réglages > Accessibilité (le nouveau droit de capture d'écran peut demander une confirmation).

## Tester la détection d'images
1. Android 11 minimum. Dans PrP, la section « Détection d'images » doit dire « activée » et la ligne « modèle chargé » passer en ✔.
2. Ouvre une application avec des images normales : rien ne doit être masqué.
3. Regarde la ligne « Dernière analyse » : elle donne la durée d'une analyse (en ms) et ce qui a été masqué.
4. Après le premier masque, la ligne « Mesure » dit si le masque apparaît dans les captures de ton téléphone (voir `PrP-architecture-android.md`).

## État de vérification
- Le code Kotlin a été contrôlé par le compilateur Kotlin 2.1.21 contre l'API Android 34, et les tests du filtre passent (liste réelle chargée).
- La compilation de l'APK a réussi sur GitHub (version a1). La version a2 (détection d'images) est contrôlée par le compilateur et testée sur la logique pure (16 tests), mais **n'a jamais tourné sur un appareil** : la capture d'écran, le masque et la vitesse du modèle sont à valider sur le téléphone.
