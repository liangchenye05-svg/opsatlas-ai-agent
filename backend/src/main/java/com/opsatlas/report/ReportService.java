package com.opsatlas.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsatlas.agent.AgentService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportService {
    public record SessionView(String id, String question, String state, JsonNode draft, String failure, LocalDateTime createdAt) {}
    public record ReportView(String id, String analysisId, JsonNode content, LocalDateTime confirmedAt) {}

    private final ReportMapper data;
    private final ObjectMapper json;

    public ReportService(ReportMapper data, ObjectMapper json) {
        this.data = data;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        data.recover();
    }

    @Transactional
    public String begin(long owner, String question) {
        String id = UUID.randomUUID().toString();
        data.begin(id, owner, question);
        data.audit(owner, "analysis_started", id, "success");
        return id;
    }

    public void audit(long owner, String action, String target, String outcome) {
        data.audit(owner, action, target, outcome);
    }

    @Transactional
    public SessionView ready(long owner, String id, AgentService.Analysis draft) {
        data.draft(id, serialize(draft));
        data.audit(owner, "draft_created", id, "success");
        return view(data.session(id, owner));
    }

    @Transactional
    public void failed(long owner, String id, String reason) {
        if (data.fail(id, reason) == 1) {
            data.audit(owner, "analysis_failed", id, "failure");
        }
    }

    @Transactional
    public ReportView confirm(long owner, String id) {
        var session = data.lock(id, owner);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析会话不存在");
        }
        if (session.state().equals("CONFIRMED")) {
            return view(data.report(id, owner));
        }
        if (!session.state().equals("DRAFT")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话没有可确认的周报草稿");
        }
        data.addReport(UUID.randomUUID().toString(), owner, id, session.draft());
        data.confirm(id);
        data.audit(owner, "report_confirmed", id, "success");
        return view(data.report(id, owner));
    }

    public List<SessionView> sessions(long owner) {
        return data.sessions(owner).stream().map(this::view).toList();
    }

    public List<ReportView> reports(long owner) {
        return data.reports(owner).stream().map(this::view).toList();
    }

    public List<ReportMapper.Audit> auditRows(long owner) {
        return data.auditRows(owner);
    }

    private SessionView view(ReportMapper.Session s) {
        return new SessionView(s.id(), s.question(), s.state(), parse(s.draft()), s.failure(), s.createdAt());
    }

    private ReportView view(ReportMapper.Report r) {
        return new ReportView(r.id(), r.analysisId(), parse(r.content()), r.confirmedAt());
    }

    private String serialize(AgentService.Analysis draft) {
        try {
            return json.writeValueAsString(draft);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize analysis", error);
        }
    }

    private JsonNode parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not read saved analysis", error);
        }
    }
}
