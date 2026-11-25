"""Convertit le modèle NSFWJS MobileNetV2 (Keras 2.2.4 / TF.js, MIT) en ONNX, entrée NCHW [1,3,224,224] 0..1."""
import json, sys, numpy as np, onnx
from onnx import helper, TensorProto, numpy_helper

src, dst = sys.argv[1], sys.argv[2]
m = json.load(open(f"{src}/model.json"))
buf = open(f"{src}/group1-shard1of1", "rb").read()
poids, off = {}, 0
for w in m["weightsManifest"][0]["weights"]:
    n = int(np.prod(w["shape"])) if w["shape"] else 1
    q = w.get("quantization")
    if q:
        size = {"uint8": 1, "uint16": 2}[q["dtype"]]
        a = np.frombuffer(buf, dtype=q["dtype"], count=n, offset=off).astype(np.float32)
        a = a * q["scale"] + q["min"]
        off += n * size
    else:
        a = np.frombuffer(buf, dtype=np.float32, count=n, offset=off).copy(); off += n * 4
    poids[w["name"]] = a.reshape(w["shape"])
assert off == len(buf), (off, len(buf))

layers = m["modelTopology"]["model_config"]["config"]["layers"]
noeuds, inits = [], []
forme = {}   # nom -> (C, H, W)
def init(nom, arr):
    inits.append(numpy_helper.from_array(arr.astype(np.float32), nom)); return nom
def pads_same(h, w, kh, kw, sh, sw):
    oh, ow = -(-h // sh), -(-w // sw)
    ph = max((oh - 1) * sh + kh - h, 0); pw = max((ow - 1) * sw + kw - w, 0)
    return [ph // 2, pw // 2, ph - ph // 2, pw - pw // 2], oh, ow

for L in layers:
    t, c, nom = L["class_name"], L["config"], L["name"]
    ent = [x[0] for x in L["inbound_nodes"][0]] if L["inbound_nodes"] else []
    if t == "InputLayer":
        _, H, W, C = c["batch_input_shape"]; forme[nom] = (C, H, W); entree = nom; continue
    x = ent[0]; C, H, W = forme[x]
    if t in ("Conv2D", "DepthwiseConv2D"):
        kh, kw = c["kernel_size"]; sh, sw = c["strides"]
        if t == "Conv2D":
            k = poids[f"{nom}/kernel"]; O = k.shape[3]; wt = k.transpose(3, 2, 0, 1); grp = 1
        else:
            k = poids[f"{nom}/depthwise_kernel"]; dm = k.shape[3]; O = C * dm
            wt = k.transpose(2, 3, 0, 1).reshape(O, 1, kh, kw); grp = C
        if c["padding"] == "same": p, oh, ow = pads_same(H, W, kh, kw, sh, sw)
        else: p, oh, ow = [0, 0, 0, 0], (H - kh) // sh + 1, (W - kw) // sw + 1
        ins = [x, init(nom + "_w", wt)]
        if c.get("use_bias"): ins.append(init(nom + "_b", poids[f"{nom}/bias"]))
        sortie = nom if c.get("activation", "linear") == "linear" else nom + "_pre"
        noeuds.append(helper.make_node("Conv", ins, [sortie], kernel_shape=[kh, kw], strides=[sh, sw], pads=p, group=grp))
        if sortie != nom:
            assert c["activation"] == "relu"; noeuds.append(helper.make_node("Relu", [sortie], [nom]))
        forme[nom] = (O, oh, ow)
    elif t == "BatchNormalization":
        g = poids.get(f"{nom}/gamma", np.ones(C)); b = poids.get(f"{nom}/beta", np.zeros(C))
        noeuds.append(helper.make_node("BatchNormalization", [x, init(nom+"_g", g), init(nom+"_b", b),
            init(nom+"_m", poids[f"{nom}/moving_mean"]), init(nom+"_v", poids[f"{nom}/moving_variance"])], [nom], epsilon=c["epsilon"]))
        forme[nom] = forme[x]
    elif t == "ReLU":
        mx = c.get("max_value")
        if mx is None: noeuds.append(helper.make_node("Relu", [x], [nom]))
        else: noeuds.append(helper.make_node("Clip", [x, init(nom+"_lo", np.array(0.0)), init(nom+"_hi", np.array(float(mx)))], [nom]))
        forme[nom] = forme[x]
    elif t == "ZeroPadding2D":
        (pt, pb), (pl, pr) = c["padding"]
        noeuds.append(helper.make_node("Pad", [x, numpy_helper.from_array(np.array([0,0,pt,pl,0,0,pb,pr], np.int64), nom+"_p").name], [nom]))
        inits.append(numpy_helper.from_array(np.array([0,0,pt,pl,0,0,pb,pr], np.int64), nom+"_p"))
        forme[nom] = (C, H + pt + pb, W + pl + pr)
    elif t == "Add":
        noeuds.append(helper.make_node("Add", ent, [nom])); forme[nom] = forme[x]
    elif t == "AveragePooling2D":
        kh, kw = c["pool_size"]; sh, sw = c["strides"]; assert c["padding"] == "valid"
        noeuds.append(helper.make_node("AveragePool", [x], [nom], kernel_shape=[kh, kw], strides=[sh, sw]))
        forme[nom] = (C, (H - kh)//sh + 1, (W - kw)//sw + 1)
    elif t == "Flatten":
        assert forme[x][1:] == (1, 1), forme[x]
        noeuds.append(helper.make_node("Flatten", [x], [nom])); forme[nom] = (C, 1, 1)
    elif t == "Dropout":
        noeuds.append(helper.make_node("Identity", [x], [nom])); forme[nom] = forme[x]
    elif t == "Dense":
        k = poids[f"{nom}/kernel"]; b = poids[f"{nom}/bias"]
        act = c["activation"]; sortie = nom if act == "linear" else nom + "_pre"
        noeuds.append(helper.make_node("Gemm", [x, init(nom+"_w", k), init(nom+"_b", b)], [sortie]))
        if act == "relu": noeuds.append(helper.make_node("Relu", [sortie], [nom]))
        elif act == "softmax": noeuds.append(helper.make_node("Softmax", [sortie], [nom], axis=1))
        forme[nom] = (k.shape[1], 1, 1)
    else:
        raise SystemExit("couche non gérée : " + t)
    dernier = nom

g = helper.make_graph(noeuds, "nsfwjs_mobilenet_v2",
    [helper.make_tensor_value_info("entree", TensorProto.FLOAT, [1, 3, 224, 224])],
    [helper.make_tensor_value_info("probas", TensorProto.FLOAT, [1, 5])], inits)
for n in g.node:
    n.input[:] = ["entree" if i == entree else i for i in n.input]
    n.output[:] = ["probas" if o == dernier else o for o in n.output]
mod = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)], producer_name="PrP")
mod.ir_version = 8
onnx.checker.check_model(mod)
onnx.save(mod, dst)
print("ok", dst, len(noeuds), "nœuds")
