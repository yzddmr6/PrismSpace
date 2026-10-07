package com.yzddmr6.prismspace.prism.transfer

/** Where transferred files land inside the target user's shared storage. */
internal object TransferPaths {
    /** MediaStore RELATIVE_PATH values (trailing slash, as MediaStore stores them). */
    const val DOWNLOAD_RELATIVE_PATH = "Download/PrismSpace/"
    const val PICTURES_RELATIVE_PATH = "Pictures/PrismSpace/"

    /** User-visible / ledger form of the same folders. */
    const val DOWNLOAD_LOCATION = "Download/PrismSpace"
    const val PICTURES_LOCATION = "Pictures/PrismSpace"

    val knownLocations = setOf(DOWNLOAD_LOCATION, PICTURES_LOCATION)

    fun isImage(mime: String?): Boolean = mime?.startsWith("image/", ignoreCase = true) == true

    fun locationFor(mime: String?): String = if (isImage(mime)) PICTURES_LOCATION else DOWNLOAD_LOCATION
}
