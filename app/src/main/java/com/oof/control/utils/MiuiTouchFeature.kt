package com.oof.control.utils

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Wrapper for MIUI ITouchFeature AIDL interface
 * Based on vendor.xiaomi.hw.touchfeature.ITouchFeature
 */
object MiuiTouchFeature {
    
    private const val TAG = "MiuiTouchFeature"
    private const val DESCRIPTOR = "vendor.xiaomi.hw.touchfeature.ITouchFeature"
    private const val SERVICE_NAME = "vendor.xiaomi.hw.touchfeature.ITouchFeature/default"
    
    // Transaction codes from ITouchFeature.smali
    private const val TRANSACTION_GET_MODE_CUR_VALUE = 1
    private const val TRANSACTION_GET_MODE_DEF_VALUE = 2
    private const val TRANSACTION_GET_MODE_MAX_VALUE = 3
    private const val TRANSACTION_GET_MODE_MIN_VALUE = 4
    private const val TRANSACTION_GET_MODE_VALUES = 5
    private const val TRANSACTION_RESET_MODE = 7
    private const val TRANSACTION_SET_MODE_VALUE = 9
    private const val TRANSACTION_GET_MODE_CUR_VALUE_STRING = 10
    private const val TRANSACTION_GET_MODE_WHITELIST = 11
    
    // Touch modes from ITouchFeature.smali
    const val TOUCH_GAME_MODE = 0
    const val TOUCH_ACTIVE_MODE = 1
    const val TOUCH_UP_THRESHOLD = 2
    const val TOUCH_TOLERANCE = 3
    const val TOUCH_WGH_MIN = 4
    const val TOUCH_WGH_MAX = 5
    const val TOUCH_WGH_STEP = 6
    const val TOUCH_EDGE_FILTER = 7
    const val TOUCH_MODE_DIRECTION = 8
    const val TOUCH_DOUBLETAP_MODE = 14
    const val TOUCH_EDGE_MODE = 15
    const val TOUCH_DEBUG_LEVEL = 18
    const val TOUCH_STYLUS_MODE = 20
    const val TOUCH_PERFORMANCE_MODE = 21
    const val TOUCH_STYLUS_HOPPING_MODE = 22
    const val TOUCH_PASSIVE_PEN_MODE = 23
    const val TOUCH_STYLUS_QUICK_NOTE_MODE = 24
    const val TOUCH_STYLUS_SLEEP_STATE = 29
    const val TOUCH_DISPLAY_ID_STATE = 30
    const val TOUCH_SINGLETAP_MODE = 34
    const val TOUCH_TP_EDGE_MODE = 25
    
    // Touch IDs
    const val TOUCH_ID_PRIMARY = 0
    const val TOUCH_ID_SECONDARY = 1
    
    private var service: IBinder? = null
    private var serviceVersion = 0
 
 	private fun getService(name: String): IBinder? {
		return try {
			val smClass = Class.forName("android.os.ServiceManager")
			val getServiceMethod = smClass.getDeclaredMethod("getService", String::class.java)
			getServiceMethod.invoke(null, name) as IBinder?
		} catch (e: Throwable) {
			Log.e(TAG, "ServiceManager reflection failed", e)
			null
		}
	}

    init {
        try {
            service = getService(SERVICE_NAME)
            if (service != null) {
                serviceVersion = 3 // AIDL v3
                Log.i(TAG, "MIUI Touch Feature service found (AIDL v3)")
            } else {
                Log.w(TAG, "MIUI Touch Feature service not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get MIUI Touch Feature service", e)
        }
    }
    
    /**
     * Check if the service is available
     */
    fun isAvailable(): Boolean = service != null
    
    /**
     * Get current value of a touch mode
     */
    fun getModeValue(touchId: Int, mode: Int): Int {
        val binder = service ?: return -1
        
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(touchId)
            data.writeInt(mode)
            
            binder.transact(TRANSACTION_GET_MODE_CUR_VALUE, data, reply, 0)
            reply.readException()
            
            val value = reply.readInt()
            Log.d(TAG, "getModeValue($touchId, $mode) = $value")
            return value
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mode value", e)
            return -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
    
    /**
     * Set value of a touch mode
     */
    fun setModeValue(touchId: Int, mode: Int, value: Int): Boolean {
        val binder = service ?: return false
        
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(touchId)
            data.writeInt(mode)
            data.writeInt(value)
            
            binder.transact(TRANSACTION_SET_MODE_VALUE, data, reply, 0)
            reply.readException()
            
            val result = reply.readInt()
            val success = result == 0
            Log.d(TAG, "setModeValue($touchId, $mode, $value) = $success")
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mode value", e)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
    
    /**
     * Reset a touch mode to default
     */
    fun resetMode(touchId: Int, mode: Int): Boolean {
        val binder = service ?: return false
        
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(touchId)
            data.writeInt(mode)
            
            binder.transact(TRANSACTION_RESET_MODE, data, reply, 0)
            reply.readException()
            
            val result = reply.readInt()
            return result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset mode", e)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
    
    /**
     * Get default value of a touch mode
     */
    fun getModeDefaultValue(touchId: Int, mode: Int): Int {
        val binder = service ?: return -1
        
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(touchId)
            data.writeInt(mode)
            
            binder.transact(TRANSACTION_GET_MODE_DEF_VALUE, data, reply, 0)
            reply.readException()
            
            return reply.readInt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mode default value", e)
            return -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
    
    /**
     * Get max value of a touch mode
     */
    fun getModeMaxValue(touchId: Int, mode: Int): Int {
        val binder = service ?: return -1
        
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(touchId)
            data.writeInt(mode)
            
            binder.transact(TRANSACTION_GET_MODE_MAX_VALUE, data, reply, 0)
            reply.readException()
            
            return reply.readInt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mode max value", e)
            return -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
    
    /**
     * Get min value of a touch mode
     */
    fun getModeMinValue(touchId: Int, mode: Int): Int {
        val binder = service ?: return -1
        
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(touchId)
            data.writeInt(mode)
            
            binder.transact(TRANSACTION_GET_MODE_MIN_VALUE, data, reply, 0)
            reply.readException()
            
            return reply.readInt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mode min value", e)
            return -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
