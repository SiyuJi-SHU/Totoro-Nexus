package org.example.service;

import org.example.dto.EvidenceDocument;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Local BM25 text matching. No vector database or model calls; source changes are read immediately. */
@Service
public class KeywordSearchService {
    private final KnowledgeFiles files;
    private final DocumentChunkService chunks;
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]+|[a-zA-Z0-9][a-zA-Z0-9_:./-]*");
    private static final Set<String> STOP = Set.of("the","a","an","is","are","to","of","in","on","and","or","for","with","from","this","that","please","how","what","why","help","me","my","it","be","as","at","by","we","you","can","could","would","should","return","exact","including","query","default","问题","怎么","如何","一下","帮我","我们","这个","什么","目前","一下子");
    public KeywordSearchService(KnowledgeFiles files, DocumentChunkService chunks) { this.files = files; this.chunks = chunks; }
    public static List<String> terms(String query) {
        if (query == null) return List.of();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        var matcher = WORD.matcher(query.substring(0, Math.min(query.length(), 6000)));
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT).replaceAll("[.:/_-]+$", "");
            if (word.matches("[\\p{IsHan}]+") && word.length() > 2) {
                for (int i = 0; i < word.length() - 1; i++) add(terms, word.substring(i, i + 2));
            } else { add(terms, word); for (String part : word.split("[:./_-]+")) add(terms, part); }
        }
        return terms.stream().limit(64).toList();
    }
    private static void add(Set<String> terms, String term) { if (term.length() > 1 && !STOP.contains(term) && !term.matches("[0-9]+")) terms.add(term); }
    private List<EvidenceDocument> corpus() throws Exception {
        List<EvidenceDocument> result = new ArrayList<>();
        for (var source : files.sources()) {
            for (var chunk : chunks.chunkDocument(source.content(), source.name())) {
                result.add(document(source.name(), chunk.getTitle(), chunk.getChunkIndex(), chunk.getContent(), "keyword", null, null, null));
            }
        }
        return result;
    }
    public List<EvidenceDocument> search(String query, int limit) throws Exception {
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return List.of();
        List<EvidenceDocument> corpus = corpus();
        List<List<String>> tokens = corpus.stream().map(d -> allTerms(d.content())).toList();
        double average = tokens.stream().mapToInt(List::size).average().orElse(1);
        Map<String, Long> df = new HashMap<>();
        for (String term : queryTerms) df.put(term, tokens.stream().filter(t -> t.contains(term)).count());
        List<EvidenceDocument> matches = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            var doc = corpus.get(i); var words = tokens.get(i);
            String heading = (doc.sourceFile() + " " + Objects.toString(doc.title(), "")).toLowerCase(Locale.ROOT);
            double score = 0;
            for (String term : queryTerms) {
                long frequency = words.stream().filter(term::equals).count();
                double idf = Math.log(1 + (corpus.size() - df.get(term) + .5) / (df.get(term) + .5));
                if (frequency > 0) score += idf * frequency * 2.2 / (frequency + 1.2 * (.25 + .75 * words.size() / Math.max(average, 1)));
                if (heading.contains(term)) score += idf * 1.5;
            }
            if (score > 0) matches.add(document(doc.sourceFile(), doc.title(), doc.chunkIndex(), doc.content(), "keyword", null, null, score));
        }
        return matches.stream().sorted(Comparator.comparingDouble((EvidenceDocument d) -> d.keywordScore()).reversed()
                .thenComparing(EvidenceDocument::id)).limit(Math.max(1, Math.min(limit, 10))).toList();
    }
    private static List<String> allTerms(String text) {
        // Preserve term frequencies for BM25, including technical identifiers and CJK bigrams.
        List<String> result = new ArrayList<>(); var matcher = WORD.matcher(text);
        while (matcher.find()) result.addAll(terms(matcher.group()));
        return result;
    }
    public List<EvidenceDocument> validateVectors(List<VectorSearchService.SearchResult> results) throws Exception {
        List<KnowledgeFiles.Source> sources = files.sources();
        List<EvidenceDocument> valid = new ArrayList<>();
        for (var hit : results) {
            if (hit.getContent() == null || hit.getContent().isBlank()) continue;
            String sourceName = Objects.toString(hit.getSourceFile(), "").replace('\\', '/');
            if (sourceName.isBlank()) continue;
            var matching = sources.stream().filter(s -> s.name().equals(sourceName) || s.name().endsWith("/" + sourceName) || sourceName.endsWith("/" + s.name())).toList();
            if (matching.size() != 1) continue;
            var excerpt = KnowledgeFiles.originalExcerpt(matching.get(0).content(), hit.getContent());
            if (excerpt.isEmpty()) continue;
            valid.add(document(matching.get(0).name(), hit.getTitle(), hit.getChunkIndex(), excerpt.get(), "vector",
                    (double) hit.getScore(), hit.getRerankScore(), null));
        }
        return List.copyOf(valid);
    }
    /** Add whole adjacent sections from identified sources, rather than retrieving 100 unrelated chunks. */
    public List<EvidenceDocument> expand(List<EvidenceDocument> selected) throws Exception {
        Map<String, EvidenceDocument> result = new LinkedHashMap<>(); int length = 0;
        for (var doc : selected) { if (length + doc.content().length() <= 12000) { result.put(doc.id(), doc); length += doc.content().length(); } }
        if (result.isEmpty()) return List.of();
        Set<String> names = selected.stream().map(EvidenceDocument::sourceFile).collect(Collectors.toSet());
        List<EvidenceDocument> corpus = corpus();
        Map<String,String> originals = new HashMap<>();
        files.sources().forEach(s -> originals.put(s.name(), s.content()));
        // Reserve space for useful checks and their prerequisites before adjacent exposition.
        // Never expand a weak hit into an entire runbook.
        Map<String, Integer> additions = new HashMap<>();
        for (var doc : corpus.stream().sorted(Comparator.comparingInt(d -> operational(d) ? 0 : nearby(d, selected, originals) ? 1 : 2)).toList()) {
            if (!names.contains(doc.sourceFile()) || result.containsKey(doc.id())) continue;
            if (selected.stream().anyMatch(s -> s.sourceFile().equals(doc.sourceFile()) && s.content().contains(doc.content()))) continue;
            boolean nearby = nearby(doc, selected, originals);
            boolean operational = operational(doc);
            if ((nearby || operational) && additions.getOrDefault(doc.sourceFile(), 0) < 2
                    && result.size() < 9 && length + doc.content().length() <= 12000) {
                result.put(doc.id(), document(doc.sourceFile(), doc.title(), doc.chunkIndex(), doc.content(), "source", null, null, null));
                length += doc.content().length();
                additions.merge(doc.sourceFile(), 1, Integer::sum);
            }
        }
        return List.copyOf(result.values());
    }
    private static boolean operational(EvidenceDocument doc) {
        return Objects.toString(doc.title(), "").toLowerCase(Locale.ROOT)
                .matches(".*(troubleshoot|resolution|verification|warning|prerequisite|recovery|investigat|排查|处置|恢复|前提|风险).*");
    }
    private static boolean nearby(EvidenceDocument doc, List<EvidenceDocument> selected, Map<String,String> originals) {
        String source = originals.get(doc.sourceFile());
        if (source == null) return false;
        int at = source.indexOf(doc.content());
        if (at < 0) return false;
        return selected.stream().filter(s -> s.sourceFile().equals(doc.sourceFile())).anyMatch(s -> {
            int hit = source.indexOf(s.content());
            return hit >= 0 && at <= hit + s.content().length() + 200 && at + doc.content().length() >= hit - 200;
        });
    }
    public static String normal(String text) { return text.replaceAll("\\s+", " ").trim(); }
    public static EvidenceDocument document(String source, String title, Integer index, String content, String method, Double distance, Double rerank, Double keyword) {
        return new EvidenceDocument("D-" + KnowledgeFiles.digest(source + "\n" + content).substring(0, 20), source,
                Objects.toString(title, "正文"), index, content, method, distance, rerank, keyword);
    }
}
