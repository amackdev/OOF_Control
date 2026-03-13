# Keep JNI native methods
-keep class com.oof.control.utils.NativeIoctl { *; }

# Keep SystemProperties reflection
-keep class android.os.SystemProperties { *; }

# Keep data classes used with JSON serialization
-keep class com.oof.control.utils.GameModeProfile { *; }
-keep class com.oof.control.utils.GameAppEntry { *; }
