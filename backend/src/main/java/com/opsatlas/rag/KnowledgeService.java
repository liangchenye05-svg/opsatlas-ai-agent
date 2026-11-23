package com.opsatlas.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeService {
    public record Search(List<KnowledgeMapper.Chunk> lexical, List<KnowledgeMapper.Chunk> vector,
                         List<KnowledgeMapper.Chunk> citations) {}

    private final KnowledgeMapper data;
    private final PolicyVectorIndex vectors;

    public KnowledgeService(KnowledgeMapper data, PolicyVectorIndex vectors) {
        this.data = data;
        this.vectors = vectors;
    }

    public List<KnowledgeMapper.Document> documents() {
        return data.documents();
    }

    public KnowledgeMapper.Chunk citation(String id) {
        var chunk = data.chunk(id);
        if (chunk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "制度片段不存在");
        }
        return chunk;
    }

    @Transactional
    public KnowledgeMapper.Document ingest(String title, String source, String text, Long uploader) {
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档没有可读取正文");
        }
        var doc = new KnowledgeMapper.Document(UUID.randomUUID().toString(), title, source, uploader);
        data.addDocument(doc);
        var chunks = new ArrayList<KnowledgeMapper.Chunk>();
        for (String paragraph : text.split("\\R\\s*\\R")) {
            String body = paragraph.strip();
            for (int start = 0; start < body.length(); start += 360) {
                var chunk = new KnowledgeMapper.Chunk(UUID.randomUUID().toString(), doc.id(), title, source,
                        chunks.size() + 1, body.substring(start, Math.min(start + 360, body.length())));
                data.addChunk(chunk);
                chunks.add(chunk);
            }
        }
        vectors.index(chunks);
        return doc;
    }

    public Search search(String question, int limit) {
        var terms = new HashSet<>(TextFeatures.terms(question));
        var all = data.chunks();
        if (terms.isEmpty() || all.isEmpty()) {
            return new Search(List.of(), List.of(), List.of());
        }
        Map<String, List<String>> words = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        double length = 0;
        for (var chunk : all) {
            var tokens = TextFeatures.terms(chunk.title() + " " + chunk.content());
            words.put(chunk.id(), tokens);
            length += tokens.size();
            for (String token : new HashSet<>(tokens)) {
                df.merge(token, 1, Integer::sum);
            }
        }
        double averageLength = length / all.size();
        Map<String, Double> scores = new HashMap<>();
        for (var chunk : all) {
            var tokens = words.get(chunk.id());
            Map<String, Long> frequencies = tokens.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            double score = 0;
            int overlap = 0;
            for (String term : terms) {
                long tf = frequencies.getOrDefault(term, 0L);
                if (tf != 0) {
                    overlap++;
                    int frequency = df.get(term);
                    double idf = Math.log1p((all.size() - frequency + 0.5) / (frequency + 0.5));
                    score += idf * 2.2 * tf / (tf + 1.2 * (0.25 + 0.75 * tokens.size() / averageLength));
                }
            }
            if (overlap >= 2) {
                scores.put(chunk.id(), score);
            }
        }
        var known = all.stream().collect(Collectors.toMap(KnowledgeMapper.Chunk::id, Function.identity()));
        var lexical = scores.keySet().stream().sorted(java.util.Comparator.<String>comparingDouble(scores::get).reversed()
                .thenComparing(Function.identity())).limit(12).toList();
        var dense = vectors.query(question, 12).stream().map(PolicyVectorIndex.Match::id).filter(known::containsKey).toList();
        var fused = new LinkedHashMap<String, Double>();
        for (List<String> ranking : List.of(lexical, dense)) {
            for (int i = 0; i < ranking.size(); i++) {
                fused.merge(ranking.get(i), 1.0 / (60 + i + 1), Double::sum);
            }
        }
        var citations = fused.entrySet().stream().filter(entry -> scores.containsKey(entry.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(entry -> -scores.get(entry.getKey())))
                .limit(limit).map(entry -> known.get(entry.getKey())).toList();
        return new Search(lexical.stream().limit(limit).map(known::get).toList(),
                dense.stream().limit(limit).map(known::get).toList(), citations);
    }
}
