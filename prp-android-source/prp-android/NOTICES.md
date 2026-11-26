# Composants embarqués

| Composant | Rôle | Licence |
|---|---|---|
| NSFWJS, modèle MobileNetV2 (`assets/nsfwjs_mobilenet_v2.onnx`), github.com/infinitered/nsfwjs, paquet npm `nsfwjs` 4.3.0 | classement des images à l'écran | MIT (copie dans `licences/NSFWJS-LICENSE.txt`). Converti de TF.js en ONNX par `outils/convertir_modele.py`, sans changer les poids (écart maximal avec TF.js : 2e-5). |
| The Block List Project, liste « porn » (`assets/sites.bin`), github.com/blocklistproject/Lists | liste de sites pour adultes (≈ 950 000 domaines, sous forme de hachages) | Unlicense / domaine public (copie dans `licences/BlockListProject-LICENSE.txt`) ; l'en-tête du fichier indique MIT. Construite par `outils/liste_sites.py`. |
| Liste « adult » de l'Université Toulouse Capitole UT1 (dsi.ut-capitole.fr/blacklists, copie github.com/olbat/ut1-blacklists) | ≈ 194 000 domaines de plus, dans toutes les langues, gardés seulement si leur nom contient aussi un indice adulte | CC BY-SA 4.0 : `assets/sites.bin` est une base de données adaptée, diffusée sous la même licence (détails et modifications dans `licences/UT1-LISTE.txt`). Le code de PrP n'est pas concerné. |
| ONNX Runtime (`onnxruntime-android`) | exécution du modèle | MIT |
| Shizuku API (`dev.rikka.shizuku:api` et `provider` 13.1.5), github.com/RikkaApps/Shizuku-API | Mode Fort sans PC (débogage sans fil) | MIT selon les métadonnées du dépôt (à revérifier avant publication) |
| Material Icons (Google), fichiers `res/drawable/ic_*.xml` sauf `ic_prp` | icônes de l'interface | Apache 2.0 (copie dans `licences/MaterialIcons-LICENSE.txt`) |

Aucun code sous licence « copyleft » (GPL, AGPL) : le code de PrP peut rester fermé ou être publié sous la licence de son choix, à condition de joindre ces avis de licence. Ce n'est pas un avis juridique.

Composants retirés en 1.0-a7 : NudeNet 320n (AGPL-3.0, basé sur Ultralytics YOLOv8) et la liste HaGeZi NSFW (GPL-3.0).
