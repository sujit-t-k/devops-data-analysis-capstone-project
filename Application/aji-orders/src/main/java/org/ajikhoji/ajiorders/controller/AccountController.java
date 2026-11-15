package org.ajikhoji.ajiorders.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ajikhoji.ajiorders.dto.AccountInfoUpdateDto;
import org.ajikhoji.ajiorders.service.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final SimulationService simulationService;

    @PutMapping("/save-modification")
    public ResponseEntity<Map<String, String>> changeAccountInfo(@Valid @RequestBody AccountInfoUpdateDto updatedAccountInfo) {
        simulationService.updateAccountInfo(updatedAccountInfo);
        return ResponseEntity.ok(Map.of("userId", String.valueOf(updatedAccountInfo.getUserId()), "updateStatus", "success"));
    }

}
