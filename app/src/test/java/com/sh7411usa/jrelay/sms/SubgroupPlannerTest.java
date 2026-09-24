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
        // Previously this asserted all 3 unassigned members landed in group 1 (2 filling its open
        // slots, 1 overfilling it to 10) -- protecting the old fold-into-last-group fallback. The
        // owner removed that fallback: a group MMS thread is capped by the carrier and jRelay is
        // already an 11th body in a "group of 10", so growing a sized thread by folding people in
        // is no longer acceptable. Now this test protects the replacement rule: fill exactly the
        // open slots in the existing group, and leave anything left over unassigned (with an
        // advisory explaining they'll get an individual text) rather than overfill.
        List<Long> unassigned = List.of(1L, 2L, 3L);
        Map<Integer, Integer> currentSizes = new LinkedHashMap<>();
        currentSizes.put(1, 7); // 2 open slots at target 9

        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, currentSizes, 9);

        int placedInGroup1 = 0;
        Set<Long> assignedIds = new HashSet<>();
        boolean anyNewGroup = false;
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            assignedIds.add(a.memberId);
            if (a.subgroupId == 1) {
                placedInGroup1++;
                assertFalse("group 1 already exists and must not be flagged new", a.isNewSubgroup);
            }
            if (a.isNewSubgroup) {
                anyNewGroup = true;
            }
        }
        // Exactly the 2 open slots in group 1 are filled; member 3L is left unassigned rather than
        // overfilling group 1 to 10 or spinning up a new group of one.
        assertEquals(2, placedInGroup1);
        assertFalse(anyNewGroup);
        assertEquals(2, plan.assignments.size());
        assertFalse("member 3L must not be assigned anywhere", assignedIds.contains(3L));
        assertFalse("advisory note must explain the unassigned member", plan.advisoryNotes.isEmpty());
    }

    // ---- bulk placement with no stranding ----

    @Test
    public void planFor_placesOneHundredUnassignedMembersAtMostOnceWithNoGroupOverOrUnderSized() {
        // 100 = 11 * 9 + 1: previously the trailing 1 was folded into the 11th group, making it a
        // group of 10 and landing all 100 members somewhere. Under the new rule that trailing
        // member is left unassigned instead, so every group is exactly targetSize (9) and exactly
        // one member goes unplaced.
        List<Long> unassigned = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            unassigned.add(i);
        }
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, new HashMap<>(), 9);

        assertEquals(99, plan.assignments.size());
        Set<Long> seen = new HashSet<>();
        Map<Integer, Integer> groupCounts = new HashMap<>();
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            assertTrue("member placed more than once: " + a.memberId, seen.add(a.memberId));
            groupCounts.merge(a.subgroupId, 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> e : groupCounts.entrySet()) {
            assertEquals("group " + e.getKey() + " must be exactly target size", 9, (int) e.getValue());
        }
        assertFalse("advisory note must explain the unassigned member", plan.advisoryNotes.isEmpty());
    }

    // ---- trailing remainder ----

    @Test
    public void planFor_trailingRemainderOfOneIsLeftUnassignedNotFoldedIntoTheNewGroup() {
        // Previously this asserted the group of 9 was pulled up to 10 so the trailing 1 wasn't
        // "stranded" -- protecting the old rule that a sub-group thread could be quietly
        // overfilled to avoid a stub. The owner reversed that: overfilling risks the carrier's
        // group-MMS recipient cap, and an unassigned member isn't stranded, they just get an
        // individual SMS like everyone does today. Now this protects that the new group stays at
        // exactly targetSize and the 10th member is left unassigned with an advisory note.
        List<Long> unassigned = new ArrayList<>();
        for (long i = 1; i <= 10; i++) { // 9 + 1 leftover
            unassigned.add(i);
        }
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, new HashMap<>(), 9);

        Map<Integer, Integer> groupCounts = new HashMap<>();
        Set<Long> assignedIds = new HashSet<>();
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            groupCounts.merge(a.subgroupId, 1, Integer::sum);
            assignedIds.add(a.memberId);
        }
        assertEquals(1, groupCounts.size());
        assertEquals(9, (int) groupCounts.values().iterator().next());
        assertFalse("member 10 must be left unassigned, not folded in", assignedIds.contains(10L));
        assertFalse("advisory note must explain the unassigned member", plan.advisoryNotes.isEmpty());
    }

    @Test
    public void planFor_trailingRemainderOfTwoIsLeftUnassignedNotFoldedIntoTheNewGroup() {
        // Same reversal as the remainder-of-one case above: the group of 9 must stay at 9, and
        // the trailing 2 are left unassigned (individual SMS) rather than overfilled to 11.
        List<Long> unassigned = new ArrayList<>();
        for (long i = 1; i <= 11; i++) { // 9 + 2 leftover
            unassigned.add(i);
        }
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, new HashMap<>(), 9);

        Map<Integer, Integer> groupCounts = new HashMap<>();
        Set<Long> assignedIds = new HashSet<>();
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            groupCounts.merge(a.subgroupId, 1, Integer::sum);
            assignedIds.add(a.memberId);
        }
        assertEquals(1, groupCounts.size());
        assertEquals(9, (int) groupCounts.values().iterator().next());
        assertFalse("member 10 must be left unassigned, not folded in", assignedIds.contains(10L));
        assertFalse("member 11 must be left unassigned, not folded in", assignedIds.contains(11L));
        assertFalse("advisory note must explain the unassigned members", plan.advisoryNotes.isEmpty());
    }

    // ---- leftover members left unassigned get individual SMS (new behaviour) ----

    @Test
    public void planFor_leftoverMemberIsUnassignedAndNoteExplainsIndividualSms() {
        // Pins the owner's new rule end-to-end: a group that has room for 8 but 9 unassigned
        // members are offered fills exactly 8 of them, leaving 1 leftover that cannot form its
        // own group of MIN_NEW_SUBGROUP_SIZE and has no other under-full group to join. That
        // member must be unassigned, and the advisory note is the only thing telling an admin
        // reading the plan summary that the member is still covered -- via an individual text --
        // rather than dropped.
        List<Long> unassigned = new ArrayList<>();
        for (long i = 1; i <= 9; i++) {
            unassigned.add(i);
        }
        Map<Integer, Integer> currentSizes = new HashMap<>();
        currentSizes.put(1, 1); // 8 open slots at target 9

        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassigned, currentSizes, 9);

        Set<Long> assignedIds = new HashSet<>();
        for (SubgroupPlanner.Assignment a : plan.assignments) {
            assignedIds.add(a.memberId);
        }
        assertEquals(8, assignedIds.size());
        assertFalse("member 9L must be left unassigned", assignedIds.contains(9L));

        boolean explainsIndividualSms = false;
        for (String note : plan.advisoryNotes) {
            String lower = note.toLowerCase();
            if (lower.contains("unassigned") && lower.contains("individual")
                    && lower.contains("sms")) {
                explainsIndividualSms = true;
            }
        }
        assertTrue("advisory note must tell the admin the leftover member gets an individual SMS",
                explainsIndividualSms);
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
