package com.fundradar.core.fund.service;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.simulation.SimulationRepository;
import com.fundradar.core.watchlist.service.WatchlistService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/** 将基金目录限定到当前账号的关注或仍有份额的模拟持仓。 */
@Service
public class FundPersonalScopeService {

    private final WatchlistService watchlistService;
    private final SimulationRepository simulationRepository;

    public FundPersonalScopeService(WatchlistService watchlistService, SimulationRepository simulationRepository) {
        this.watchlistService = watchlistService;
        this.simulationRepository = simulationRepository;
    }

    /** 两个条件同时选择时取交集；未选择的条件不限制结果。 */
    public Set<String> findFundCodes(boolean watchedOnly, boolean heldOnly) {
        Set<String> watched = watchedOnly ? watchlistService.findCurrentUserFollowedFundCodes() : null;
        Set<String> held = heldOnly
                ? simulationRepository.heldFundCodes(CurrentUserContext.require().userId())
                : null;
        if (watched == null) return held == null ? Set.of() : held;
        if (held == null) return watched;
        Set<String> intersection = new HashSet<>(watched);
        intersection.retainAll(held);
        return intersection;
    }
}
