package br.edu.ifba.via.service;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LocalEmbeddings {
    public static final String MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2";
    private static final String MODEL_REVISION = "a3ded7f4d0d725c6fc251504622d65b2516a3aa1";
    private static final String REVISION = "onnx-spring-ai-2.0.1-mean-l2-v1-" + MODEL_REVISION;
    private static final int DIMENSION = 384;
    private static final String MODEL_BASE = "https://huggingface.co/" + MODEL + "/resolve/" + MODEL_REVISION + "/";

    private final TransformersEmbeddingModel model;

    public LocalEmbeddings(@Value("${via.embedding.cache-dir:data/models}") String cacheDirectory) {
        try {
            model = new TransformersEmbeddingModel();
            model.setTokenizerResource(MODEL_BASE + "tokenizer.json");
            model.setModelResource(MODEL_BASE + "onnx/model.onnx");
            model.setResourceCacheDirectory(cacheDirectory);
            model.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível iniciar o modelo ONNX local " + MODEL, e);
        }
    }

    public synchronized float[] embed(String text) {
        float[] vector = CacheSemantico.validarVetorEmbedding(model.embed(text));
        if (vector.length != DIMENSION) throw new IllegalStateException("Dimensão inesperada do embedding ONNX");
        double norm = 0;
        for (float value : vector) norm += (double) value * value;
        norm = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) vector[i] /= (float) norm;
        return CacheSemantico.validarVetorEmbedding(vector);
    }

    public String revision() { return REVISION; }
    public int dimension() { return DIMENSION; }

    @PreDestroy
    void close() {
        try { model.close(); }
        catch (ai.onnxruntime.OrtException e) { throw new IllegalStateException("Falha ao fechar modelo ONNX", e); }
    }
}
