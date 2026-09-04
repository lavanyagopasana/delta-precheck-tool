package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PreCheckItemUpdateRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckItemEdit;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.PreCheckItemEditRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemEvidenceRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The edit trail on a pre-check item.
 *
 * <p>This exists because pre-check editing was opened to Migration Managers, who are also the first
 * approver in the chain: the overlap is disclosed rather than forbidden, so the disclosure has to be
 * reliable. What matters most here is that the trail records the editor's role AS IT WAS, and that a
 * save which changed nothing does not pad the trail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreCheckItemEditTrailTest {

    private static final Long COMBINATION_ID = 7L;
    private static final Long ITEM_ID = 42L;

    @Mock private PreCheckItemRepository itemRepository;
    @Mock private PreCheckItemEvidenceRepository evidenceRepository;
    @Mock private PreCheckItemEditRepository editRepository;
    @Mock private PreCheckSubmissionRepository submissionRepository;
    @Mock private WorkspaceCombinationService combinationService;
    @Mock private AppUserService appUserService;

    private PreCheckItemService service;
    private PreCheckItem item;

    @BeforeEach
    void setUp() {
        service = new PreCheckItemService(itemRepository, evidenceRepository, editRepository,
                submissionRepository, combinationService, appUserService);

        WorkspaceCombination combination = new WorkspaceCombination();
        combination.setId(COMBINATION_ID);
        when(combinationService.findOrThrow(COMBINATION_ID)).thenReturn(combination);

        item = new PreCheckItem();
        item.setId(ITEM_ID);
        item.setCombinationId(COMBINATION_ID);
        item.setItemName("Hyperlinks Verified");
        item.setStatus(ItemStatus.NOT_STARTED);
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(itemRepository.save(any(PreCheckItem.class))).thenAnswer(i -> i.getArgument(0));
        when(evidenceRepository.findByItemIdOrderByUploadedAtAscIdAsc(ITEM_ID)).thenReturn(List.of());
        when(submissionRepository.findByCombinationId(anyLong())).thenReturn(Optional.empty());
    }

    private PreCheckItemUpdateRequest request(ItemStatus status, String notes, String by) {
        PreCheckItemUpdateRequest request = new PreCheckItemUpdateRequest();
        request.setStatus(status);
        request.setNotes(notes);
        request.setUpdatedBy(by);
        return request;
    }

    private PreCheckItemEdit captureEdit() {
        ArgumentCaptor<PreCheckItemEdit> captor = ArgumentCaptor.forClass(PreCheckItemEdit.class);
        verify(editRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aManagersEditIsRecordedWithTheirRole() {
        // The case the trail was built for: the first approver editing the form they will approve.
        when(appUserService.roleOf("mgr@cloudfuze.com"))
                .thenReturn(Optional.of(AppUserRole.MIGRATION_MANAGER));

        service.update(COMBINATION_ID, ITEM_ID,
                request(ItemStatus.COMPLETED, null, "mgr@cloudfuze.com"));

        PreCheckItemEdit edit = captureEdit();
        assertThat(edit.getEditedBy()).isEqualTo("mgr@cloudfuze.com");
        assertThat(edit.getEditedByRole()).isEqualTo(AppUserRole.MIGRATION_MANAGER);
        assertThat(edit.getFromStatus()).isEqualTo(ItemStatus.NOT_STARTED);
        assertThat(edit.getToStatus()).isEqualTo(ItemStatus.COMPLETED);
    }

    @Test
    void anAdminsEditIsRecordedJustTheSame() {
        // No role edits without leaving a trace -- the admin unblock path included.
        when(appUserService.roleOf("admin@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.ADMIN));

        service.update(COMBINATION_ID, ITEM_ID,
                request(ItemStatus.NOT_APPLICABLE, null, "admin@cloudfuze.com"));

        assertThat(captureEdit().getEditedByRole()).isEqualTo(AppUserRole.ADMIN);
    }

    @Test
    void aSaveThatChangedNothingWritesNoRow() {
        // The panel re-sends the current values on debounce and on every status change, so without
        // this the trail would fill with entries recording that nothing happened.
        item.setStatus(ItemStatus.COMPLETED);
        item.setNotes("Checked");
        when(appUserService.roleOf("eng@cloudfuze.com"))
                .thenReturn(Optional.of(AppUserRole.MIGRATION_ENGINEER));

        service.update(COMBINATION_ID, ITEM_ID,
                request(ItemStatus.COMPLETED, "Checked", "eng@cloudfuze.com"));

        verify(editRepository, never()).save(any());
    }

    @Test
    void editingOnlyTheNoteIsRecordedAsANoteChange() {
        item.setStatus(ItemStatus.COMPLETED);
        item.setNotes("First pass");
        when(appUserService.roleOf("eng@cloudfuze.com"))
                .thenReturn(Optional.of(AppUserRole.MIGRATION_ENGINEER));

        service.update(COMBINATION_ID, ITEM_ID,
                request(ItemStatus.COMPLETED, "Second pass", "eng@cloudfuze.com"));

        PreCheckItemEdit edit = captureEdit();
        assertThat(edit.isNotesChanged()).isTrue();
        // Same status in and out, so the trail must not claim a status change.
        assertThat(edit.getFromStatus()).isEqualTo(edit.getToStatus());
    }

    @Test
    void anUnknownEditorStillProducesARow() {
        // Auth-off local runs and any caller the allowlist has since dropped: the edit happened, so
        // it is recorded with a null role rather than silently skipped.
        when(appUserService.roleOf("ghost@cloudfuze.com")).thenReturn(Optional.empty());

        service.update(COMBINATION_ID, ITEM_ID,
                request(ItemStatus.COMPLETED, null, "ghost@cloudfuze.com"));

        assertThat(captureEdit().getEditedByRole()).isNull();
    }
}
