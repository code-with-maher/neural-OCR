# Dependency Update Report

Generated on: Thu Apr  2 17:34:03 UTC 2026

## Latest dependency state
```
Starting a Gradle Daemon (subsequent builds will be faster)
Problem found: Multiple scripts (id: scripts:multiple-scripts)
  Multiple build script files were found in directory '/home/runner/work/Asm/Asm'
    Multiple build script files were found in directory '/home/runner/work/Asm/Asm'. Selected 'build.gradle', and ignoring 'build.gradle.kts'.
    Solution: Delete the files 'build.gradle.kts' in directory '/home/runner/work/Asm/Asm'

> Configure project :app
Declaring dependencies using multi-string notation has been deprecated. This will fail with an error in Gradle 10. Please use single-string notation instead: "com.android.tools.lint:lint-gradle:31.8.2". Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_9.html#dependency_multi_string_notation
Declaring dependencies using multi-string notation has been deprecated. This will fail with an error in Gradle 10. Please use single-string notation instead: "com.android.tools.build:aapt2:8.8.2-12006047:linux". Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_9.html#dependency_multi_string_notation
Declaring 'crunchPngs' as a property using an 'is-' method with a Boolean type on com.android.build.gradle.internal.dsl.BuildType$AgpDecorated has been deprecated. Starting with Gradle 10, this property will no longer be treated like a property. The combination of method name and return type is not consistent with Java Bean property rules. Add a method named 'getCrunchPngs' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.gradle.internal.dsl.BuildType$AgpDecorated.isCrunchPngs' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_boolean_properties
Declaring 'useProguard' as a property using an 'is-' method with a Boolean type on com.android.build.gradle.internal.dsl.BuildType has been deprecated. Starting with Gradle 10, this property will no longer be treated like a property. The combination of method name and return type is not consistent with Java Bean property rules. Add a method named 'getUseProguard' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.gradle.internal.dsl.BuildType.isUseProguard' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_boolean_properties
Build file '/home/runner/work/Asm/Asm/app/build.gradle': line 7
Properties should be assigned using the 'propName = value' syntax. Setting a property via the Gradle-generated 'propName value' or 'propName(value)' syntax in Groovy DSL has been deprecated. This is scheduled to be removed in Gradle 10. Use assignment ('namespace = <value>') instead. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_space_assignment_syntax
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:7)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
	at build_7goxc7brcuvnpr6k2p7nhapak.run(/home/runner/work/Asm/Asm/app/build.gradle:6)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
Build file '/home/runner/work/Asm/Asm/app/build.gradle': line 8
Properties should be assigned using the 'propName = value' syntax. Setting a property via the Gradle-generated 'propName value' or 'propName(value)' syntax in Groovy DSL has been deprecated. This is scheduled to be removed in Gradle 10. Use assignment ('compileSdk = <value>') instead. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_space_assignment_syntax
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:8)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
	at build_7goxc7brcuvnpr6k2p7nhapak.run(/home/runner/work/Asm/Asm/app/build.gradle:6)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
Build file '/home/runner/work/Asm/Asm/app/build.gradle': line 12
Properties should be assigned using the 'propName = value' syntax. Setting a property via the Gradle-generated 'propName value' or 'propName(value)' syntax in Groovy DSL has been deprecated. This is scheduled to be removed in Gradle 10. Use assignment ('minSdk = <value>') instead. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_space_assignment_syntax
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1$_closure3.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:12)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:10)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
Build file '/home/runner/work/Asm/Asm/app/build.gradle': line 13
Properties should be assigned using the 'propName = value' syntax. Setting a property via the Gradle-generated 'propName value' or 'propName(value)' syntax in Groovy DSL has been deprecated. This is scheduled to be removed in Gradle 10. Use assignment ('targetSdk = <value>') instead. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_space_assignment_syntax
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1$_closure3.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:13)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:10)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
Build file '/home/runner/work/Asm/Asm/app/build.gradle': line 27
Properties should be assigned using the 'propName = value' syntax. Setting a property via the Gradle-generated 'propName value' or 'propName(value)' syntax in Groovy DSL has been deprecated. This is scheduled to be removed in Gradle 10. Use assignment ('shrinkResources = <value>') instead. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_space_assignment_syntax
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1$_closure4$_closure9.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:27)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1$_closure4.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:24)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
Build file '/home/runner/work/Asm/Asm/app/build.gradle': line 29
Properties should be assigned using the 'propName = value' syntax. Setting a property via the Gradle-generated 'propName value' or 'propName(value)' syntax in Groovy DSL has been deprecated. This is scheduled to be removed in Gradle 10. Use assignment ('signingConfig = <value>') instead. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_space_assignment_syntax
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1$_closure4$_closure9.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:29)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1$_closure4.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:24)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
Build file '/home/runner/work/Asm/Asm/app/build.gradle': line 36
Properties should be assigned using the 'propName = value' syntax. Setting a property via the Gradle-generated 'propName value' or 'propName(value)' syntax in Groovy DSL has been deprecated. This is scheduled to be removed in Gradle 10. Use assignment ('viewBinding = <value>') instead. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_space_assignment_syntax
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1$_closure5.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:36)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
	at build_7goxc7brcuvnpr6k2p7nhapak$_run_closure1.doCall$original(/home/runner/work/Asm/Asm/app/build.gradle:35)
	(Run with --stacktrace to get the full stack trace of this deprecation warning.)
Declaring 'wearAppUnbundled' as a property using an 'is-' method with a Boolean type on com.android.build.api.variant.impl.ApplicationVariantImpl has been deprecated. Starting with Gradle 10, this property will no longer be treated like a property. The combination of method name and return type is not consistent with Java Bean property rules. Add a method named 'getWearAppUnbundled' with the same behavior and mark the old one with @Deprecated, or change the type of 'com.android.build.api.variant.impl.ApplicationVariantImpl.isWearAppUnbundled' (and the setter) to 'boolean'. Consult the upgrading guide for further information: https://docs.gradle.org/9.4.1/userguide/upgrading_version_8.html#groovy_boolean_properties
Problem found: Selection failed (id: task-selection:selection-failed)
  Task 'dependencyUpdates' not found in root project 'Asm' and its subprojects.
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run-1775151232208.json

[Incubating] Problems report is available at: file:///home/runner/work/Asm/Asm/build/reports/problems/problems-report.html
```
