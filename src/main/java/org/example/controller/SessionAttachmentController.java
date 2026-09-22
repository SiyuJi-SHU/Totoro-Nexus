package org.example.controller;

import org.example.platform.SessionAttachment;
import org.example.platform.SessionAttachmentService;
import org.example.platform.PlatformIdentity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform/sessions")
public class SessionAttachmentController {
    private final SessionAttachmentService attachmentService;
    private final PlatformIdentity identity;

    public SessionAttachmentController(SessionAttachmentService attachmentService, PlatformIdentity identity) {
        this.attachmentService = attachmentService;
        this.identity = identity;
    }

    @GetMapping("/{sessionId}/attachments")
    public List<SessionAttachment> list(
        @PathVariable String sessionId,
        Authentication auth
    ) {
        return attachmentService.list(sessionId, identity.userId(auth));
    }

    @PostMapping("/{sessionId}/attachments")
    public SessionAttachment upload(
        @PathVariable String sessionId,
        @RequestParam("file") MultipartFile file,
        Authentication auth
    ) throws IOException {
        return attachmentService.upload(sessionId, file, identity.userId(auth));
    }

    @GetMapping("/{sessionId}/attachments/{attachmentId}")
    public ResponseEntity<Resource> download(
        @PathVariable String sessionId,
        @PathVariable String attachmentId,
        Authentication auth
    ) {
        String userId = identity.userId(auth);
        var attachments = attachmentService.list(sessionId, userId);
        var attachment = attachments.stream()
            .filter(a -> a.id().equals(attachmentId))
            .findFirst()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "附件不存在"));
        var path = attachmentService.getFilePath(attachmentId, userId);

        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(attachment.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment().filename(attachment.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .body(resource);
    }

    @DeleteMapping("/{sessionId}/attachments/{attachmentId}")
    public Map<String, Boolean> delete(
        @PathVariable String sessionId,
        @PathVariable String attachmentId,
        Authentication auth
    ) {
        attachmentService.delete(sessionId, attachmentId, identity.userId(auth));
        return Map.of("deleted", true);
    }
}
