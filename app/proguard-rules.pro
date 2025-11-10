# Don't obfuscate code
-dontobfuscate

# (Opzionale ma consigliato) mantieni annotazioni e generics per riflessione/JSON
-keepattributes *Annotation*, Signature

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# KeyMapper - keep all VK_* fields for reflection
-keep class com.limelight.utils.KeyMapper {*;}

# KeyConfigHelper - keep classes and fields for Gson
-keep class com.limelight.utils.KeyConfigHelper {*;}
-keep class com.limelight.utils.KeyConfigHelper$ShortcutFile {*;}
-keep class com.limelight.utils.KeyConfigHelper$Shortcut {*;}

# Keep TensorFlow Lite GPU delegate classes that R8 might incorrectly remove
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.opencv.** { *; }

# Profiles
-keep class com.limelight.profiles.ProfilesManager$ProfilesData {*;}
-keep class com.limelight.profiles.SettingsProfile {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**
-keep class com.limelight.binding.input.InputSender
# Keep CpuAffinity (public wrappers called via reflection in places)
-keep class com.limelight.utils.CpuAffinity { *; }
# --- Moonlight: keep thread/affinity helpers & RX boost (used via reflection/JNI) ---
-keep class com.limelight.utils.CpuAffinity { *; }
#
-keep class com.limelight.perf.CpuWarmUp { *; }
#

# FSR renderer (auto-hint)
-keep class com.limelight.render.GlUpscaleRenderer { *; }

# FSR per-device sizing helpers (TextureView/SurfaceView + installer)
-keep class com.limelight.utils.DisplaySizer { *; }
-keep class com.limelight.utils.TextureViewSizer { *; }
-keep class com.limelight.utils.SurfaceViewSizer { *; }
-keep class com.limelight.utils.FSRSizerInstaller { *; }
-keep class com.limelight.utils.FSRSizerInstaller$AutoCloser { *; }
