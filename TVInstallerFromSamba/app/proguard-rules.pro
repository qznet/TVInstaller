-keep class jcifs.** { *; }
-dontwarn jcifs.**

# JCIFS requests MD4 from Bouncy Castle by algorithm name during NTLM authentication.
# Keep the provider mapping and implementation classes that are loaded reflectively.
-keep class org.bouncycastle.jcajce.provider.digest.MD4** { *; }

