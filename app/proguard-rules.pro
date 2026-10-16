# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers,allowshrinking class kotlinx.serialization.KSerializer {
    *;
}
-keepclassmembers class com.jishi.monitor.data.model.** { *; }
