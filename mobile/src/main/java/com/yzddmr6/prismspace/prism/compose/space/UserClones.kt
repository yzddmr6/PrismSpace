package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.data.PrismAppInfo

/**
 * A dual-space package is the user's 分身: any third-party package, or a system package the
 * profile-side policy makes available by the user's choice. Never a main-space marker.
 */
internal fun isUserClone(isSystem: Boolean, policyEnabled: Boolean): Boolean = !isSystem || policyEnabled

internal fun PrismAppInfo.countsAsUserClone(selfPackage: String): Boolean =
    isInstalled && packageName != selfPackage && isUserClone(isSystem, isPolicyEnabled)
