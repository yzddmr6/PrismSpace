package com.yzddmr6.prismspace.provisioning

/**
 * Built-in default set of system apps made available in a dual space once the user confirms the
 * selection page. Packages missing on a device are reported as absent by the applier.
 */
object SystemAppDefaults {
    val packages: Set<String> = linkedSetOf(
        "com.android.browser",              // Browser (system package on HyperOS)
        "com.android.chrome",               // Only effective when preinstalled as a system package
        "com.android.camera",               // Camera
        "com.android.camera2",              // AOSP camera package name
        "com.google.android.GoogleCamera",  // Pixel camera package name
        "com.android.contacts",             // Contacts (MIUI dialer lives here too)
        // File managers are already critical; listed so the selection page copy stays uniform.
        "com.android.fileexplorer",
        "com.google.android.documentsui",
        "com.android.documentsui",
    )
}

/** The default set only takes effect after the user confirmed the selection page. */
fun effectiveDefaultSet(status: SelectionStatus?): Set<String> =
    if (status == SelectionStatus.Confirmed) SystemAppDefaults.packages else emptySet()
