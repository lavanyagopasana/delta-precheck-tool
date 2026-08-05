package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.config.AsyncConfig;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a");

    private static final String PURPLE = "#5B21B6";
    private static final String INK = "#1f2430";
    private static final String MUTED = "#6b7280";
    private static final String LABEL = "#8b8fa3";
    private static final String LINE = "#e5e7eb";
    private static final String PAGE_BG = "#eef0f4";
    private static final String PENDING_BG = "#ede9fe";
    private static final String PENDING_TEXT = "#5b21b6";
    private static final String SUCCESS_BG = "#dcfce7";
    private static final String SUCCESS_TEXT = "#15803d";
    private static final String FONT_STACK = "Arial, 'Segoe UI', Helvetica, sans-serif";

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // @Async: SMTP send runs on the emailExecutor pool, not the request thread. Caller (SignOffService/
    // PreCheckSubmissionService) computes all args synchronously inside its transaction, so nothing here
    // touches a lazy entity -- these methods take only primitives/strings and are safe off-thread.
    //
    // Every notification below names both the server AND the combination it's about, as distinct
    // fields -- pre-checks/sign-offs/Delta are tracked per combination now (a server can have several,
    // e.g. Box -> OneDrive and Google Drive -> OneDrive migrated independently), so a combination-less
    // email would leave the recipient unsure which of a server's several migrations it refers to.
    @Async(AsyncConfig.EMAIL_EXECUTOR)
    public void notifyMigrationEngineersDeltaInitiated(String projectName, String serverName, String combinationName,
                                                        String initiatedBy, LocalDateTime initiatedAt,
                                                        List<String> migrationEngineerEmails) {
        if (migrationEngineerEmails.isEmpty()) {
            log.warn("Delta initiated for \"{}\" but no Migration Engineer is configured -- no email sent.",
                    serverLabel(serverName, combinationName));
            return;
        }

        // Every Migration Engineer site-wide gets this (not just people on this project), so the
        // project name in the subject matters more here than on any other template -- it's the only
        // way to tell which of possibly several projects an engineer works on this is about.
        String subject = "Action needed: initiate Delta migration for " + serverLabel(serverName, combinationName)
                + " (" + projectName + ")";
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[] { "Project", projectName });
        fields.add(new String[] { "Server URL", serverName });
        fields.add(new String[] { "Combination", combinationName });
        fields.add(new String[] { "Requested by", orDash(initiatedBy) });
        fields.add(new String[] { "Requested at", initiatedAt.format(DATE_FORMATTER) });

        String lede = initiatedBy + " is requesting Delta migration for \"" + serverLabel(serverName, combinationName)
                + "\" -- all sign-offs are complete and it's ready to go.";
        String html = renderEmail("Initiate Delta Migration", "READY TO INITIATE", true, lede,
                fields, "Open Dashboard", frontendUrl);
        String text = "Hi,\n\n"
                + lede + "\n\n"
                + "Project: " + projectName + "\n"
                + "Server URL: " + serverName + "\n"
                + "Combination: " + combinationName + "\n"
                + "Requested by: " + orDash(initiatedBy) + "\n"
                + "Requested at: " + initiatedAt.format(DATE_FORMATTER) + "\n\n"
                + "Open dashboard: " + frontendUrl;

        send(migrationEngineerEmails.toArray(new String[0]), subject, html, text);
    }

    @Async(AsyncConfig.EMAIL_EXECUTOR)
    public void notifyMigrationManagerPreCheckSubmitted(String projectName, String serverName, String combinationName,
                                                         int workspacePairCount, String submittedBy, String managerEmail) {
        String subject = "Pre-check submitted: " + serverLabel(serverName, combinationName) + " (" + projectName + ")";
        List<String[]> fields = fieldRows(projectName, serverName, combinationName, workspacePairCount, submittedBy);

        String lede = "The pre-check for this combination has been submitted and is awaiting your review and approval.";
        String html = renderEmail("Pre-Check Submitted", "AWAITING YOUR APPROVAL", false,
                lede, fields, "Review", approvalsUrl());
        String text = "Hi,\n\n"
                + lede + "\n\n"
                + fieldsBlock(projectName, serverName, combinationName, workspacePairCount, submittedBy)
                + "\nReview: " + approvalsUrl();

        send(new String[] { managerEmail }, subject, html, text);
    }

    @Async(AsyncConfig.EMAIL_EXECUTOR)
    public void notifyApprovalRequired(String roleLabel, String projectName, String serverName, String combinationName,
                                        int workspacePairCount, String submittedBy, List<String> recipientEmails) {
        if (recipientEmails.isEmpty()) {
            log.warn("Approval required ({}) for \"{}\" but no {} is configured -- no email sent.",
                    roleLabel, serverLabel(serverName, combinationName), roleLabel);
            return;
        }
        // Recipient is the person whose turn it is to approve, so the email deliberately does NOT name
        // their role ("Dev Lead", "QA Lead", ...) -- it just asks for their review/approval. roleLabel
        // is retained only for the server-side log line above.
        String subject = "Approval required: " + serverLabel(serverName, combinationName) + " (" + projectName + ")";
        List<String[]> fields = fieldRows(projectName, serverName, combinationName, workspacePairCount, submittedBy);

        String lede = "This combination's pre-check is awaiting your review and approval.";
        String html = renderEmail("Approval Required", "AWAITING YOUR APPROVAL", false,
                lede, fields, "Review", approvalsUrl());
        String text = "Hi,\n\n"
                + lede + "\n\n"
                + fieldsBlock(projectName, serverName, combinationName, workspacePairCount, submittedBy)
                + "\nReview: " + approvalsUrl();

        send(recipientEmails.toArray(new String[0]), subject, html, text);
    }

    @Async(AsyncConfig.EMAIL_EXECUTOR)
    public void notifyMigrationManagerDeltaReady(String projectName, String serverName, String combinationName,
                                                  int workspacePairCount, String preCheckSubmittedBy, String managerEmail) {
        String subject = "Delta Ready: " + serverLabel(serverName, combinationName) + " (" + projectName + ")";
        List<String[]> fields = fieldRows(projectName, serverName, combinationName, workspacePairCount, preCheckSubmittedBy);

        String lede = "All required approvals have been granted for this combination \u2014 it is now cleared for Delta migration.";
        String html = renderEmail("Delta Ready", "DELTA READY", true,
                lede, fields, "Open Approvals", approvalsUrl());
        String text = "Hi,\n\n"
                + lede + "\n\n"
                + fieldsBlock(projectName, serverName, combinationName, workspacePairCount, preCheckSubmittedBy).stripTrailing();

        send(new String[] { managerEmail }, subject, html, text);
    }

    // Sent to the Migration Manager when a Migration Engineer clicks Start on a Delta-Ready
    // combination -- i.e. the Delta migration is now underway. Recipient is the manager, so no role
    // is named.
    @Async(AsyncConfig.EMAIL_EXECUTOR)
    public void notifyMigrationManagerDeltaStarted(String projectName, String serverName, String combinationName,
                                                   int workspacePairCount, String startedBy, LocalDateTime startedAt,
                                                   String managerEmail) {
        String subject = "Delta Initiated: " + serverLabel(serverName, combinationName) + " (" + projectName + ")";
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[] { "Project", projectName });
        fields.add(new String[] { "Server URL", serverName });
        fields.add(new String[] { "Combination", combinationName });
        fields.add(new String[] { "Workspace pairs", String.valueOf(workspacePairCount) });
        fields.add(new String[] { "Initiated by", orDash(startedBy) });
        fields.add(new String[] { "Initiated at", startedAt.format(DATE_FORMATTER) });

        String lede = "The Delta migration for this combination has been initiated and is now in progress.";
        String html = renderEmail("Delta Migration Initiated", "IN PROGRESS", false,
                lede, fields, "Open Dashboard", frontendUrl);
        String text = "Hi,\n\n"
                + lede + "\n\n"
                + "Project: " + projectName + "\n"
                + "Server URL: " + serverName + "\n"
                + "Combination: " + combinationName + "\n"
                + "Workspace pairs: " + workspacePairCount + "\n"
                + "Initiated by: " + orDash(startedBy) + "\n"
                + "Initiated at: " + startedAt.format(DATE_FORMATTER) + "\n\n"
                + "Open dashboard: " + frontendUrl;

        send(new String[] { managerEmail }, subject, html, text);
    }

    // Sent to the Migration Manager when a Migration Engineer clicks Finish -- the Delta migration for
    // the combination is complete. Recipient is the manager, so no role is named.
    @Async(AsyncConfig.EMAIL_EXECUTOR)
    public void notifyMigrationManagerDeltaFinished(String projectName, String serverName, String combinationName,
                                                    int workspacePairCount, String finishedBy, LocalDateTime finishedAt,
                                                    String managerEmail) {
        String subject = "Delta Finished: " + serverLabel(serverName, combinationName) + " (" + projectName + ")";
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[] { "Project", projectName });
        fields.add(new String[] { "Server URL", serverName });
        fields.add(new String[] { "Combination", combinationName });
        fields.add(new String[] { "Workspace pairs", String.valueOf(workspacePairCount) });
        fields.add(new String[] { "Completed by", orDash(finishedBy) });
        fields.add(new String[] { "Completed at", finishedAt.format(DATE_FORMATTER) });

        String lede = "The Delta migration for this combination has been completed.";
        String html = renderEmail("Delta Migration Finished", "COMPLETED", true,
                lede, fields, "Open Dashboard", frontendUrl);
        String text = "Hi,\n\n"
                + lede + "\n\n"
                + "Project: " + projectName + "\n"
                + "Server URL: " + serverName + "\n"
                + "Combination: " + combinationName + "\n"
                + "Workspace pairs: " + workspacePairCount + "\n"
                + "Completed by: " + orDash(finishedBy) + "\n"
                + "Completed at: " + finishedAt.format(DATE_FORMATTER) + "\n\n"
                + "Open dashboard: " + frontendUrl;

        send(new String[] { managerEmail }, subject, html, text);
    }

    private List<String[]> fieldRows(String projectName, String serverName, String combinationName,
                                      int workspacePairCount, String submittedBy) {
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[] { "Project", projectName });
        fields.add(new String[] { "Server URL", serverName });
        fields.add(new String[] { "Combination", combinationName });
        fields.add(new String[] { "Workspace pairs", String.valueOf(workspacePairCount) });
        fields.add(new String[] { "Pre-check submitted by", orDash(submittedBy) });
        return fields;
    }

    private String fieldsBlock(String projectName, String serverName, String combinationName,
                                int workspacePairCount, String submittedBy) {
        return "Project: " + projectName + "\n"
                + "Server URL: " + serverName + "\n"
                + "Combination: " + combinationName + "\n"
                + "Workspace pairs: " + workspacePairCount + "\n"
                + "Pre-check submitted by: " + orDash(submittedBy) + "\n";
    }

    // Compact "server / combination" form used only in subject lines and log warnings, where a
    // single-line label reads better than two separate fields.
    private String serverLabel(String serverName, String combinationName) {
        return StringUtils.hasText(combinationName) ? serverName + " / " + combinationName : serverName;
    }

    private String orDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private String approvalsUrl() {
        return frontendUrl + "/approvals";
    }

    // Builds the branded HTML card every notification email uses: a purple header bar, a status
    // pill, a two-column field table, and a CTA button -- all with inline styles and table-based
    // layout since Outlook's desktop renderer (Word engine) doesn't support flexbox/grid or CSS
    // classes in email.
    private String renderEmail(String headline, String badgeLabel, boolean badgeSuccess, String lede,
                                List<String[]> fields, String ctaLabel, String ctaUrl) {
        String badgeBg = badgeSuccess ? SUCCESS_BG : PENDING_BG;
        String badgeText = badgeSuccess ? SUCCESS_TEXT : PENDING_TEXT;

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            String[] field = fields.get(i);
            String borderTop = i == 0 ? "" : "border-top:1px solid " + LINE + ";";
            rows.append("<tr>")
                    .append("<td style=\"padding:10px 14px;font-size:13px;font-weight:600;color:").append(LABEL)
                    .append(";width:42%;").append(borderTop).append("\">").append(escape(field[0])).append("</td>")
                    .append("<td style=\"padding:10px 14px;font-size:13px;font-weight:500;color:").append(INK)
                    .append(";").append(borderTop).append("\">").append(escape(field[1])).append("</td>")
                    .append("</tr>");
        }

        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:"
                + PAGE_BG + ";padding:32px 0;font-family:" + FONT_STACK + ";\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;"
                + "border-radius:10px;border:1px solid " + LINE + ";overflow:hidden;\">"
                + "<tr><td style=\"background:" + PURPLE + ";padding:20px 24px;\">"
                + "<div style=\"color:#ffffff;font-size:10.5px;font-weight:bold;letter-spacing:1px;text-transform:uppercase;opacity:0.85;\">"
                + "DELTA MIGRATION READINESS TRACKER</div>"
                + "<div style=\"color:#ffffff;font-size:19px;font-weight:bold;margin-top:6px;\">" + escape(headline) + "</div>"
                + "</td></tr>"
                + "<tr><td style=\"padding:24px;\">"
                + "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.6;color:" + INK + ";\">" + escape(lede) + "</p>"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:18px;\"><tr>"
                + "<td style=\"background:" + badgeBg + ";color:" + badgeText + ";font-size:12.5px;font-weight:bold;"
                + "padding:5px 12px;border-radius:999px;\">" + escape(badgeLabel) + "</td>"
                + "</tr></table>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border:1px solid "
                + LINE + ";border-radius:8px;margin-bottom:22px;\">" + rows + "</table>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td>"
                + "<a href=\"" + ctaUrl + "\" style=\"display:block;text-align:center;background:" + PURPLE
                + ";color:#ffffff;font-size:14px;font-weight:bold;text-decoration:none;padding:12px 20px;"
                + "border-radius:7px;font-family:" + FONT_STACK + ";\">" + escape(ctaLabel) + "</a>"
                + "</td></tr></table>"
                + "</td></tr>"
                + "<tr><td style=\"padding:14px 24px;border-top:1px solid " + LINE + ";\">"
                + "<p style=\"margin:0;font-size:11px;color:" + MUTED + ";text-align:center;\">"
                + "This is an automated notification from Delta Pre-Check Tool. Do not reply to this email.</p>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void send(String[] toEmails, String subject, String html, String plainText) {
        String[] recipients = java.util.Arrays.stream(toEmails)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);

        if (!StringUtils.hasText(fromEmail) || recipients.length == 0) {
            log.warn("Email not sent (SMTP not configured, or no recipient): subject=\"{}\"", subject);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            // "true" enables multipart mode -- required for setText(plainText, html) below to attach
            // both a plain-text and an HTML body as alternatives; without it, MimeMessageHelper
            // throws IllegalStateException("Not in multipart mode") on every send.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(recipients);
            helper.setSubject(subject);
            helper.setText(plainText, html);
            mailSender.send(message);
            log.info("Email sent to {} ({})", String.join(", ", recipients), subject);
        } catch (Exception e) {
            log.error("Failed to send email via SMTP", e);
        }
    }
}
