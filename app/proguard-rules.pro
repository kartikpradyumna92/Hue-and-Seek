# Room: retain entity classes and DAO implementations (instantiated by reflection)
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# Hilt: ViewModels annotated with @HiltViewModel need their @Inject constructor kept
-keepclassmembers @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel {
    @javax.inject.Inject <init>(...);
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.debug.*

# Kotlin metadata (needed for Hilt/KSP reflection)
-keepattributes *Annotation*
-keepattributes Signature
