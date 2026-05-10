package com.eakp.chat.dto;

import java.time.Instant;
import java.util.List;

public record MessageDto(
    String          id,
    String          role,
    String          content,
    List<SourceDto> sources,
    Double          faithfulness,
    Instant         createdAt
) {}
