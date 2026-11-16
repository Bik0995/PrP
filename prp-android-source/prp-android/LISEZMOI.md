# PrP — version Android (1.0-a1)

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
- `app/src/main/assets/nsfw.bin` — liste HaGeZi NSFW (82 942 domaines, gzip).
- `outils/installer-android.bat` — installation depuis le PC (adb).
- `outils/secours-essai.bat` — retire la version d'essai, même verrouillée.
- `signature/prp-essai.jks` — clé d'ESSAI (mot de passe `prp-essai`). À remplacer avant publication.

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

## État de vérification
- Le code Kotlin a été contrôlé par le compilateur Kotlin 2.1.21 contre l'API Android 34, et les tests du filtre passent (liste réelle chargée).
- L'APK n'a **pas** encore été assemblé ni lancé sur un appareil : le premier test sur émulateur est l'étape suivante.
