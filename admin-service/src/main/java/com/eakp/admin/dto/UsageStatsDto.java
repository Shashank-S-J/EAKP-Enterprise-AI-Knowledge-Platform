package com.eakp.admin.dto;

import java.util.List;
import java.util.Map;

public record UsageStatsDto(
    List<Map<String, Object>> queriesPerDay,
    List<Map<String, Object>> topQuestions,
    long                      activeUsers,
    long                      cacheEntries,
    int                       periodDays
) {}
