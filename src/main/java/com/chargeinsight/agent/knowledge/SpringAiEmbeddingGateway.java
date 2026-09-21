package com.chargeinsight.agent.knowledge;

import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Spring AI adapter; resolving the provider is deferred until a dense operation is explicitly requested. */
@Service
public class SpringAiEmbeddingGateway implements EmbeddingGateway {
    private final ObjectProvider<EmbeddingModel> embeddingModels;

    public SpringAiEmbeddingGateway(ObjectProvider<EmbeddingModel> embeddingModels) {
        this.embeddingModels = embeddingModels;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embeddingModel().embed(texts);
    }

    @Override
    public float[] embed(String text) {
        return embeddingModel().embed(text);
    }

    private EmbeddingModel embeddingModel() {
        EmbeddingModel model = embeddingModels.getIfAvailable();
        if (model == null) throw new IllegalStateException("未配置 EmbeddingModel");
        return model;
    }
}
