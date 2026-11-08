package com.chakker.phoneblocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Registering as a device admin means the app can't be uninstalled until admin is
 * deactivated first. It is friction, not a wall.
 */
class BlockerDeviceAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Deactivating this makes the blocker uninstallable. Is this 1am-you talking?"
}
