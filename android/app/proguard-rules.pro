# kotlinx.serialization keeps its own generated serializers via consumer rules.
-keepattributes *Annotation*, InnerClasses
-dontwarn org.slf4j.**

# ML Kit face detection (liveness camera): R8 renamed or stripped parts of it and the release build crashed with a
# NullPointerException when the camera screen opened. Keep the library's classes as shipped.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face_bundled.** { *; }
-dontwarn com.google.mlkit.**
