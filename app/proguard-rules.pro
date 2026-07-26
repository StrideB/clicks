# ONNX Runtime loads its JNI bindings reflectively.
-keep class ai.onnxruntime.** { *; }

# Billing library models are constructed from Play service JSON.
-keep class com.android.billingclient.** { *; }

# JNI binds native methods BY NAME, so renaming them silently breaks the binding at call time
# rather than at build time. The vendored llama.cpp bridge (llama_jni.cpp) and any other native
# entry point depend on this.
-keepclasseswithmembernames class * { native <methods>; }

# ML Kit GenAI (proofreading / prompt / rewriting) is loaded through AICore out-of-process and its
# request/response types are constructed reflectively, so shrinking them leaves nulls behind
# instead of a link error.
-keep class com.google.mlkit.genai.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai.** { *; }

# MediaPipe tasks-genai likewise resolves its graph/option types by reflection.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep line numbers so release stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
# Kotlin nullability/metadata annotations inform R8's own null analysis; dropping them is what lets
# an incorrect non-null assumption survive into generated code.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class kotlin.Metadata { *; }

# AI Edge localagents-rag generated protos reference Google-internal protobuf marker
# annotations that don't exist in the public protobuf-javalite artifact. They're compile-time
# metadata only — safe to ignore (R8's own missing_rules.txt suggests exactly these).
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
