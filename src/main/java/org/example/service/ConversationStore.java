package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;

/** Small local session store. Lives under the existing persisted uploads mount, outside searchable files. */
@Service
public class ConversationStore {
    private final Path root;
    private final ObjectMapper json = new ObjectMapper();
    public ConversationStore(KnowledgeFiles files) { root = files.root().resolve(".conversations"); }
    public static String sessionId(String id) {
        if (id == null || id.isBlank()) return UUID.randomUUID().toString();
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标识无效");
        return id;
    }
    public record Session(List<Map<String,String>> messages, String diagnosisId) {}
    public record StoredDiagnosis(String sessionId, AiOpsService.DiagnosisOutcome outcome) {}
    public synchronized List<String> sessionIds() throws Exception {
        Path folder=path("sessions","inventory").getParent();if(!Files.isDirectory(folder))return List.of();
        try(var entries=Files.list(folder)){return entries.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).map(p->p.getFileName().toString()).filter(n->n.matches("[A-Za-z0-9_-]{1,128}\\.json")).map(n->n.substring(0,n.length()-5)).sorted().limit(1000).toList();}
    }
    private Path path(String group, String id) throws Exception {
        Path target = root.resolve(group).resolve(id + ".json").normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("会话路径无效");
        for (Path p : List.of(root.getParent(), root, target.getParent(), target))
            if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("会话路径不能使用链接");
        return target;
    }
    private Session load(String id) throws Exception {
        Path p = path("sessions", sessionId(id));
        return Files.exists(p) ? json.readValue(Files.readAllBytes(p), Session.class) : new Session(List.of(), null);
    }
    private void writeSession(String id, Session session) throws Exception {
        KnowledgeFiles.atomicWrite(path("sessions", sessionId(id)), json.writeValueAsBytes(session));
    }
    public synchronized List<Map<String,String>> history(String id) throws Exception { return List.copyOf(load(id).messages()); }
    public synchronized void append(String id, String question, String answer) throws Exception {
        Session session = load(id); List<Map<String,String>> messages = new ArrayList<>(session.messages());
        messages.add(Map.of("role", "user", "content", question)); messages.add(Map.of("role", "assistant", "content", answer));
        while (messages.size() > 12) { messages.remove(0); messages.remove(0); }
        writeSession(id, new Session(List.copyOf(messages), session.diagnosisId()));
    }
    public synchronized void save(String id, AiOpsService.DiagnosisOutcome outcome) throws Exception {
        id = sessionId(id);
        KnowledgeFiles.atomicWrite(path("diagnoses", outcome.snapshot().id()), json.writeValueAsBytes(new StoredDiagnosis(id, outcome)));
        writeSession(id, new Session(load(id).messages(), outcome.snapshot().id()));
    }
    public synchronized AiOpsService.DiagnosisOutcome diagnosis(String sessionId, String diagnosisId) throws Exception {
        if (diagnosisId == null || diagnosisId.isBlank()) diagnosisId = load(sessionId).diagnosisId();
        if (diagnosisId == null) return null;
        if (!diagnosisId.matches("[a-fA-F0-9-]{36}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "诊断标识无效");
        Path p = path("diagnoses", diagnosisId);
        if (!Files.exists(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该诊断的证据存档不存在，请重新诊断");
        StoredDiagnosis stored = json.readValue(Files.readAllBytes(p), StoredDiagnosis.class);
        if (!Objects.equals(stored.sessionId(), sessionId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前会话没有此诊断");
        return stored.outcome();
    }
    public synchronized void clear(String id) throws Exception { writeSession(id, new Session(List.of(), null)); }
}
