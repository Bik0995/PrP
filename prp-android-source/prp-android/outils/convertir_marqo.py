"""Télécharge le modèle Marqo/nsfw-image-detection-384 (Apache 2.0, Hugging Face) et le convertit
en ONNX pour PrP. Lancé par .github/workflows/modele-marqo.yml (le poste de travail n'a pas
accès à Hugging Face). Produit : marqo_nsfw_384.onnx et marqo_nsfw_384.json (taille d'entrée,
normalisation, ordre des classes)."""
import json, numpy as np, torch, timm, onnxruntime as ort

m = timm.create_model("hf_hub:Marqo/nsfw-image-detection-384", pretrained=True).eval()
cfg = timm.data.resolve_model_data_config(m)
noms = m.pretrained_cfg.get("label_names")
print("configuration :", cfg, "classes :", noms)
c, h, l = cfg["input_size"]
x = torch.rand(2, c, h, l)
torch.onnx.export(m, x[:1], "marqo_nsfw_384.onnx", input_names=["entree"], output_names=["logits"],
                  opset_version=17, do_constant_folding=True)
with torch.no_grad():
    ref = m(x).numpy()
s = ort.InferenceSession("marqo_nsfw_384.onnx")
sortie = np.concatenate([s.run(None, {"entree": x[i:i + 1].numpy()})[0] for i in range(2)])
ecart = float(abs(ref - sortie).max())
print("écart PyTorch / ONNX :", ecart)
assert ecart < 1e-3
json.dump({"taille": [h, l], "moyenne": list(cfg["mean"]), "ecart_type": list(cfg["std"]),
           "interpolation": cfg.get("interpolation"), "classes": noms, "ecart_onnx": ecart},
          open("marqo_nsfw_384.json", "w"), indent=1)
