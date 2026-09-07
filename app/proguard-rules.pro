# Views inflated from XML are constructed reflectively by name.
-keep class com.devbangs.beampad.TrackpadView {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Activities, fragments, the service and the application are kept
# automatically from the manifest, so they need no rules here.

# Keep line numbers so Play crash reports stay readable, but hide the
# original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Ads and billing ship their own consumer rules inside their AARs; R8
# applies those automatically. Adding blanket keeps here would only
# disable shrinking on large libraries for no benefit.
