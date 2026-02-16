package ez.backend.turbo.simulation;

import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.simulation.ZoneLinkResolver.ZoneLinkSet;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ZonePolicyIndex {

    private static final Logger log = LogManager.getLogger(ZonePolicyIndex.class);

    private final Map<Id<Link>, List<BanRule>> linkBans;
    private final Map<Id<Link>, List<EnforcementRule>> entryRules;
    private final Map<Id<Link>, Set<String>> exitZones;
    private final boolean hasAnyBans;

    public record BanRule(String vehicleType, double startTime, double endTime) {}

    public record EnforcementRule(String zoneId, String vehicleType, int tier,
                                  double startTime, double endTime,
                                  double penalty, double intervalSeconds) {}

    private ZonePolicyIndex(Map<Id<Link>, List<BanRule>> linkBans,
                            Map<Id<Link>, List<EnforcementRule>> entryRules,
                            Map<Id<Link>, Set<String>> exitZones,
                            boolean hasAnyBans) {
        this.linkBans = linkBans;
        this.entryRules = entryRules;
        this.exitZones = exitZones;
        this.hasAnyBans = hasAnyBans;
    }

    public static ZonePolicyIndex build(List<SimulationRequest.Zone> zones,
                                        List<ZoneLinkSet> zoneLinkSets) {
        Map<Id<Link>, List<BanRule>> linkBans = new HashMap<>();
        Map<Id<Link>, List<EnforcementRule>> entryRules = new HashMap<>();
        Map<Id<Link>, Set<String>> exitZones = new HashMap<>();
        boolean hasAnyBans = false;
        int ruleCount = 0;

        for (int i = 0; i < zones.size(); i++) {
            SimulationRequest.Zone zone = zones.get(i);
            ZoneLinkSet linkSet = zoneLinkSets.get(i);

            for (SimulationRequest.Policy policy : zone.getPolicies()) {
                if (policy.getTier() == 1) continue;

                double startTime = parseTime(policy.getPeriod().get(0));
                double endTime = parseTime(policy.getPeriod().get(1));
                ruleCount++;

                if (policy.getTier() == 3) {
                    hasAnyBans = true;
                    BanRule ban = new BanRule(policy.getVehicleType(), startTime, endTime);
                    for (Id<Link> linkId : linkSet.allLinks()) {
                        linkBans.computeIfAbsent(linkId, k -> new ArrayList<>()).add(ban);
                    }
                }

                double penalty = policy.getPenalty() != null ? policy.getPenalty() : 0.0;
                double intervalSeconds = policy.getInterval() != null ? policy.getInterval() * 60.0 : 0.0;
                EnforcementRule rule = new EnforcementRule(
                        zone.getId(), policy.getVehicleType(), policy.getTier(),
                        startTime, endTime, penalty, intervalSeconds);

                for (Id<Link> linkId : linkSet.entryGateways()) {
                    entryRules.computeIfAbsent(linkId, k -> new ArrayList<>()).add(rule);
                }

                if (policy.getTier() == 2) {
                    for (Id<Link> linkId : linkSet.exitGateways()) {
                        exitZones.computeIfAbsent(linkId, k -> new HashSet<>()).add(zone.getId());
                    }
                }
            }
        }

        log.info(L.msg("simulation.policy.index"), zones.size(), ruleCount);

        return new ZonePolicyIndex(
                Collections.unmodifiableMap(linkBans),
                Collections.unmodifiableMap(entryRules),
                Collections.unmodifiableMap(exitZones),
                hasAnyBans);
    }

    public boolean isBanned(Id<Link> linkId, String vehicleType, double time) {
        List<BanRule> bans = linkBans.get(linkId);
        if (bans == null) return false;
        for (BanRule ban : bans) {
            if (ban.vehicleType.equals(vehicleType)
                    && time >= ban.startTime && time < ban.endTime) {
                return true;
            }
        }
        return false;
    }

    public List<EnforcementRule> getEntryRules(Id<Link> linkId) {
        return entryRules.get(linkId);
    }

    public Set<String> getExitZones(Id<Link> linkId) {
        return exitZones.get(linkId);
    }

    public boolean hasAnyBans() {
        return hasAnyBans;
    }

    private static double parseTime(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 3600.0 + Integer.parseInt(parts[1]) * 60.0;
    }
}
