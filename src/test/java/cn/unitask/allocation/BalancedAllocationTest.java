package cn.unitask.allocation;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BalancedAllocationTest {
    @Test
    void preservesAssignmentsAndChoosesLowestExistingLoad() {
        var plan = BalancedAllocation.build(List.of(new BalancedAllocation.Slot(20,1,List.of()),
                new BalancedAllocation.Slot(10,3,List.of(1L))),Map.of(1L,5L,2L,0L),new Random(4));
        assertThat(plan.assignments()).containsExactly(
                new BalancedAllocation.Assignment(10,List.of(2L),1),
                new BalancedAllocation.Assignment(20,List.of(2L),0));
        assertThat(plan.memberLoads()).containsExactly(new BalancedAllocation.Load(1,5),new BalancedAllocation.Load(2,2));
    }
}
