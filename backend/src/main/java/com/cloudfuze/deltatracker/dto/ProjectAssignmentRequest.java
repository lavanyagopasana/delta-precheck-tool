package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProjectAssignmentRequest {

    // Team members only -- the Migration Manager is fixed once the project is created (auto-set
    // when the creator is a manager, or picked at creation time otherwise) and can't be reassigned.
    // Bounded to 200 to stop an unbounded list, and each element must be a valid email (the list is
    // otherwise persisted straight into project_engineers). Element-level @Email is validated because
    // the controller method carries @Valid.
    @Size(max = 200, message = "A project can have at most 200 engineers")
    private List<@Email(message = "Engineer emails must be valid email addresses") String> engineerEmails;
}
