# models

Local model weights. **Gitignored — never committed.**

## `bge-small-en-v1.5/`

The embedding model used by `src/mind/embeddings` (`BAAI/bge-small-en-v1.5`
-- see that module's README for why). If this directory exists,
`mind.embeddings.embeddings` loads it directly with no network call at all
(`EMBEDDING_MODEL_LOCAL_DIR`); otherwise it falls back to downloading
`BAAI/bge-small-en-v1.5` from the Hugging Face Hub on first use.

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
