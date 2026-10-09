package com.samuelpart.iptvplayer;

import android.content.Context;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Configuracion global de Glide: RGB_565 (mitad de memoria por poster) y
 * cache de disco amplia. Evita que el catalogo grande se traben o dejen de
 * cargar posters en telefonos de poca RAM.
 */
@com.bumptech.glide.annotation.GlideModule
public class IptvGlideModule extends AppGlideModule {
    @Override
    public void applyOptions(Context context, GlideBuilder builder) {
        builder.setDefaultRequestOptions(
            new com.bumptech.glide.request.RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
