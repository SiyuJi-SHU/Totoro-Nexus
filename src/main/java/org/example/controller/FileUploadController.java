package org.example.controller;

import org.example.service.ConsoleDocumentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;

/** Compatibility route; ingestion is exactly the same service as the console. */
@RestController
public class FileUploadController {
    private final org.example.platform.KnowledgeIngestion documents;
    public FileUploadController(org.example.platform.KnowledgeIngestion documents) { this.documents = documents; }
    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(defaultValue="existing-data") String datasetId,
                                    @RequestParam(defaultValue="") String folder) throws Exception {
        return ResponseEntity.ok(documents.upload(datasetId,folder,file));
    }
}
