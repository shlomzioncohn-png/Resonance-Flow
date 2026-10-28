# models

Local model weights. **Gitignored — never committed.**

## `bge-small-en-v1.5/`

The embedding model used by `src/mind/embeddings` (`BAAI/bge-small-en-v1.5`
-- see that module's README for why). In the image the weights are baked in at
build time (`/opt/models/embeddings/`, pinned revision and sha256, see the
Dockerfile), and that copy is loaded first, so this folder is for local dev only.
If `MODEL_CACHE_DIR` points here (`.env.example`), loading needs no network call;
otherwise it downloads from the Hugging Face Hub on first use.

To populate it locally: download the model folder (e.g. via
`huggingface-cli download BAAI/bge-small-en-v1.5 --local-dir models/bge-small-en-v1.5`,
or a zip export of the same files) and extract it here. Only these files
are actually needed (skip `onnx/`, `pytorch_model.bin` if
`model.safetensors` is present, and any `.cache/` metadata folder):

```
models/bge-small-en-v1.5/
  config.json
  config_sentence_transformers.json
  model.safetensors
  modules.json
  sentence_bert_config.json
  special_tokens_map.json
  tokenizer.json
  tokenizer_config.json
  vocab.txt
  1_Pooling/config.json
```

## `whisper/<size>/`

The ASR model used by `src/mind/multimodal/asr.py`: Whisper `base.en` in
CTranslate2 format (`Systran/faster-whisper-base.en`), the #135 decision. In the
image the weights are baked in at build time (`/opt/models/whisper/`, see the
Dockerfile), so this folder is for local dev only; with `MODEL_CACHE_DIR` pointing
at `models/` (`.env.example`), `asr.py` loads `models/whisper/<WHISPER_MODEL>/`.

The weights are pinned: `asr.py` checks `model.bin` against the sha256 in
`PINNED_WHISPER_MODELS` on every load and refuses any other file
(`ASR_UNAVAILABLE`). Without the folder it downloads that pinned revision here
on first use -- which fails on a network that blocks Hugging Face's CDN (NetFree
does), so place the files by hand: download them on an unfiltered network from
`https://huggingface.co/Systran/faster-whisper-base.en/tree/3d3d5dee26484f91867d81cb899cfcf72b96be6c`
(the pinned revision). Only these four are needed:

```
models/whisper/base.en/
  config.json
  model.bin        # sha256 2a166925539a16005f14ff328359f9b9adb9dc4fb631bb3b227526862e93e2ef
  tokenizer.json
  vocabulary.txt
```

`tiny.en/` and `small.en/` are only needed to re-run the size benchmark
(`benchmarks/asr_benchmark.py`); they are not pinned, so `asr.py` never
downloads them.
