package com.chargeinsight.agent.knowledge;

import java.util.List;

/** Narrow boundary around the configured embedding model, kept separate from vector-store I/O. */
public interface EmbeddingGateway {
    List<float[]> embed(List<String> texts);

    float[] embed(String text);
}
