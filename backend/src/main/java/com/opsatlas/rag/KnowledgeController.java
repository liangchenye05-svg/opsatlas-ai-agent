package com.opsatlas.rag;

import com.opsatlas.security.IdentityMapper;
import com.opsatlas.security.IdentityService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final PolicyVectorIndex vectors;
    private final IdentityService identities;

    public KnowledgeController(KnowledgeService knowledge, PolicyVectorIndex vectors, IdentityService identities) {
        this.knowledge = knowledge;
        this.vectors = vectors;
        this.identities = identities;
    }

    @GetMapping("/documents")
    public List<KnowledgeMapper.Document> documents() {
        return knowledge.documents();
    }

    @PostMapping(path="/documents", consumes="multipart/form-data")
    public KnowledgeMapper.Document upload(@AuthenticationPrincipal Jwt jwt, @RequestPart MultipartFile file,
            @RequestParam @NotBlank @Size(max=140) String title) {
        var actor = identities.actor(identities.userId(jwt));
        if (actor.role() != IdentityMapper.Role.HEADQUARTERS) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅总部运营可上传制度");
        }
        String original = file.getOriginalFilename();
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        String extension = name.toLowerCase(Locale.ROOT);
        if (name.length() > 160 || !(extension.endsWith(".md") || extension.endsWith(".txt"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 UTF-8 的 TXT、MD 文档");
        }
        if (file.getSize() > 256_000) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "制度文档最大 256 KB");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.getBytes())).toString();
            return knowledge.ingest(title.strip(), "upload/" + name, text.replaceFirst("^\\uFEFF", ""), actor.id());
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取 UTF-8 文档正文");
        }
    }

    @GetMapping("/search")
    public KnowledgeService.Search search(@RequestParam @NotBlank @Size(max=400) String q,
            @RequestParam(defaultValue="4") @Min(1) @Max(12) int limit) {
        return knowledge.search(q, limit);
    }

    @GetMapping("/answer")
    public Map<String, Object> answer(@RequestParam @NotBlank @Size(max=400) String q) {
        var citations = knowledge.search(q, 4).citations();
        return Map.of("answer", citations.isEmpty() ? "没有找到支持该问题的企业制度。" : "请核对下列制度原文。",
                "grounded", !citations.isEmpty(), "citations", citations);
    }

    @GetMapping("/citations/{id}")
    public KnowledgeMapper.Chunk citation(@PathVariable String id) {
        return knowledge.citation(id);
    }

    @GetMapping("/index")
    public Map<String, Object> index() {
        return vectors.status();
    }
}
