# Dependency Update Report

Generated on: Thu Apr  2 18:33:11 UTC 2026

## Latest dependency state
```
Starting a Gradle Daemon (subsequent builds will be faster)

> Configure project :app
Declaring dependencies using multi-string notation has been deprecated. This will fail with an error in Gradle 10. Please use single-string notation instead: "com.android.tools.lint:lint-gradle:31.7.0". Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_9.html#dependency_multi_string_notation
Declaring dependencies using multi-string notation has been deprecated. This will fail with an error in Gradle 10. Please use single-string notation instead: "com.android.tools.build:aapt2:8.7.0-12006047:linux". Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_9.html#dependency_multi_string_notation
Declaring 'crunchPngs' as a property using an 'is-' method with a Boolean type on com.android.build.gradle.internal.dsl.BuildType$AgpDecorated has been deprecated. Starting with Gradle 10, this property will no longer be treated like a property. The combination of method name and return type is not consistent with Java Bean property rules. Add a method named 'getCrunchPngs' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.gradle.internal.dsl.BuildType$AgpDecorated.isCrunchPngs' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_boolean_properties
Declaring 'useProguard' as a property using an 'is-' method with a Boolean type on com.android.build.gradle.internal.dsl.BuildType has been deprecated. Starting with Gradle 10, this property will no longer be treated like a property. The combination of method name and return type is not consistent with Java Bean property rules. Add a method named 'getUseProguard' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.gradle.internal.dsl.BuildType.isUseProguard' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_boolean_properties
w: file:///home/runner/work/Asm/Asm/app/build.gradle.kts:55:5: 'fun BaseAppModuleExtension.kotlinOptions(configure: Action<KotlinJvmOptions>): Unit' is deprecated. The kotlinOptions types are deprecated, please migrate to the compilerOptions types. More details are here: https://kotl.in/u1r8ln.
Declaring 'wearAppUnbundled' as a property using an 'is-' method with a Boolean type on com.android.build.api.variant.impl.ApplicationVariantImpl has been deprecated. Starting with Gradle 10, this property will no longer be treated like a property. The combination of method name and return type is not consistent with Java Bean property rules. Add a method named 'getWearAppUnbundled' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.api.variant.impl.ApplicationVariantImpl.isWearAppUnbundled' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_boolean_properties
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run-1775154719275.json

[Incubating] Problems report is available at: file:///home/runner/work/Asm/Asm/build/reports/problems/problems-report.html
```
