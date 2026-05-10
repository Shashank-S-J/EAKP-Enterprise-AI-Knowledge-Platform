package com.eakp.admin.dto;

import java.util.List;
import java.util.Map;

public record RagQualityDto(
    double              avgFaithfulness,
    double              hallucinationRate,
    long                lowFaithfulnessCount,
    long                totalAnswers,
    List<Map<String, Object>> faithfulnessDistribution
) {}
