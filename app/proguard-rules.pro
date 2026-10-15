# SQLCipher : classes appelées depuis le code natif
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# kotlinx.serialization (format de sauvegarde)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class cd.smartbudget.fc.** {
    *** Companion;
}
-keepclasseswithmembers class cd.smartbudget.fc.** {
    kotlinx.serialization.KSerializer serializer(...);
}
