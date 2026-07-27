package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.PreCheckSubmissionDto;
import com.cloudfuze.deltatracker.dto.SubmissionSubmitRequest;
import com.cloudfuze.deltatracker.service.PreCheckSubmissionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{serverId}/precheck-submission")
public class PreCheckSubmissionController {

    private final PreCheckSubmissionService submissionService;

    public PreCheckSubmissionController(PreCheckSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping
    public PreCheckSubmissionDto get(@PathVariable Long serverId,
                                      @RequestParam(required = false) String viewerEmail) {
        return submissionService.getByServer(serverId, viewerEmail);
    }

    @PostMapping("/submit")
    public PreCheckSubmissionDto submit(@PathVariable Long serverId,
                                         @Valid @RequestBody SubmissionSubmitRequest request) {
        return submissionService.submit(serverId, request);
    }
}
