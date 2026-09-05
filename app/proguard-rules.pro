# Add project-specific ProGuard rules here.
# By default, the rules in this file are appended to the default ProGuard rules.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Glide rules
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}
