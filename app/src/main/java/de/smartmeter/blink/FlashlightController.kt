package de.smartmeter.blink

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Controls the phone's camera flashlight (torch) via CameraManager.
 *
 * `CameraManager.setTorchMode` requires the CAMERA permission on many builds
 * (others gate it too), so the app requests it at runtime. Failures are
 * recorded in [lastError] instead of being swallowed.
 */
class FlashlightController(context: Context)
{

    private val appContext = context.applicationContext
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraId: String? = null

    /** Human-readable reason the last torch command failed, or null. */
    var lastError: String? = null
        private set

    val torchAvailable: Boolean by lazy { findFlashCamera() }

    private fun str(resId: Int): String = appContext.getString(resId)

    private fun findFlashCamera(): Boolean
    {
        return try {
            val id = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cameraId = id
            if (id == null) lastError = str(R.string.flash_no_flash)
            id != null
        }
        catch (e: Exception)
        {
            lastError = e.message ?: str(R.string.flash_list_failed)
            false
        }
    }

    /** @return true if the torch state was applied successfully. */
    fun setTorch(enabled: Boolean): Boolean
    {
        if (!torchAvailable) {
            lastError ?: run { lastError = str(R.string.flash_no_flash) }
            return false
        }
        return try {
            cameraManager.setTorchMode(cameraId!!, enabled)
            lastError = null
            true
        }
        catch (e: SecurityException)
        {
            lastError = str(R.string.flash_permission_missing)
            false
        }
        catch (e: CameraAccessException)
        {
            lastError = str(R.string.flash_busy)
            false
        }
        catch (e: Exception)
        {
            lastError = e.message ?: str(R.string.flash_failed)
            false
        }
    }
}