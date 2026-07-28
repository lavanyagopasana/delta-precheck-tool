package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.AppUserDto;
import com.cloudfuze.deltatracker.dto.AppUserImportResultDto;
import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import com.cloudfuze.deltatracker.util.CsvUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class AppUserService {

    private final AppUserRepository appUserRepository;

    // Temporary testing switch: while false, everyone with a valid token is treated as allowed,
    // regardless of the allowlist table. Set AZURE_REQUIRE_ALLOWLIST=true to go back to requiring
    // an admin to explicitly add each person. requireAdmin() below is NOT affected by this -- the
    // Manage Access page itself always requires a real ADMIN row, even while this is off.
    @Value("${azure.require-allowlist:false}")
    private boolean requireAllowlist;

    // Anyone signing in with this email domain is auto-added to the allowlist (if not already
    // present) as MIGRATION_ENGINEER the first time they're looked up -- no admin action needed.
    // This runs regardless of azure.require-allowlist. Existing rows (e.g. someone later promoted
    // to Admin) are never overwritten by this -- it only creates a row when one doesn't exist yet.
    @Value("${azure.auto-provision-domain:cloudfuze.com}")
    private String autoProvisionDomain;

    public AppUserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    public List<AppUserDto> list() {
        return appUserRepository.findAllByOrderByAddedAtAsc().stream()
                .map(AppUserDto::fromEntity)
                .toList();
    }

    public List<String> emailsForRole(AppUserRole role) {
        return appUserRepository.findByRole(role).stream().map(AppUser::getEmail).toList();
    }

    public Optional<AppUserRole> roleOf(String email) {
        if (email == null) {
            return Optional.empty();
        }
        autoProvisionIfEligible(email);
        return appUserRepository.findByEmailIgnoreCase(email).map(AppUser::getRole);
    }

    public boolean isAllowed(String email) {
        autoProvisionIfEligible(email);
        if (!requireAllowlist) {
            return true;
        }
        return email != null && appUserRepository.existsByEmailIgnoreCase(email);
    }

    private void autoProvisionIfEligible(String email) {
        if (email == null || !StringUtils.hasText(autoProvisionDomain)) {
            return;
        }
        if (!email.toLowerCase().endsWith("@" + autoProvisionDomain.toLowerCase())) {
            return;
        }
        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            return;
        }
        appUserRepository.save(new AppUser(email, AppUserRole.MIGRATION_ENGINEER, "auto (" + autoProvisionDomain + ")"));
    }

    public void requireAdmin(String email) {
        if (roleOf(email).filter(r -> r == AppUserRole.ADMIN).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only admins can manage app access.");
        }
    }

    public boolean isAdmin(String email) {
        return roleOf(email).filter(r -> r == AppUserRole.ADMIN).isPresent();
    }

    // actingAdminEmail is the admin performing the change (from AdminController.requireAdmin). It's
    // stored as addedBy for brand-new rows, and used to guard the scenarios where editing a user
    // isn't safe -- so "editing users" is deliberately NOT allowed for every user/every change.
    public AppUserDto upsert(String email, AppUserRole role, String actingAdminEmail) {
        Optional<AppUser> existing = appUserRepository.findByEmailIgnoreCase(email);

        if (existing.isPresent()) {
            AppUser current = existing.get();
            boolean roleChanging = current.getRole() != role;
            // You can't change your own role -- another admin must, so an admin can't accidentally
            // demote themselves out of Manage Access.
            if (roleChanging && email.equalsIgnoreCase(actingAdminEmail)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "You can't change your own role.");
            }
            // There must always be at least one admin -- the last one can't be demoted.
            if (roleChanging && current.getRole() == AppUserRole.ADMIN && role != AppUserRole.ADMIN
                    && appUserRepository.countByRole(AppUserRole.ADMIN) <= 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Can't demote the last remaining admin.");
            }
        }

        AppUser user = existing.orElseGet(() -> new AppUser(email, role, actingAdminEmail));
        user.setRole(role);
        return AppUserDto.fromEntity(appUserRepository.save(user));
    }

    // One email per row. A header row is auto-detected if any cell normalizes to "email"; otherwise
    // every row (including the first) is treated as data, with the email in the first column. Every
    // valid row gets upserted to the given role, same as adding one at a time -- an email already on
    // the allowlist just has its role updated, it's never duplicated.
    public AppUserImportResultDto importCsv(MultipartFile file, AppUserRole role, String addedBy) {
        List<String> lines = readLines(file);
        if (lines.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        }

        int emailColumn = 0;
        int startRow = 0;
        List<String> header = CsvUtils.parseLine(lines.get(0));
        for (int i = 0; i < header.size(); i++) {
            if (normalizeHeader(header.get(i)).equals("email")) {
                emailColumn = i;
                startRow = 1;
                break;
            }
        }

        List<String> errors = new ArrayList<>();
        int totalRows = 0;
        int created = 0;
        int updated = 0;

        for (int rowNum = startRow; rowNum < lines.size(); rowNum++) {
            String line = lines.get(rowNum);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            totalRows++;
            List<String> fields = CsvUtils.parseLine(line);
            String email = emailColumn < fields.size() ? fields.get(emailColumn).trim().toLowerCase() : "";
            if (!StringUtils.hasText(email) || !email.contains("@")) {
                errors.add("Row " + (rowNum + 1) + ": \"" + email + "\" isn't a valid email");
                continue;
            }

            boolean existed = appUserRepository.existsByEmailIgnoreCase(email);
            try {
                upsert(email, role, addedBy);
            } catch (ApiException e) {
                // Keep processing the rest of the batch -- one guarded row (e.g. the acting admin's
                // own, or a last-admin demotion) must not fail the whole import.
                errors.add("Row " + (rowNum + 1) + ": " + e.getMessage());
                continue;
            }
            if (existed) {
                updated++;
            } else {
                created++;
            }
        }

        AppUserImportResultDto result = new AppUserImportResultDto();
        result.setTotalRows(totalRows);
        result.setCreatedCount(created);
        result.setUpdatedCount(updated);
        result.setErrors(errors);
        return result;
    }

    private String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        String cleaned = header.replace("﻿", "");
        return cleaned.trim().toLowerCase().replaceAll("[^a-z]", "");
    }

    private List<String> readLines(MultipartFile file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to read CSV file");
        }
        return lines;
    }

    public void remove(String email, String actingAdminEmail) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));

        // You can't remove your own access -- prevents locking yourself out; another admin must.
        if (email.equalsIgnoreCase(actingAdminEmail)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can't remove your own access.");
        }
        if (user.getRole() == AppUserRole.ADMIN && appUserRepository.countByRole(AppUserRole.ADMIN) <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot remove the last remaining admin.");
        }

        appUserRepository.delete(user);
    }
}
