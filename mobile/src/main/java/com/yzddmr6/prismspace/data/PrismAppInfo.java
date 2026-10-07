package com.yzddmr6.prismspace.data;

import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.QUERY_ALL_PACKAGES;
import static android.content.Context.LAUNCHER_APPS_SERVICE;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.Q;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;

import androidx.annotation.Nullable;

import com.yzddmr6.prismspace.common.app.AppInfo;
import com.yzddmr6.prismspace.util.Hacks;
import com.yzddmr6.prismspace.util.Suppliers;
import com.yzddmr6.prismspace.util.Users;

import java.util.function.Supplier;

/**
 * PrismSpace-specific {@link AppInfo}
 *
 * Created by Oasis on 2016/8/10.
 */
public class PrismAppInfo extends AppInfo {

	void setHidden(final boolean state) {
		final Integer private_flags = Hacks.ApplicationInfo_privateFlags.get(this);
		if (private_flags != null)
			Hacks.ApplicationInfo_privateFlags.set(this, state ? private_flags | PRIVATE_FLAG_HIDDEN : private_flags & ~ PRIVATE_FLAG_HIDDEN);
	}

	/** System apps the profile-side policy keeps out of the space are treated as "disabled". */
	public boolean shouldShowAsEnabled() {
		return enabled && ! isHiddenSysPrismAppTreatedAsDisabled();
	}

	/** Reads the explicit policy target, never the ambiguous "hidden but not suspended" encoding. */
	public boolean isHiddenSysPrismAppTreatedAsDisabled() {
		return isSystem() && isPolicyHidden();
	}

	private boolean isPolicyHidden() {
		return ((PrismAppListProvider) mProvider).isPolicyHidden(user, packageName);
	}

	/** A system package the profile-side policy makes available by the user's choice (the user's 分身). */
	public boolean isPolicyEnabled() {
		return isSystem() && ((PrismAppListProvider) mProvider).isPolicyEnabled(user, packageName);
	}

	/** @return whether this package is critical to the system, thus should not be frozen or disabled. */
	public boolean isCritical() {
		return ((PrismAppListProvider) mProvider).isCritical(packageName);
	}

	public boolean canQueryAllPackages() { return mCanQueryAllPackages.get(); }
	private boolean checkCanQueryAllPackages() {
		return SDK_INT <= Q || targetSdkVersion <= Q
				|| context().checkPermission(QUERY_ALL_PACKAGES, -1, uid) == PERMISSION_GRANTED;
	}
	private final Supplier<Boolean> mCanQueryAllPackages = Suppliers.memoize(this::checkCanQueryAllPackages);

	public boolean canManageExternalStorage() { return mCanManageExternalStorage.get(); }
	private boolean checkCanManageExternalStorage() {
		return SDK_INT > Q && targetSdkVersion > Q
				&& context().checkPermission(MANAGE_EXTERNAL_STORAGE, -1, uid) == PERMISSION_GRANTED;
	}
	private final Supplier<Boolean> mCanManageExternalStorage = Suppliers.memoize(this::checkCanManageExternalStorage);

	/** Is launchable (even if hidden) */
	@Override public boolean isLaunchable() { return mIsLaunchable.get(); }
	private final Supplier<Boolean> mIsLaunchable = Suppliers.memoizeWithExpiration(	// Use GET_DISABLED_COMPONENTS in case mainland sibling is disabled.
			() -> checkLaunchable(Hacks.RESOLVE_ANY_USER_AND_UNINSTALLED | MATCH_DISABLED_COMPONENTS), 1, SECONDS);

	@Override protected boolean checkLaunchable(final int flags_for_resolve) {
		if (! Users.isParentProfile(user) && ! isHidden()) {		// Accurate detection for non-frozen app in PrismSpace
			try { return ! requireNonNull((LauncherApps) context().getSystemService(LAUNCHER_APPS_SERVICE)).getActivityList(packageName, user).isEmpty(); }
			catch (final SecurityException e) { return false; } // "SecurityException: Cannot retrieve activities for unrelated profile NNN" appeared on OPPO A3s and Vivo 1718 (both Android 8.1).
		}
		return super.checkLaunchable(flags_for_resolve);	// Inaccurate detection for frozen app (false-positive if launcher activity is actually disabled)
	}

	@Override public PrismAppInfo getLastInfo() { return (PrismAppInfo) super.getLastInfo(); }

	public PrismAppInfo cloneWithLabel(final CharSequence label) {
		return new PrismAppInfo((PrismAppListProvider) mProvider, user, this, getLastInfo(), label);
	}

	PrismAppInfo(final PrismAppListProvider provider, final UserHandle user, final ApplicationInfo base, final @Nullable PrismAppInfo last) {
		this(provider, user, base, last, null);
	}

	PrismAppInfo(final PrismAppListProvider provider, final UserHandle user, final ApplicationInfo base,
	                      final @Nullable PrismAppInfo last, final @Nullable CharSequence label) {
		super(provider, base, last, label);
		this.user = user;
	}

	@Override public StringBuilder buildToString(final Class<?> clazz) {
		final StringBuilder builder = super.buildToString(clazz).append(", user ").append(Users.toId(user));
		if (isHidden()) builder.append(! Users.isParentProfile(user) && isHiddenSysPrismAppTreatedAsDisabled() ? ", hidden (as disabled)" : ", hidden");
		return builder;
	}

	public final UserHandle user;
}
