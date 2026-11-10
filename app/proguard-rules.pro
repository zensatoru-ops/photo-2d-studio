# Keep native bridge methods - JNI resolves by signature, obfuscation breaks that link
-keepclasseswithmembernames class com.photo2model.studio.core.NativeBridge {
    native <methods>;
}
