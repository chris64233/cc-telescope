package com.chris64233.cc.telescope.service;

import java.util.List;

import com.chris64233.cc.telescope.domain.Instrument;
import com.chris64233.cc.telescope.domain.Proposal;
import com.chris64233.cc.telescope.domain.Telescope;
import com.chris64233.cc.telescope.dto.ProposalRequest;
import com.chris64233.cc.telescope.dto.ProposalResponse;
import com.chris64233.cc.telescope.dto.TelescopeRequest;
import com.chris64233.cc.telescope.dto.TelescopeResponse;
import com.chris64233.cc.telescope.repo.InstrumentRepository;
import com.chris64233.cc.telescope.repo.ProposalRepository;
import com.chris64233.cc.telescope.repo.TelescopeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final InstrumentRepository instrumentRepository;
    private final TelescopeRepository telescopeRepository;
    private final ProposalRepository proposalRepository;

    public RegistrationService(InstrumentRepository instrumentRepository,
                               TelescopeRepository telescopeRepository,
                               ProposalRepository proposalRepository) {
        this.instrumentRepository = instrumentRepository;
        this.telescopeRepository = telescopeRepository;
        this.proposalRepository = proposalRepository;
    }

    @Transactional
    public Instrument registerInstrument(String name) {
        return instrumentRepository.findByName(name)
                .orElseGet(() -> instrumentRepository.save(new Instrument(name)));
    }

    @Transactional
    public TelescopeResponse registerTelescope(TelescopeRequest request) {
        if (telescopeRepository.findByCode(request.code()).isPresent()) {
            throw new ConflictException("望远镜编号已存在: " + request.code());
        }
        Telescope telescope = new Telescope(
                request.code(), request.name(), request.switchOverMinutes());
        for (String instrumentName : request.instruments()) {
            telescope.addInstrument(resolveInstrument(instrumentName));
        }
        Telescope saved = telescopeRepository.save(telescope);
        return toTelescopeResponse(saved);
    }

    @Transactional
    public ProposalResponse registerProposal(ProposalRequest request) {
        if (proposalRepository.findByProposalNo(request.proposalNo()).isPresent()) {
            throw new ConflictException("提案编号已存在: " + request.proposalNo());
        }
        Proposal proposal = new Proposal(request.proposalNo(), request.totalMinutes());
        for (String instrumentName : request.instruments()) {
            proposal.addAllowedInstrument(resolveInstrument(instrumentName));
        }
        Proposal saved = proposalRepository.save(proposal);
        return toProposalResponse(saved);
    }

    private Instrument resolveInstrument(String name) {
        return instrumentRepository.findByName(name)
                .orElseThrow(() -> new NotFoundException("仪器不存在: " + name));
    }

    @Transactional(readOnly = true)
    public List<TelescopeResponse> listTelescopes() {
        return telescopeRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Telescope::getCode))
                .map(this::toTelescopeResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProposalResponse> listProposals() {
        return proposalRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Proposal::getProposalNo))
                .map(this::toProposalResponse)
                .toList();
    }

    private TelescopeResponse toTelescopeResponse(Telescope telescope) {
        List<String> instruments = telescope.getSupportedInstruments().stream()
                .map(Instrument::getName)
                .sorted()
                .toList();
        return new TelescopeResponse(telescope.getId(), telescope.getCode(),
                telescope.getName(), telescope.getSwitchOverMinutes(), instruments);
    }

    private ProposalResponse toProposalResponse(Proposal proposal) {
        List<String> instruments = proposal.getAllowedInstruments().stream()
                .map(Instrument::getName)
                .sorted()
                .toList();
        return new ProposalResponse(proposal.getId(), proposal.getProposalNo(),
                proposal.getTotalMinutes(), proposal.getUsedMinutes(),
                proposal.getRemainingMinutes(), instruments);
    }
}
