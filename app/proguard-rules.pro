# Views inflated from XML are constructed reflectively.
-keep class com.devbangs.beampad.TrackpadView { *; }

# BiometricPrompt callbacks are invoked from the framework.
-keep class androidx.biometric.** { *; }

# Keep line numbers so crash reports stay readable, but hide the
# original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
