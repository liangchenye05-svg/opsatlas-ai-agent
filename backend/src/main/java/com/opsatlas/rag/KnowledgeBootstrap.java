package com.opsatlas.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeBootstrap implements ApplicationRunner {
    private final ResourcePatternResolver policies;
    private final KnowledgeService service;
    private final KnowledgeMapper data;
    private final PolicyVectorIndex index;

    public KnowledgeBootstrap(ResourcePatternResolver policies, KnowledgeService service, KnowledgeMapper data, PolicyVectorIndex index) {
        this.policies = policies;
        this.service = service;
        this.data = data;
        this.index = index;
    }

    @Override
    public void run(ApplicationArguments arguments) throws IOException {
        index.ensureCollection();
        index.index(data.chunks());
        for (var policy : policies.getResources("classpath:policies/*.md")) {
            String source = "builtin/" + policy.getFilename();
            if (data.countSource(source) == 0) {
                String content = policy.getContentAsString(StandardCharsets.UTF_8);
                int firstLine = content.indexOf('\n');
                String title = content.substring(0, firstLine).replaceFirst("^# +", "").strip();
                service.ingest(title, source, content.substring(firstLine + 1), null);
            }
        }
    }
}
