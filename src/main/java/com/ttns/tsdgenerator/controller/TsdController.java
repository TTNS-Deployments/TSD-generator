package com.ttns.tsdgenerator.controller;

import com.ttns.tsdgenerator.model.D2Request;
import com.ttns.tsdgenerator.model.FormData;
import com.ttns.tsdgenerator.model.LlmContent;
import com.ttns.tsdgenerator.service.D2DocumentService;
import com.ttns.tsdgenerator.service.DocumentService;
import com.ttns.tsdgenerator.service.LlmService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class TsdController {

    private final LlmService llmService;
    private final DocumentService documentService;
    private final D2DocumentService d2DocumentService;

    @Value("${temp.path}")
    private String tempPath;

    public TsdController(LlmService llmService, DocumentService documentService, D2DocumentService d2DocumentService) {
        this.llmService = llmService;
        this.documentService = documentService;
        this.d2DocumentService = d2DocumentService;
    }

    @PostConstruct
    public void init() {
        try {
            Path tempDir = Paths.get(tempPath);
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            } else {
                try (Stream<Path> files = Files.list(tempDir)) {
                    files.forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            // silently ignore
                        }
                    });
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not initialize temp directory: " + e.getMessage());
        }
    }

    /**
     * D2 TSD: no AI. Multipart request with a JSON part "data" ({@link D2Request}) and a file part "boq"
     * (the Azure Pricing Calculator .xlsx export, pasted into the document as a table).
     */
    @PostMapping(value = "/generate-d2", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> generateD2(@RequestPart("data") D2Request request,
                                        @RequestPart(value = "boq", required = false) MultipartFile boq) {
        try {
            byte[] docBytes = d2DocumentService.generate(request, boq);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.setContentDispositionFormData("attachment", d2DocumentService.fileName(request));
            return ResponseEntity.ok().headers(headers).body(docBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error generating D2 TSD: " + e.getMessage());
        }
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateTsd(@RequestBody FormData formData) {
        try {
            Map<String, String> promptDict = llmService.buildPrompt(formData);
            LlmContent llmContent = llmService.generateContent(promptDict);
            byte[] docBytes = documentService.generateDocument(formData, llmContent);

            String sanitizedName = formData.getCustomerName().replace(" ", "_");
            String filename = "AVD_TSD_" + sanitizedName + "_" + formData.getDate() + ".docx";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.setContentDispositionFormData("attachment", filename);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(docBytes);
        } catch (Exception e) {
            e.printStackTrace();
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            // Print full cause chain
            Throwable cause = e.getCause();
            while (cause != null) {
                System.err.println("Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
            return ResponseEntity.internalServerError()
                    .body("Error generating TSD: " + msg);
        }
    }
}
