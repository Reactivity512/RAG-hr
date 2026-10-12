package com.github.reactivity512.rag.vector;

import java.util.List;
import java.util.Map;

/**
 * Точка коллекции: id, dense-вектор и payload.
 */
public record ResumeVectorPoint(String id, List<Float> denseVector, Map<String, Object> payload) {
}
