package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.MetabaseDatabaseDto;
import com.cloudfuze.deltatracker.service.MetabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/metabase")
public class MetabaseController {

    private final MetabaseClient metabaseClient;

    public MetabaseController(MetabaseClient metabaseClient) {
        this.metabaseClient = metabaseClient;
    }

    // The databases the project page's dropdown is built from. Read-only, and gated in SecurityConfig
    // to any allowlisted caller: it is a list of database names, every caller is internal staff, and
    // the approvers who can't SET a project's database still see it on the project page.
    //
    // Errors (not configured, unreachable, rejected credential) come back as the normal ApiException
    // shape via GlobalExceptionHandler, which is what lets the frontend fall back to a plain text
    // field with the reason on screen rather than an empty dropdown nobody can explain.
    @GetMapping("/databases")
    public List<MetabaseDatabaseDto> databases() {
        return metabaseClient.fetchDatabases();
    }
}
