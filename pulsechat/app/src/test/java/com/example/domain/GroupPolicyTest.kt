package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPolicyTest {
    private val roster = GroupRoster(
        participants = listOf("owner", "admin2", "m1", "m2"),
        admins = listOf("owner", "admin2"),
        createdBy = "owner",
        onlyAdminsCanSend = false,
        onlyAdminsCanEditInfo = true,
    )

    @Test fun permissions() {
        assertTrue(GroupPolicy.canSend(roster, "m1"))
        assertFalse(GroupPolicy.canSend(roster, "stranger"))
        assertFalse(GroupPolicy.canSend(roster.copy(onlyAdminsCanSend = true), "m1"))
        assertTrue(GroupPolicy.canSend(roster.copy(onlyAdminsCanSend = true), "admin2"))
        assertFalse(GroupPolicy.canEditInfo(roster, "m1"))
        assertTrue(GroupPolicy.canEditInfo(roster.copy(onlyAdminsCanEditInfo = false), "m1"))
        assertFalse(GroupPolicy.canChangeSettings(roster, "m1"))
    }

    @Test fun memberManagement() {
        assertTrue(GroupPolicy.canAdd(roster, "admin2", listOf("new")))
        assertFalse(GroupPolicy.canAdd(roster, "m1", listOf("new")))
        assertFalse(GroupPolicy.canAdd(roster, "owner", listOf("m1")))
        assertTrue(GroupPolicy.canRemove(roster, "admin2", "m1"))
        assertFalse(GroupPolicy.canRemove(roster, "admin2", "owner"))   // creator is protected
        assertFalse(GroupPolicy.canRemove(roster, "m1", "m2"))
        assertFalse(GroupPolicy.canRemove(roster, "owner", "owner"))
        assertTrue(GroupPolicy.canPromote(roster, "owner", "m1"))
        assertFalse(GroupPolicy.canPromote(roster, "owner", "admin2"))
        assertTrue(GroupPolicy.canDemote(roster, "owner", "admin2"))
        assertFalse(GroupPolicy.canDemote(roster, "admin2", "owner"))
        val removed = GroupPolicy.remove(roster, "admin2")
        assertFalse("admin2" in removed.participants || "admin2" in removed.admins)
    }

    @Test fun lastAdminLeavingTransfersAdmin() {
        val solo = GroupRoster(listOf("a", "b", "c"), listOf("a"), "a")
        val out = GroupPolicy.leave(solo, "a") as GroupPolicy.LeaveOutcome.Left
        assertEquals(listOf("b", "c"), out.roster.participants)
        assertEquals(listOf("b"), out.roster.admins)
        assertEquals("b", out.promoted)
        val withOtherAdmin = GroupPolicy.leave(roster, "owner") as GroupPolicy.LeaveOutcome.Left
        assertNull(withOtherAdmin.promoted)
        assertEquals(listOf("admin2"), withOtherAdmin.roster.admins)
        assertTrue(GroupPolicy.leave(roster, "x") is GroupPolicy.LeaveOutcome.NotMember)
        val last = GroupPolicy.leave(GroupRoster(listOf("a"), listOf("a"), "a"), "a") as GroupPolicy.LeaveOutcome.Left
        assertTrue(last.roster.participants.isEmpty() && last.roster.admins.isEmpty())
    }

    @Test fun createValidation() {
        assertEquals(listOf(GroupError.NAME_EMPTY, GroupError.NO_MEMBERS), GroupPolicy.validateCreate(" ", "", listOf("me"), "me"))
        assertTrue(GroupPolicy.validateCreate("Team", "", listOf("me", "bob"), "me").isEmpty())
        assertTrue(GroupError.TOO_MANY_MEMBERS in GroupPolicy.validateCreate("Big", "", (1..300).map { "u$it" }, "me"))
    }
}
