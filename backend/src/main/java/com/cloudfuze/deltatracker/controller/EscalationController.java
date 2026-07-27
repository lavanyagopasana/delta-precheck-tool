package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.EscalationCreateRequest;
import com.cloudfuze.deltatracker.dto.EscalationDto;
import com.cloudfuze.deltatracker.dto.EscalationResolveRequest;
import com.cloudfuze.deltatracker.service.EscalationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/escalations")
public class EscalationController {

    private final EscalationService escalationService;

    public EscalationController(EscalationService escalationService) {
        this.escalationService = escalationService;
    }

    @GetMapping
    public List<EscalationDto> listAll() {
        return escalationService.listAll();
    }

    @GetMapping("/open-count")
    public Map<String, Long> openCount() {
        return Map.of("count", escalationService.countOpen());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EscalationDto create(@Valid @RequestBody EscalationCreateRequest request) {
        return escalationService.create(request);
    }

    @PatchMapping("/{id}/resolve")
    public EscalationDto resolve(@PathVariable Long id, @Valid @RequestBody EscalationResolveRequest request) {
        return escalationService.resolve(id, request);
    }
}
