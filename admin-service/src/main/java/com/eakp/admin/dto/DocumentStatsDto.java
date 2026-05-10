package com.eakp.admin.dto;

import java.util.List;
import java.util.Map;

public record DocumentStatsDto(
    List<Map<String, Object>> statusBreakdown,
    List<Map<String, Object>> topDocumentsByChunks,
    List<Map<String, Object>> failedDocuments,
    double                    avgChunksPerDocument
) {}
