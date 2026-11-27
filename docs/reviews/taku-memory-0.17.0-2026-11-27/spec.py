"""How each candidate model is run: ONNX file, pooling, and the query/document prefixes its authors specify.
bge: bge-small-zh-v1.5 (before); e5: multilingual-e5-small; a25/a8: bekko-embedding-v1-a25m/-a8m; eg: embeddinggemma-300m (q4);
jz: jina-embeddings-v2-base-zh. Each sits in models_dir/<name>/ with tokenizer.json."""
SPEC = {
 'bge': dict(file='model.onnx', pool='cls', q='为这个句子生成表示以用于检索相关文章：', d=''),
 'e5':  dict(file='model.onnx', pool='mean', q='query: ', d='passage: '),
 'a25': dict(file='model.onnx', pool='mean', q='', d=''),
 'a8':  dict(file='model.onnx', pool='mean', q='', d=''),
 'eg':  dict(file='model_q4.onnx', pool='out1', q='task: search result | query: ', d='title: none | text: '),
 'jz':  dict(file='model.onnx', pool='mean', q='', d=''),
}
