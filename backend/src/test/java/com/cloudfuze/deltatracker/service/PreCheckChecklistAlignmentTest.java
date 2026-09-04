package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How an existing checklist picks up items its product type has since gained.
 *
 * <p>This is the behaviour that was missing when the Folder/File, Email and Channel/DM sections were
 * added: combinations already part-filled kept showing the old list, because the only pass that
 * existed replaced a checklist outright and refused to touch one anybody had started.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreCheckChecklistAlignmentTest {

    private static final Long CID = 5L;

    @Mock private PreCheckItemRepository itemRepository;
    @Mock private PreCheckSubmissionRepository submissionRepository;
    @Mock private SignOffRepository signOffRepository;
    @Mock private WorkspaceCombinationRepository combinationRepository;
    @Mock private com.cloudfuze.deltatracker.repository.WorkspacePairRepository workspacePairRepository;
    @Mock private TicketService ticketService;
    @Mock private ServerService serverService;
    @Mock private EmailService emailService;
    @Mock private DeltaCycleService deltaCycleService;

    private WorkspaceCombinationService service;
    private WorkspaceCombination combination;

    @BeforeEach
    void setUp() {
        service = new WorkspaceCombinationService(combinationRepository, itemRepository,
                submissionRepository, signOffRepository, workspacePairRepository, ticketService,
                serverService, emailService, deltaCycleService);

        Server server = new Server("SRV-1");
        server.setId(1L);
        server.setProductType(ProductType.CONTENT);
        combination = new WorkspaceCombination();
        combination.setId(CID);
        combination.setName("Outlook to Outlook");
        combination.setServer(server);

        when(signOffRepository.findByCombinationId(CID)).thenReturn(List.of());
        when(submissionRepository.findByCombinationId(CID))
                .thenReturn(Optional.of(new PreCheckSubmission(combination)));
        when(itemRepository.save(any(PreCheckItem.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** The old Content list, with statuses already chosen -- i.e. a checklist somebody is filling. */
    private List<PreCheckItem> partFilledOldContentList() {
        List<String> old = List.of("Delta Type", "OneTime Migration", "Previous Delta Migration",
                "Data Verified", "Permissions Verified", "Hyperlinks Verified",
                "Workspace Status Updated in DB", "Drive changes");
        List<PreCheckItem> items = new ArrayList<>();
        for (String name : old) {
            PreCheckItem item = new PreCheckItem(combination, name);
            item.setStatus(ItemStatus.COMPLETED);
            items.add(item);
        }
        return items;
    }

    private List<String> savedNames() {
        ArgumentCaptor<PreCheckItem> captor = ArgumentCaptor.forClass(PreCheckItem.class);
        verify(itemRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream().map(PreCheckItem::getItemName).toList();
    }

    @Test
    void aPartFilledChecklistGainsOnlyTheItemsItIsMissing() {
        when(itemRepository.findByCombinationId(CID)).thenReturn(partFilledOldContentList());

        service.alignPreCheckItemsToProductType(combination);

        // Exactly the two new sections, and nothing else re-created.
        assertThat(savedNames()).containsExactlyInAnyOrder("Folder Details", "File Details");
        // Nothing filled in is thrown away to tidy the shape.
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    void anUntouchedChecklistIsStillReplacedOutright() {
        // The pass that can also REMOVE an item no longer in the list -- only safe when nothing has
        // been filled in.
        List<PreCheckItem> untouched = new ArrayList<>();
        untouched.add(new PreCheckItem(combination, "Delta Type"));
        untouched.add(new PreCheckItem(combination, "Some Retired Item"));
        when(itemRepository.findByCombinationId(CID)).thenReturn(untouched);

        service.alignPreCheckItemsToProductType(combination);

        verify(itemRepository).deleteAll(any());
        assertThat(savedNames()).contains("Folder Details", "File Details")
                .doesNotContain("Some Retired Item");
    }

    @Test
    void aSubmittedChecklistIsLeftCompletelyAlone() {
        // Adding a blank mandatory row to a form an approver is already reviewing would silently
        // invalidate it.
        PreCheckSubmission submitted = new PreCheckSubmission(combination);
        submitted.setStatus(SubmissionStatus.SUBMITTED);
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submitted));
        when(itemRepository.findByCombinationId(CID)).thenReturn(partFilledOldContentList());

        service.alignPreCheckItemsToProductType(combination);

        verify(itemRepository, never()).save(any(PreCheckItem.class));
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    void aChecklistAlreadyMatchingItsProductTypeIsNotTouched() {
        List<PreCheckItem> current = new ArrayList<>();
        for (String name : ServerService.preCheckItemsFor(ProductType.CONTENT)) {
            current.add(new PreCheckItem(combination, name));
        }
        when(itemRepository.findByCombinationId(CID)).thenReturn(current);

        service.alignPreCheckItemsToProductType(combination);

        verify(itemRepository, never()).save(any(PreCheckItem.class));
        verify(itemRepository, never()).deleteAll(any());
    }

    /**
     * What happens to a part-filled checklist when its SERVER'S PRODUCT TYPE changes.
     *
     * <p>Content -> Email drops Permissions Verified, Hyperlinks Verified and Drive changes, none of
     * which an email migration can evidence. Left on the form they can never be completed, so the
     * pre-check can never be submitted -- adding the new Email sections alone did not fix that.
     */
    @Test
    void changingProductTypeRemovesTheItemsThatNoLongerApplyButOnlyEmptyOnes() {
        combination.getServer().setProductType(ProductType.EMAIL);

        List<PreCheckItem> current = new ArrayList<>();
        // Filled in by an engineer before the product type changed -- must survive.
        PreCheckItem filledStale = new PreCheckItem(combination, "Hyperlinks Verified");
        filledStale.setStatus(ItemStatus.COMPLETED);
        filledStale.setNotes("checked");
        current.add(filledStale);
        // Never touched, and no longer applies -- should go.
        current.add(new PreCheckItem(combination, "Drive changes"));
        // Shared by both product types, and filled -- keeps the checklist "part-filled".
        PreCheckItem shared = new PreCheckItem(combination, "Data Verified");
        shared.setStatus(ItemStatus.COMPLETED);
        current.add(shared);
        when(itemRepository.findByCombinationId(CID)).thenReturn(current);

        service.alignPreCheckItemsToProductType(combination);

        // The Email sections arrive...
        assertThat(savedNames()).contains("Email Folders", "Email Picking", "Email Copy Queue",
                "Email Info", "Attachment Details");

        // ...the untouched Content-only row is deleted, and the filled one is not.
        ArgumentCaptor<Iterable<PreCheckItem>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(itemRepository).deleteAll(deleted.capture());
        List<String> goneNames = new ArrayList<>();
        deleted.getValue().forEach(i -> goneNames.add(i.getItemName()));
        assertThat(goneNames).containsExactly("Drive changes");
    }
}
