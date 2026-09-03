package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AppUserUpsertRequest {

    // @Email added: this value becomes both the allowlist identity and an EmailService recipient, so
    // a malformed string here would silently produce undeliverable notifications.
    @NotBlank
    @Email(message = "Enter a valid email address")
    private String email;

    @NotNull
    private AppUserRole role;

    /**
     * Boxed on purpose: absent (null) means "leave whatever this row already had", which is what
     * lets a caller that only means to change a role not clear the flag as a side effect. Only an
     * admin reaches this -- AdminController checks that before delegating.
     */
    private Boolean assignableAsManager;
}
