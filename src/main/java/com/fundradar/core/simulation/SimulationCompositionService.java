package com.fundradar.core.simulation;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.fund.api.FundCompositionResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.fundradar.core.simulation.SimulationCompositionTypes.*;
import static com.fundradar.core.simulation.SimulationTypes.Position;

/** 个人金额只在Java内与公共报告加权；未知资料不补零，主题不按100%归一化。 */
@Service
public class SimulationCompositionService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final SimulationRepository repo;
    private final AiFundClient client;

    public SimulationCompositionService(SimulationRepository repo, AiFundClient client) {
        this.repo = repo;
        this.client = client;
    }

    public Composition composition() {
        UUID user = CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId();
        return composition(user);
    }

    Composition composition(UUID user) {
        Map<String, Holding> holdings = currentHoldings(user);
        BigDecimal accountAmount = sum(holdings.values(), Holding::amount);
        if (holdings.isEmpty()) {
            View empty = view("ACCOUNT", BigDecimal.ZERO, null, null, List.of(), List.of());
            return new Composition(accountAmount, empty, empty, empty);
        }
        String codes = holdings.keySet().stream().sorted().collect(Collectors.joining(","));
        FundCompositionResponse source = client.getFundComposition(codes);
        Map<String, FundCompositionResponse.Fund> byCode = source.funds().stream()
                .collect(Collectors.toMap(FundCompositionResponse.Fund::fundCode, Function.identity()));
        return new Composition(accountAmount, assets(holdings, accountAmount, byCode),
                industries(holdings, accountAmount, byCode), themes(holdings, accountAmount, byCode, source));
    }

    private Map<String, Holding> currentHoldings(UUID user) {
        Map<String, Holding> result = new LinkedHashMap<>();
        for (Position position : repo.positions(user)) {
            if (position.shares().signum() > 0 && position.marketValue() != null
                    && position.marketValue().signum() > 0) {
                result.merge(position.fundCode(), new Holding(position.fundCode(), position.fundName(),
                        position.marketValue()), (left, right) -> left.add(right.amount));
            }
        }
        var baseline = repo.confirmedBaseline(user);
        if (baseline != null) {
            for (var confirmed : baseline.holdings()) {
                if (confirmed.reportedAmount() != null && confirmed.reportedAmount().signum() > 0) {
                    result.merge(confirmed.fundCode(), new Holding(confirmed.fundCode(), confirmed.fundName(),
                            confirmed.reportedAmount()), (left, right) -> left.add(right.amount));
                }
            }
        }
        return result;
    }

    private View assets(Map<String, Holding> holdings, BigDecimal total,
            Map<String, FundCompositionResponse.Fund> source) {
        Map<String, Acc> rows = new LinkedHashMap<>();
        BigDecimal covered = BigDecimal.ZERO;
        List<ExcludedHolding> excluded = new ArrayList<>();
        for (Holding holding : holdings.values()) {
            var report = source.get(holding.code).assets();
            if (report == null) {
                Acc unknown = rows.computeIfAbsent("UNKNOWN", key -> new Acc(key, "待补充", "", true));
                unknown.add(holding.amount, holding, List.of(), null, null);
                excluded.add(new ExcludedHolding(holding.code, holding.name, holding.amount, "资产配置报告待补充"));
                continue;
            }
            covered = covered.add(holding.amount);
            List<ReportSource> reports = List.of(source(report, holding));
            for (var item : report.items()) {
                rows.computeIfAbsent(item.key(), key -> new Acc(key, item.name(), "", false))
                        .add(part(holding.amount, item.weightPct()), holding, reports, null, null);
            }
        }
        BigDecimal coverage = pct(covered, total);
        return view("ACCOUNT", total, coverage, null, rows.values(), excluded);
    }

    private View industries(Map<String, Holding> holdings, BigDecimal accountAmount,
            Map<String, FundCompositionResponse.Fund> source) {
        Map<String, Acc> rows = new LinkedHashMap<>();
        BigDecimal stockTotal = BigDecimal.ZERO;
        BigDecimal coveredAccount = BigDecimal.ZERO;
        List<ExcludedHolding> excluded = new ArrayList<>();
        for (Holding holding : holdings.values()) {
            var report = source.get(holding.code).industries();
            if (report == null) {
                excluded.add(new ExcludedHolding(holding.code, holding.name, holding.amount, "股票行业报告待补充"));
                continue;
            }
            coveredAccount = coveredAccount.add(holding.amount);
            BigDecimal stockAmount = part(holding.amount, report.stockWeightPct());
            stockTotal = stockTotal.add(stockAmount);
            List<ReportSource> reports = List.of(source(report, holding));
            BigDecimal known = BigDecimal.ZERO;
            for (var item : report.items()) {
                BigDecimal amount = part(holding.amount, item.weightPct());
                known = known.add(amount);
                rows.computeIfAbsent(item.key(), key -> new Acc(key, item.name(), "", false))
                        .add(amount, holding, reports, null, null);
            }
            BigDecimal pending = stockAmount.subtract(known);
            if (pending.signum() > 0) {
                rows.computeIfAbsent("INDUSTRY_UNKNOWN", key -> new Acc(key, "行业待补充", "", true))
                        .add(pending, holding, reports, null, null);
            }
        }
        return view("DISCLOSED_STOCK", stockTotal, pct(coveredAccount, accountAmount), null, rows.values(), excluded);
    }

    private View themes(Map<String, Holding> holdings, BigDecimal accountAmount,
            Map<String, FundCompositionResponse.Fund> source, FundCompositionResponse response) {
        Map<String, Acc> rows = new LinkedHashMap<>();
        Map<String, String> definitions = response.themeDefinitions().stream()
                .collect(Collectors.toMap(FundCompositionResponse.ThemeDefinition::id,
                        FundCompositionResponse.ThemeDefinition::definition));
        BigDecimal disclosedTotal = BigDecimal.ZERO;
        BigDecimal coveredAccount = BigDecimal.ZERO;
        BigDecimal mapped = BigDecimal.ZERO;
        List<ExcludedHolding> excluded = new ArrayList<>();
        for (Holding holding : holdings.values()) {
            var report = source.get(holding.code).themes();
            if (report == null) {
                excluded.add(new ExcludedHolding(holding.code, holding.name, holding.amount, "个股主题资料待补充"));
                continue;
            }
            coveredAccount = coveredAccount.add(holding.amount);
            disclosedTotal = disclosedTotal.add(part(holding.amount, report.disclosedWeightPct()));
            List<ReportSource> reports = report.sources().stream().map(item -> source(item, holding)).toList();
            for (var stock : report.holdings()) {
                BigDecimal stockAmount = part(holding.amount, stock.weightPct());
                if (stock.themes().isEmpty()) {
                    continue;
                }
                mapped = mapped.add(stockAmount);
                for (var assignment : stock.themes()) {
                    ThemeEvidence evidence = new ThemeEvidence(assignment.sourceName(), assignment.sourceUrl(),
                            assignment.classificationDate(), assignment.method());
                    rows.computeIfAbsent(assignment.id(), key -> new Acc(key, assignment.name(),
                                    definitions.getOrDefault(key, ""), false))
                            .add(stockAmount, holding, reports, assignment.id(), evidence);
                }
            }
        }
        return view("DISCLOSED_STOCKS_WITH_SECURITY_DETAIL", disclosedTotal, pct(coveredAccount, accountAmount),
                pct(mapped, disclosedTotal), rows.values(), excluded);
    }

    private View view(String denominator, BigDecimal total, BigDecimal coverage, BigDecimal mapped,
            java.util.Collection<Acc> values, List<ExcludedHolding> excluded) {
        List<Item> items = values.stream().filter(row -> row.amount.signum() != 0).map(row -> {
            List<Contribution> contributions = row.contributions.stream().map(contribution -> new Contribution(
                    contribution.holding.code, contribution.holding.name, contribution.amount,
                    pct(contribution.amount, total), contribution.sources)).toList();
            return new Item(row.key, row.name, row.description, row.amount, pct(row.amount, total), row.pending,
                    contributions, List.copyOf(row.evidence));
        }).sorted(Comparator.comparing(Item::pending).thenComparing(Item::amount, Comparator.reverseOrder()))
                .collect(Collectors.toCollection(ArrayList::new));
        items.sort((left, right) -> {
            if (left.pending() != right.pending()) return Boolean.compare(left.pending(), right.pending());
            if (denominator.equals("ACCOUNT")) return assetOrder(left.key()) - assetOrder(right.key());
            return right.amount().compareTo(left.amount());
        });
        Set<LocalDate> ends = values.stream().flatMap(row -> row.contributions.stream())
                .flatMap(row -> row.sources.stream()).map(ReportSource::endDate)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        LocalDate start = ends.stream().min(LocalDate::compareTo).orElse(null);
        LocalDate end = ends.stream().max(LocalDate::compareTo).orElse(null);
        return new View(denominator, total, coverage, mapped, start, end, items, excluded);
    }

    private static int assetOrder(String key) {
        return switch (key) {
            case "STOCK" -> 10;
            case "BOND" -> 20;
            case "FUND" -> 30;
            case "CASH_OTHER" -> 40;
            default -> 50;
        };
    }

    private static ReportSource source(FundCompositionResponse.AssetReport report, Holding holding) {
        return new ReportSource(holding.code, holding.name, report.title(), report.endDate(), report.publishedDate(),
                report.sourceUrl(), report.fullDisclosure());
    }

    private static ReportSource source(FundCompositionResponse.IndustryReport report, Holding holding) {
        return new ReportSource(holding.code, holding.name, report.title(), report.endDate(), report.publishedDate(),
                report.sourceUrl(), report.fullDisclosure());
    }

    private static ReportSource source(FundCompositionResponse.ReportSource report, Holding holding) {
        String name = holding.code.equals(report.fundCode()) ? holding.name : report.fundName();
        return new ReportSource(report.fundCode(), name, report.title(), report.endDate(), report.publishedDate(),
                report.sourceUrl(), report.fullDisclosure());
    }

    private static BigDecimal part(BigDecimal amount, BigDecimal weightPct) {
        return amount.multiply(weightPct).divide(HUNDRED, 10, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal amount, BigDecimal total) {
        return total.signum() == 0 ? null : amount.multiply(HUNDRED).divide(total, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(java.util.Collection<Holding> holdings, Function<Holding, BigDecimal> field) {
        return holdings.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record Holding(String code, String name, BigDecimal amount) {
        Holding add(BigDecimal extra) { return new Holding(code, name, amount.add(extra)); }
    }

    private static final class Acc {
        private final String key;
        private final String name;
        private final String description;
        private final boolean pending;
        private BigDecimal amount = BigDecimal.ZERO;
        private final List<PendingContribution> contributions = new ArrayList<>();
        private final Set<ThemeEvidence> evidence = new LinkedHashSet<>();

        private Acc(String key, String name, String description, boolean pending) {
            this.key = key;
            this.name = name;
            this.description = description;
            this.pending = pending;
        }

        private void add(BigDecimal extra, Holding holding, List<ReportSource> sources, String evidenceKey,
                ThemeEvidence itemEvidence) {
            amount = amount.add(extra);
            boolean merged = false;
            for (int index = 0; index < contributions.size(); index++) {
                PendingContribution existing = contributions.get(index);
                if (existing.holding.code.equals(holding.code) && existing.sources.equals(sources)) {
                    contributions.set(index, new PendingContribution(holding, existing.amount.add(extra), sources));
                    merged = true;
                    break;
                }
            }
            if (!merged) contributions.add(new PendingContribution(holding, extra, sources));
            if (evidenceKey != null && itemEvidence != null) evidence.add(itemEvidence);
        }
    }

    private record PendingContribution(Holding holding, BigDecimal amount, List<ReportSource> sources) {}
}
