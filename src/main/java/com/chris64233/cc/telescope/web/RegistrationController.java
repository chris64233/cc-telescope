package com.chris64233.cc.telescope.web;

import java.util.List;

import com.chris64233.cc.telescope.dto.InstrumentRequest;
import com.chris64233.cc.telescope.dto.InstrumentResponse;
import com.chris64233.cc.telescope.dto.ProposalRequest;
import com.chris64233.cc.telescope.dto.ProposalResponse;
import com.chris64233.cc.telescope.dto.TelescopeRequest;
import com.chris64233.cc.telescope.dto.TelescopeResponse;
import com.chris64233.cc.telescope.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/instruments")
    @ResponseStatus(HttpStatus.CREATED)
    public InstrumentResponse registerInstrument(@Valid @RequestBody InstrumentRequest request) {
        var instrument = registrationService.registerInstrument(request.name());
        return new InstrumentResponse(instrument.getId(), instrument.getName());
    }

    @PostMapping("/telescopes")
    @ResponseStatus(HttpStatus.CREATED)
    public TelescopeResponse registerTelescope(@Valid @RequestBody TelescopeRequest request) {
        return registrationService.registerTelescope(request);
    }

    @PostMapping("/proposals")
    @ResponseStatus(HttpStatus.CREATED)
    public ProposalResponse registerProposal(@Valid @RequestBody ProposalRequest request) {
        return registrationService.registerProposal(request);
    }

    @GetMapping("/telescopes")
    public List<TelescopeResponse> listTelescopes() {
        return registrationService.listTelescopes();
    }

    @GetMapping("/proposals")
    public List<ProposalResponse> listProposals() {
        return registrationService.listProposals();
    }
}
