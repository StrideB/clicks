# ONNX Runtime loads its JNI bindings reflectively.
-keep class ai.onnxruntime.** { *; }

# Billing library models are constructed from Play service JSON.
-keep class com.android.billingclient.** { *; }

# Keep line numbers so release stack traces stay readable.
-keepattributes SourceFile,LineNumberTable

# AI Edge localagents-rag generated protos reference Google-internal protobuf marker
# annotations that don't exist in the public protobuf-javalite artifact. They're compile-time
# metadata only — safe to ignore (R8's own missing_rules.txt suggests exactly these).
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
