package org.example.controller;
import org.example.service.ConsoleDocumentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

@RestController @RequestMapping("/api/console/documents")
public class ConsoleDocumentsController {
    private final ConsoleDocumentService documents;
    public ConsoleDocumentsController(ConsoleDocumentService documents){this.documents=documents;}
    public Object list() throws Exception{return documents.list();}
    public Object preview(@RequestParam String name)throws Exception{return documents.preview(name);}
    public ResponseEntity<?> download(@RequestParam String name)throws Exception {
        Path path=documents.existing(name);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(path.getFileName().toString(),StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(path));
    }
    public ResponseEntity<?> upload(@RequestParam MultipartFile file)throws Exception{return ResponseEntity.status(201).body(documents.upload(file));}
    public Object delete(@RequestParam String name)throws Exception{return documents.delete(name);}
}
