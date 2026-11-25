package cn.unitask.allocation;

import java.util.*;
import java.util.random.RandomGenerator;

final class BalancedAllocation {
    record Slot(long taskId, int required, List<Long> current) {}
    public record Assignment(long taskId, List<Long> newUserIds, int remainingSlots) {}
    public record Load(long userId, long taskCount) {}
    public record Plan(List<Assignment> assignments, List<Load> memberLoads) {}

    static Plan build(List<Slot> slots, Map<Long,Long> initialLoads, RandomGenerator random) {
        var loads = new TreeMap<>(initialLoads);
        var assignments = new ArrayList<Assignment>();
        for (var slot : slots.stream().sorted(Comparator.comparingLong(Slot::taskId)).toList()) {
            var available = new TreeSet<>(loads.keySet());
            available.removeAll(slot.current());
            int open = slot.required() - slot.current().size();
            var added = new ArrayList<Long>();
            while (added.size() < open && !available.isEmpty()) {
                long minimum = available.stream().mapToLong(loads::get).min().orElseThrow();
                var tied = available.stream().filter(user -> loads.get(user) == minimum).toList();
                long selected = tied.get(random.nextInt(tied.size()));
                loads.compute(selected, (user, count) -> count + 1);
                available.remove(selected);
                added.add(selected);
            }
            assignments.add(new Assignment(slot.taskId(), List.copyOf(added), open - added.size()));
        }
        return new Plan(assignments, loads.entrySet().stream().map(e -> new Load(e.getKey(), e.getValue())).toList());
    }

    private BalancedAllocation() {}
}
