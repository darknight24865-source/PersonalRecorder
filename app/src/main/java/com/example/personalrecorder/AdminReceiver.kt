package com.example.personalrecorder

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Minimal device-admin receiver (milestone m8).
 *
 * Declaring a DeviceAdminReceiver is what makes `dpm set-device-owner`
 * enrollment possible. Once the app is the device owner, UpdateChecker's
 * PackageInstaller sessions run fully silently (no install UI at all).
 *
 * Enrollment is a deliberate, documented adb step:
 *
 *     adb shell dpm set-device-owner com.example.personalrecorder/.AdminReceiver
 *
 * The receiver declares no policies -- it exists only so the platform
 * recognizes the app as a device-owner candidate. Without it,
 * isDeviceOwnerApp() is always false and updates fall back to the
 * one-tap system installer.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Device-owner enrollment succeeded; silent updates are now possible.
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Enrollment removed; updates fall back to the one-tap installer.
    }
}
