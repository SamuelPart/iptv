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

# ── LUMEN: protecciones para R8 ──────────────────────────────────────
# LibVLC: los metodos nativos JNI se llaman por nombre desde C — si R8
# los renombra, el video no abre.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }

# Puente JS del reproductor web (BOT): addJavascriptInterface llama a los
# metodos ANOTADOS por nombre desde JavaScript — no se pueden ofuscar.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Modelos Serializable que viajan entre Activities via Intent extras.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
}
-keep class com.samuelpart.iptvplayer.CineMedia { *; }
-keep class com.samuelpart.iptvplayer.Channel { *; }
-keep class com.samuelpart.iptvplayer.Episode { *; }
-keep class com.samuelpart.iptvplayer.CastMember { *; }

# Enumeraciones accedidas por valueOf en el parser/enrutador.
-keepclassmembers enum com.samuelpart.iptvplayer.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
