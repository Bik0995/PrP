# Composants embarqués

| Composant | Rôle | Licence |
|---|---|---|
| NudeNet 320n (`nudenet_320n.onnx`), github.com/notAI-tech/NudeNet | détection d'images | Le fichier LICENSE livré avec le paquet est l'AGPL-3.0 (copie dans `licences/NudeNet-LICENSE.txt`), alors que les métadonnées du paquet indiquent MIT : à clarifier auprès des auteurs avant publication ; en attendant, traité comme AGPL-3.0 |
| HaGeZi NSFW (`nsfw.bin`), github.com/hagezi/dns-blocklists | liste de sites pour adultes | GPL-3.0 (à revérifier avant publication) |
| ONNX Runtime (`onnxruntime-android`) | exécution du modèle | MIT |
| Material Icons (Google), fichiers `res/drawable/ic_*.xml` sauf `ic_prp` | icônes de l'interface | Apache 2.0 (copie dans `licences/MaterialIcons-LICENSE.txt`) |

Conséquence pratique : pour publier PrP, le plus simple est de le placer sous AGPL-3.0 (compatible avec la GPL-3.0 de la liste). Ce n'est pas un avis juridique.
