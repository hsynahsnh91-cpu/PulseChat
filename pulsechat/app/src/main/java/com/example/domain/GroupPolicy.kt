package com.example.domain

/** Group roster snapshot. Firestore rules enforce the same constraints server-side. */
data class GroupRoster(
    val participants: List<String>,
    val admins: List<String>,
    val createdBy: String?,
    val onlyAdminsCanSend: Boolean = false,
    val onlyAdminsCanEditInfo: Boolean = true,
)

enum class GroupError { NAME_EMPTY, NAME_TOO_LONG, DESCRIPTION_TOO_LONG, NO_MEMBERS, TOO_MANY_MEMBERS }

object GroupPolicy {
    const val MAX_MEMBERS = 256
    const val MAX_NAME = 100
    const val MAX_DESCRIPTION = 512

    fun isMember(r: GroupRoster, uid: String) = uid in r.participants
    fun isAdmin(r: GroupRoster, uid: String) = uid in r.participants && uid in r.admins

    fun canSend(r: GroupRoster, uid: String) = isMember(r, uid) && (!r.onlyAdminsCanSend || isAdmin(r, uid))
    fun canEditInfo(r: GroupRoster, uid: String) = isMember(r, uid) && (!r.onlyAdminsCanEditInfo || isAdmin(r, uid))
    fun canChangeSettings(r: GroupRoster, uid: String) = isAdmin(r, uid)

    fun canAdd(r: GroupRoster, actor: String, newMembers: Collection<String>): Boolean {
        val fresh = newMembers.filter { it !in r.participants }.toSet()
        return isAdmin(r, actor) && fresh.isNotEmpty() && r.participants.size + fresh.size <= MAX_MEMBERS
    }

    /** Admins may remove anyone except themselves (use leave) and the group creator. */
    fun canRemove(r: GroupRoster, actor: String, target: String) =
        isAdmin(r, actor) && actor != target && target in r.participants && target != r.createdBy

    fun canPromote(r: GroupRoster, actor: String, target: String) =
        isAdmin(r, actor) && target in r.participants && target !in r.admins

    fun canDemote(r: GroupRoster, actor: String, target: String) =
        isAdmin(r, actor) && target in r.admins && target != actor && target != r.createdBy

    sealed class LeaveOutcome {
        data class Left(val roster: GroupRoster, val promoted: String?) : LeaveOutcome()
        object NotMember : LeaveOutcome()
    }

    /**
     * Leaving removes the member and their admin role. If they were the last admin and members
     * remain, admin rights are transferred to the longest-standing remaining member, so a group is
     * never left without an admin.
     */
    fun leave(r: GroupRoster, uid: String): LeaveOutcome {
        if (uid !in r.participants) return LeaveOutcome.NotMember
        val participants = r.participants.filter { it != uid }
        var admins = r.admins.filter { it != uid && it in participants }
        var promoted: String? = null
        if (admins.isEmpty() && participants.isNotEmpty()) {
            promoted = participants.first()
            admins = listOf(promoted)
        }
        return LeaveOutcome.Left(r.copy(participants = participants, admins = admins), promoted)
    }

    fun remove(r: GroupRoster, target: String): GroupRoster =
        r.copy(participants = r.participants.filter { it != target }, admins = r.admins.filter { it != target })

    fun validateCreate(name: String, description: String, memberIds: Collection<String>, creator: String): List<GroupError> {
        val errors = mutableListOf<GroupError>()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) errors += GroupError.NAME_EMPTY
        if (trimmed.length > MAX_NAME) errors += GroupError.NAME_TOO_LONG
        if (description.trim().length > MAX_DESCRIPTION) errors += GroupError.DESCRIPTION_TOO_LONG
        val others = memberIds.filter { it != creator }.toSet()
        if (others.isEmpty()) errors += GroupError.NO_MEMBERS
        if (others.size + 1 > MAX_MEMBERS) errors += GroupError.TOO_MANY_MEMBERS
        return errors
    }
}
