package org.example.dto;

/** Immutable excerpt. IDs identify the exact source content, not a model-created citation. */
public record EvidenceDocument(String id, String sourceFile, String title, Integer chunkIndex,
        String content, String method, Double vectorDistance, Double rerankScore, Double keywordScore) {}
