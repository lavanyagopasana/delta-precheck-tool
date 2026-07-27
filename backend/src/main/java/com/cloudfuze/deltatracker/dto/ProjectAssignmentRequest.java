package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProjectAssignmentRequest {

    // Team members only -- the Migration Manager is fixed once the project is created (auto-set
    // when the creator is a manager, or picked at creation time otherwise) and can't be reassigned.
    private List<String> engineerEmails;
}
