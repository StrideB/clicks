# ONNX Runtime loads its JNI bindings reflectively.
-keep class ai.onnxruntime.** { *; }

# Billing library models are constructed from Play service JSON.
-keep class com.android.billingclient.** { *; }

# Keep line numbers so release stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
