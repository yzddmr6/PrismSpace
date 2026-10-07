package com.yzddmr6.prismspace.prism.transfer

/**
 * Plans one batch from resolved sources. The receiving user is deliberately NOT an input: the same
 * resolutions plan the same transfer whether the receiver runs in the main or the dual space.
 */
internal object TransferBatchPlanner {

    /**
     * @param parentUserId the main space's user id.
     * @param dualUserId the PrismSpace-managed dual space (`Users.profile` in the main space, the
     *   current user inside it); null when no dual space exists.
     */
    fun plan(
        entry: TransferEntry,
        refs: List<SourceRef>,
        resolutions: List<SourceResolution>,
        parentUserId: Int?,
        dualUserId: Int?,
        newId: () -> String,
    ): BatchPlan {
        require(refs.size == resolutions.size) { "Every ref needs exactly one resolution" }
        if (refs.isEmpty()) return BatchPlan.Rejected(BatchRejection.NoFiles)
        val resolved = resolutions.filterIsInstance<SourceResolution.Resolved>()
        if (resolved.isEmpty()) return BatchPlan.Rejected(BatchRejection.AllUnreadable)
        val sourceUsers = resolved.map { it.sourceUserId }.distinct()
        if (sourceUsers.size > 1) return BatchPlan.Rejected(BatchRejection.MixedSourceUsers)
        val source = sourceUsers.single()
        val destination = when {
            parentUserId != null && source == parentUserId ->
                TransferDestination.OtherSpace(source, dualUserId, SpaceRole.Dual)
            dualUserId != null && source == dualUserId ->
                TransferDestination.OtherSpace(source, parentUserId, SpaceRole.Main)
            else -> return BatchPlan.Rejected(BatchRejection.UnmanagedSourceUser)
        }
        val skipped = refs.filterIndexed { index, _ -> resolutions[index] is SourceResolution.Unreadable }
        return BatchPlan.Ready(
            TransferRequest(
                batchId = newId(),
                entry = entry,
                destination = destination,
                items = resolved.map { TransferItem(newId(), it) },
                skipped = skipped,
            ),
        )
    }

    /** Rejections are logged with the distinct source users only (no names, no URIs). */
    fun sourceUsers(resolutions: List<SourceResolution>): List<Int> =
        resolutions.filterIsInstance<SourceResolution.Resolved>().map { it.sourceUserId }.distinct()
}
