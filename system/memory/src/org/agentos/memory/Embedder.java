package org.agentos.memory;

import ai.onnxruntime.*;
import java.nio.LongBuffer;
import java.util.*;

/**
 * On-device text embeddings: bekko-embedding-v1-a8m (ONNX, 384 dims, 100+ languages; queries and documents may be in
 * different languages). Tokens come from Bpe, an exact port of the model's tokenizer; vectors are the mean of every token's
 * last hidden state (the special tokens included, as the model's pooling config says), L2 normalized. Queries and documents
 * are encoded the same way, with no prefix. Runs locally; no text leaves the device.
 */
final class Embedder implements AutoCloseable {
 /** Which model the stored vectors come from; a change re-embeds everything (MemoryStore.ensureVectors). */
 static final String MODEL_ID="bekko-embedding-v1-a8m@c721113d";
 static final int MAX_TOKENS=512;
 final Bpe tokenizer;final OrtEnvironment env;final OrtSession session;

 Embedder(Bpe tokenizer,String modelPath)throws Exception{
  this.tokenizer=tokenizer;
  env=OrtEnvironment.getEnvironment();
  OrtSession.SessionOptions o=new OrtSession.SessionOptions();o.setIntraOpNumThreads(2);
  session=env.createSession(modelPath,o);
 }

 /** query is kept for callers: this model encodes questions and stored text alike. */
 float[] encode(String text,boolean query)throws Exception{
  int[] ids=tokenizer.encode(text,MAX_TOKENS);
  long[] in=new long[ids.length],mask=new long[ids.length];
  for(int i=0;i<ids.length;i++){in[i]=ids[i];mask[i]=1;}
  long[] shape={1,ids.length};
  Map<String,OnnxTensor> feed=new HashMap<>();
  try{
   feed.put("input_ids",OnnxTensor.createTensor(env,LongBuffer.wrap(in),shape));
   feed.put("attention_mask",OnnxTensor.createTensor(env,LongBuffer.wrap(mask),shape));
   try(OrtSession.Result out=session.run(feed)){
    float[][] h=((float[][][])out.get(0).getValue())[0];float[] v=new float[h[0].length];
    for(float[] t:h)for(int i=0;i<v.length;i++)v[i]+=t[i];
    double n=0;for(float x:v)n+=x*x;n=Math.sqrt(n);if(n>0)for(int i=0;i<v.length;i++)v[i]/=n;return v;
   }
  }finally{for(OnnxTensor t:feed.values())t.close();}
 }

 static byte[] pack(float[] v){java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(v.length*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);for(float x:v)b.putFloat(x);return b.array();}
 static float[] unpack(byte[] a){java.nio.FloatBuffer b=java.nio.ByteBuffer.wrap(a).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] v=new float[b.remaining()];b.get(v);return v;}
 /** Vectors from another model (a different length) score as unrelated instead of failing. */
 static float dot(float[] a,float[] b){if(a.length!=b.length)return -1;float s=0;for(int i=0;i<a.length;i++)s+=a[i]*b[i];return s;}

 @Override public void close(){try{session.close();}catch(Exception ignored){}}
}
