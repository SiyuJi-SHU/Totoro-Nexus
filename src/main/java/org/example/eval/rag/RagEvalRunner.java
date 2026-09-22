package org.example.eval.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.RagService;
import org.example.service.VectorSearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RagEvalRunner {

    private final RagService ragService;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String casesPath;

    public RagEvalRunner(
            RagService ragService,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            @Value("${eval.rag.cases-path:classpath:eval/rag_cases.jsonl}") String casesPath) {
        this.ragService = ragService;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.casesPath = casesPath;
    }

    public RagEvalRun run() throws Exception {
        List<RagEvalCase> cases = loadCases();
        List<RagEvalResult> results = new ArrayList<>();
        for (RagEvalCase evalCase : cases) {
            results.add(evaluateCase(evalCase));
        }

        RagEvalRun run = new RagEvalRun();
        run.setResults(results);
        run.setSummary(RagEvalMetrics.aggregate(results));
        return run;
    }

    private List<RagEvalCase> loadCases() throws Exception {
        Resource resource = resourceLoader.getResource(casesPath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("RAG eval cases not found: " + casesPath);
        }

        List<RagEvalCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                cases.add(objectMapper.readValue(trimmed, RagEvalCase.class));
            }
        }
        return cases;
    }

    private RagEvalResult evaluateCase(RagEvalCase evalCase) {
        RagEvalResult result = new RagEvalResult();
        result.setId(evalCase.getId());
        result.setQuestion(evalCase.getQuestion());
        result.setExpectedSourceFile(evalCase.getExpectedSourceFile());

        Instant start = Instant.now();
        try {
            List<VectorSearchService.SearchResult> searchResults =
                    ragService.retrieveRelevantDocuments(evalCase.getQuestion(), evalCase.getTopK());
            result.setLatencyMs(Duration.between(start, Instant.now()).toMillis());
            fillRetrievalResult(evalCase, searchResults, result);
        } catch (Exception e) {
            result.setLatencyMs(Duration.between(start, Instant.now()).toMillis());
            result.setErrorMessage(e.getMessage());
        }
        return result;
    }

    private void fillRetrievalResult(
            RagEvalCase evalCase,
            List<VectorSearchService.SearchResult> searchResults,
            RagEvalResult result) {
        List<String> retrievedFiles = new ArrayList<>();
        String expected = normalizeFileName(evalCase.getExpectedSourceFile());
        int hitRank = -1;
        StringBuilder retrievedContent = new StringBuilder();

        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult searchResult = searchResults.get(i);
            String sourceFile = firstNonBlank(searchResult.getSourceFile(), searchResult.getSourcePath());
            retrievedFiles.add(sourceFile);
            retrievedContent.append(searchResult.getContent()).append('\n');
            if (hitRank == -1 && normalizeFileName(sourceFile).equals(expected)) {
                hitRank = i + 1;
            }
        }

        result.setRetrievedFiles(retrievedFiles);
        result.setHitRank(hitRank);
        fillKeywordHitRate(evalCase.getExpectedKeywords(), retrievedContent.toString(), result);
    }

    private void fillKeywordHitRate(List<String> expectedKeywords, String retrievedContent, RagEvalResult result) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            result.setKeywordHitRate(1.0);
            return;
        }

        Set<String> matched = new HashSet<>();
        String normalizedContent = retrievedContent.toLowerCase(Locale.ROOT);
        for (String keyword : expectedKeywords) {
            if (keyword != null && normalizedContent.contains(keyword.toLowerCase(Locale.ROOT))) {
                matched.add(keyword);
            }
        }

        result.setMatchedKeywords(new ArrayList<>(matched));
        result.setKeywordHitRate(matched.size() * 1.0 / expectedKeywords.size());
    }

    private String normalizeFileName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }
}
