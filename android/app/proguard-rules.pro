# Application classes are also called from app_process, companion IPC and the independent
# receiver instrumentation. Keep their ABI while shrinking the third-party implementations.
-keep class com.hsd.universal.** { *; }
-keep class net.prototux.aare.** { *; }
-keep class io.github.shaonialife.cardeck.** { *; }
# The separately installed, publisher-signed receiver shares these on-device ABIs.
-keep class kotlin.** { *; }
-keep class aap_protobuf.** { *; }
-keep class com.google.protobuf.** { *; }
-dontobfuscate
