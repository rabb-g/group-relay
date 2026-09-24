package com.sh7411usa.jrelay.sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SubgroupPlannerTest {

    // ---- the hard rule ----

    @Test
    public void planFor_neverProposesMovingAnAlreadyAssignedMember() {
        // Reshuffling an existing sub-group breaks a live group-MMS thread on nine handsets and
        // invalidates contacts members already saved from the roster. planFor only ever receives
        // unassigned member ids as input, so an already-assigned member cannot even appear as a
        // candidate for assignment - this test pins that only unassigned ids ever get an
        // Assignment, and that an over-full existing group (a case that "looks like" it wants
        // rebalancing) produces an advisory note only, never an assignment touching it.
        List<Long> unassigned = List.of(100L, 101L);
        Map<Integer, Integer> currentSizes = new LinkedHashMap<>();
        currentSizes.put(1, 12); // over target; a naive rebalancer would want to move members out
        currentSizes.put(2, 3);

        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, currentSizes, 9);

        Set<Long> assignedIds = new HashSet<>();
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            assignedIds.add(a.memberId);
        }
        // Only the ids we declared unassigned may be assigned - nothing already in group 1 or 2
        // is touched.
        assertEquals(Set.of(100L, 101L), assignedIds);
        boolean notedOverFull = false;
        for (String note : plan.advisoryNotes) {
            if (note.contains("1") && note.toLowerCase().contains("over target")) {
                notedOverFull = true;
            }
        }
        assertTrue("over-full group 1 should surface as advisory text only", notedOverFull);
    }

    // ---- filling existing groups before creating new ones ----

    @Test
    public void planFor_fillsExistingUnderFullSubgroupsBeforeCreatingNewOnes() {
        List<Long> unassigned = List.of(1L, 2L, 3L);
        Map<Integer, Integer> currentSizes = new LinkedHashMap<>();
        currentSizes.put(1, 7); // 2 open slots at target 9

        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, currentSizes, 9);

        int placedInGroup1 = 0;
        boolean anyNewGroup = false;
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            if (a.subgroupId == 1) {
                placedInGroup1++;
                assertFalse("group 1 already exists and must not be flagged new", a.isNewSubgroup);
            }
            if (a.isNewSubgroup) {
                anyNewGroup = true;
            }
        }
        // All three land in group 1: two fill its open slots, and the third -- which cannot form a
        // viable group on its own -- falls back onto the group this plan just touched, a
        // deliberate overfill to 10 rather than stranding somebody in a thread of one.
        // (An earlier version of this test asserted 2 here while also asserting no new group and
        // three total assignments, which cannot all hold at once.)
        assertEquals(3, placedInGroup1);
        assertFalse(anyNewGroup);
        assertEquals(3, plan.assignments.size());
    }

    // ---- bulk placement with no stranding ----

    @Test
    public void planFor_placesOneHundredUnassignedMembersExactlyOnceWithNoGroupBelowMinimum() {
        List<Long> unassigned = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            unassigned.add(i);
        }
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, new HashMap<>(), 9);

        assertEquals(100, plan.assignments.size());
        Set<Long> seen = new HashSet<>();
        Map<Integer, Integer> groupCounts = new HashMap<>();
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            assertTrue("member placed more than once: " + a.memberId, seen.add(a.memberId));
            groupCounts.merge(a.subgroupId, 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> e : groupCounts.entrySet()) {
            assertTrue("group " + e.getKey() + " below minimum viable size: " + e.getValue(),
                    e.getValue() >= SubgroupPlanner.MIN_NEW_SUBGROUP_SIZE);
        }
    }

    // ---- trailing remainder ----

    @Test
    public void planFor_trailingRemainderOfOneDoesNotFormItsOwnSubgroup() {
        List<Long> unassigned = new ArrayList<>();
        for (long i = 1; i <= 10; i++) { // 9 + 1 leftover
            unassigned.add(i);
        }
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, new HashMap<>(), 9);

        Map<Integer, Integer> groupCounts = new HashMap<>();
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            groupCounts.merge(a.subgroupId, 1, Integer::sum);
        }
        assertEquals(1, groupCounts.size());
        assertEquals(10, (int) groupCounts.values().iterator().next());
    }

    @Test
    public void planFor_trailingRemainderOfTwoDoesNotFormItsOwnSubgroup() {
        List<Long> unassigned = new ArrayList<>();
        for (long i = 1; i <= 11; i++) { // 9 + 2 leftover
            unassigned.add(i);
        }
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, new HashMap<>(), 9);

        Map<Integer, Integer> groupCounts = new HashMap<>();
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            groupCounts.merge(a.subgroupId, 1, Integer::sum);
        }
        assertEquals(1, groupCounts.size());
        assertEquals(11, (int) groupCounts.values().iterator().next());
    }

    // ---- too few people, nowhere to put them ----

    @Test
    public void planFor_tooFewPeopleAndNoExistingGroupAreLeftUnassignedWithAdvisoryNote() {
        List<Long> unassigned = List.of(1L, 2L);
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, new HashMap<>(), 9);

        assertTrue("must not be forced into a stub group", plan.assignments.isEmpty());
        assertFalse("must explain why nothing was assigned", plan.advisoryNotes.isEmpty());
    }

    // ---- over-full existing group is advisory only ----

    @Test
    public void planFor_overFullExistingSubgroupIsLeftAloneAndReportedAsAdviceOnly() {
        List<Long> unassigned = List.of(1L);
        Map<Integer, Integer> currentSizes = new HashMap<>();
        currentSizes.put(5, 12); // already over target of 9

        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, currentSizes, 9);

        for (SubgroupPlanner.Assignment a : plan.assignments) {
            assertFalse("over-full group must never receive a new assignment", a.subgroupId == 5);
        }
        boolean mentionsGroup5 = false;
        for (String note : plan.advisoryNotes) {
            if (note.contains("5")) {
                mentionsGroup5 = true;
            }
        }
        assertTrue("advisory notes must mention the over-full group", mentionsGroup5);
    }

    // ---- nonsense target sizes ----

    @Test
    public void planFor_targetSizeZeroIsRejectedWithNoteAndNoAssignments() {
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(List.of(1L, 2L, 3L), new HashMap<>(), 0);
        assertTrue(plan.assignments.isEmpty());
        assertFalse(plan.advisoryNotes.isEmpty());
    }

    @Test
    public void planFor_targetSizeOneIsRejectedWithNoteAndNoAssignments() {
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(List.of(1L, 2L, 3L), new HashMap<>(), 1);
        assertTrue(plan.assignments.isEmpty());
        assertFalse(plan.advisoryNotes.isEmpty());
    }

    // ---- determinism ----

    @Test
    public void planFor_isDeterministicAcrossRepeatedCallsEvenWithHashMapCurrentSizes() {
        // currentSizes is deliberately a HashMap, whose iteration order is not guaranteed, to pin
        // that planFor's own TreeMap-based ordering - not incoming iteration order - drives the
        // result. Without this, an admin could see a different proposal each time they preview.
        List<Long> unassigned = new ArrayList<>();
        for (long i = 1; i <= 37; i++) {
            unassigned.add(i);
        }
        Map<Integer, Integer> currentSizes = new HashMap<>();
        currentSizes.put(3, 4);
        currentSizes.put(1, 8);
        currentSizes.put(9, 2);
        currentSizes.put(5, 9);
        currentSizes.put(2, 1);

        SubgroupPlanner.Plan first = SubgroupPlanner.planFor(unassigned, currentSizes, 9);
        SubgroupPlanner.Plan second = SubgroupPlanner.planFor(unassigned, currentSizes, 9);

        assertEquals(first.assignments.size(), second.assignments.size());
        for (int i = 0; i < first.assignments.size(); i++) {
            SubgroupPlanner.Assignment a = first.assignments.get(i);
            SubgroupPlanner.Assignment b = second.assignments.get(i);
            assertEquals(a.memberId, b.memberId);
            assertEquals(a.subgroupId, b.subgroupId);
            assertEquals(a.isNewSubgroup, b.isNewSubgroup);
        }
        assertEquals(first.advisoryNotes, second.advisoryNotes);
    }
}
