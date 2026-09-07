# --- Retrofit / OkHttp / Gson ---
# Retrofit leest generieke signatures van interface-methodes (Response<T>, List<T>)
# via reflectie; zonder Signature/InnerClasses valt dat terug op raw types.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Retrofit service-interfaces intact houden.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowobfuscation interface <1>
-keep interface com.nexustvguide.app.data.api.GuideApiService { *; }

# Gson vult de DTO's reflectief; velden mogen niet gestript of hernoemd worden.
-keep class com.nexustvguide.app.data.model.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# --- Leanback / program guide ---
# De gids-fragment subclass wordt via layout/reflectie geraakt.
-keep class com.egeniq.androidtvprogramguide.** { *; }
-keep class com.nexustvguide.app.ui.** { *; }

# --- ThreeTenABP ---
-dontwarn org.threeten.bp.**
-keep class org.threeten.bp.zone.** { *; }

# Gson leidt het element-type af uit de generic superclass van anonieme
# TypeToken-subclasses (object : TypeToken<List<ChannelDto>>() {}). De optimizer in
# proguard-android-optimize.txt mag zulke klassen mergen of inlinen; dan verdwijnt
# die superclass-info en faalt Gson met "TypeToken must be created with a type
# argument". -keepattributes Signature alleen is niet genoeg: de klasse zelf moet
# blijven bestaan. Dit zijn de rules die Gson zelf als consumer-rules meelevert.
-keep,allowobfuscation,allowoptimization class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowoptimization class * extends com.google.gson.reflect.TypeToken

# --- Zenderlogo's ---
# ChannelLogoResolver zoekt de drawables op via Resources.getIdentifier(); die
# lookup is onzichtbaar voor R8, dus zonder deze regel schrapt shrinkResources
# alle channel_logo_*.png als ongebruikt.
-keepclassmembers class **.R$drawable {
    public static final int channel_logo_*;
}
