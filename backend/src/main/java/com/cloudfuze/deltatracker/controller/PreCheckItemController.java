package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
import com.cloudfuze.deltatracker.dto.PreCheckItemUpdateRequest;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.service.PreCheckItemService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/servers/{serverId}/precheck-items")
public class PreCheckItemController {

    private final PreCheckItemService preCheckItemService;

    public PreCheckItemController(PreCheckItemService preCheckItemService) {
        this.preCheckItemService = preCheckItemService;
    }

    @GetMapping
    public List<PreCheckItemDto> list(@PathVariable Long serverId) {
        return preCheckItemService.listByServer(serverId);
    }

    @PostMapping("/{itemId}")
    public PreCheckItemDto update(@PathVariable Long serverId,
                                   @PathVariable Long itemId,
                                   @RequestBody PreCheckItemUpdateRequest request) {
        return preCheckItemService.update(serverId, itemId, request);
    }

    @PostMapping("/check-all")
    public void checkAll(@PathVariable Long serverId,
                          @RequestParam ItemStatus status,
                          @RequestParam(required = false) String updatedBy) {
        preCheckItemService.setAllStatus(serverId, status, updatedBy);
    }
}
