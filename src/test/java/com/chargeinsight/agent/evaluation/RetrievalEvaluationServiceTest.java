package com.chargeinsight.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.chargeinsight.agent.knowledge.MetricKnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RetrievalEvaluationServiceTest {
    @Test
    void evaluatesKeywordTfIdfBm25AndHybridStrategiesAgainstLabelledCases() {
        var service = new RetrievalEvaluationService(new MetricKnowledgeService(), new ObjectMapper());

        var report = service.run();

        assertThat(report.caseCount()).isEqualTo(665);
        assertThat(report.strategies()).containsKeys(MetricKnowledgeService.RetrievalStrategy.values());
        report.strategies().values().forEach(metrics -> {
            assertThat(metrics.hitAt3()).isBetween(0.0, 1.0);
            assertThat(metrics.recallAt5()).isBetween(0.0, 1.0);
            assertThat(metrics.mrrAt5()).isBetween(0.0, 1.0);
        });
        assertThat(service.run(RetrievalEvaluationService.EvaluationSplit.HOLDOUT).caseCount()).isEqualTo(13);
        assertThat(service.run(RetrievalEvaluationService.EvaluationSplit.COLLOQUIAL_HOLDOUT).caseCount()).isEqualTo(24);
        assertThat(service.run(RetrievalEvaluationService.EvaluationSplit.COLLOQUIAL_SYNTHETIC).caseCount()).isEqualTo(480);
        assertThat(service.run(RetrievalEvaluationService.EvaluationSplit.DEVELOPMENT).caseCount()).isEqualTo(28);
        assertThat(service.run(RetrievalEvaluationService.EvaluationSplit.REGRESSION).caseCount()).isEqualTo(120);
        assertThatIllegalStateException().isThrownBy(() -> service.audit(RetrievalEvaluationService.EvaluationSplit.COLLOQUIAL_HOLDOUT))
                .withMessage("当前构造方式不支持 Dense/RRF 评测");
        System.out.println("RETRIEVAL_EVALUATION=" + report);
    }
}
