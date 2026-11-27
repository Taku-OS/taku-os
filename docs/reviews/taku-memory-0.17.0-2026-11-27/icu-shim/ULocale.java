package android.icu.util;
/** JVM stand-in for Android's ICU: delegates to ICU4J. */
public final class ULocale{public static final ULocale ROOT=new ULocale(com.ibm.icu.util.ULocale.ROOT);final com.ibm.icu.util.ULocale u;ULocale(com.ibm.icu.util.ULocale u){this.u=u;}public com.ibm.icu.util.ULocale icu(){return u;}}
