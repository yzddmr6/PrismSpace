package com.yzddmr6.prismspace.settings.profile

/**
 * One install per app. Staging a large suite took 26 s from tap to confirmation (#29); every
 * impatient tap staged another full copy of it, and their confirmations stacked up later.
 */
internal enum class RepeatInstallTap {
    /** The session is still being staged, or checked by the system after commit: nothing to show yet. */
    StillPreparing,

    /** The confirmation is waiting. The installer's own task is excluded from recents, so this is the way back to it. */
    ShowConfirmation,

    /** The system no longer has the session: forget it and stage a fresh one. */
    StartOver,
}

/**
 * What another tap on Install does while the same app's install has not finished.
 *
 * @param sessionAlive whether the system still has the session; null while it is being created.
 * @param confirmationShown whether its confirmation has reached the screen.
 */
internal fun repeatInstallTap(sessionAlive: Boolean?, confirmationShown: Boolean): RepeatInstallTap = when {
    sessionAlive == false -> RepeatInstallTap.StartOver
    confirmationShown -> RepeatInstallTap.ShowConfirmation
    else -> RepeatInstallTap.StillPreparing
}

/**
 * Whether a session status broadcast is about the session tracked for its package. Any other
 * session's status must leave the current install alone: abandoning a session whose confirmation
 * could not be shown makes the system report it as aborted. A status without a session id is taken.
 */
internal fun isStatusFor(trackedSessionId: Int, statusSessionId: Int): Boolean =
    statusSessionId < 0 || statusSessionId == trackedSessionId
