# APK signing tool attribution

The signing ZIP includes the unmodified `apksigner.jar` from Android SDK
Build-Tools 35.0.0. It contains the Android Open Source Project's apksigner,
apksig and Conscrypt Java code. It is not part of the diary application.

The tool is open source. Android SDK terms section 3.5 says that distribution
of open source SDK components is governed by their applicable open source
licenses. The apksig/apksigner source declares Apache License 2.0; see
`LICENSE-apksigner.txt`, reproduced verbatim from the AOSP Android 15 source.
`NOTICE-Android-SDK.txt` is the complete, unmodified SDK Build-Tools 35.0.0
notice file. It preserves the applicable Conscrypt notices and Apache 2.0
license texts, including the Harmony and Netty notices, alongside notices for
other SDK components. Keep both text files with the JAR when redistributing.

## Files and integrity

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `apksigner.jar` | 1,074,241 | `00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7` |
| `NOTICE-Android-SDK.txt` | 1,068,025 | `14ad8a3f18a62e674e865b5e06b5847a6c97848d33f8cdc8e842eaf9bed994c3` |

The notice file contains non-UTF-8 legacy text. Preserve its original bytes.
The source repository retains only the attribution text; release packaging
copies the JAR and SDK notice from an installed Build-Tools 35.0.0 package.

The Linux and Windows Build-Tools 35.0.0 archives contain byte-identical
`apksigner.jar` files. The JAR contains Java 8 class files and no native
libraries. The helper supports a locally installed JDK 17 or later, with
`java` and `keytool` available. Startup was checked with JDK 21. The JAR uses
standard Java security providers when optional Conscrypt native code is
unavailable; no Android Studio installation is required to run it.

## Authoritative sources

- Android SDK terms, section 3.5: https://developer.android.com/studio/terms
- apksig/apksigner license: https://android.googlesource.com/platform/tools/apksig/+/refs/tags/android-15.0.0_r1/LICENSE
- apksigner build definition: https://android.googlesource.com/platform/tools/apksig/+/refs/tags/android-15.0.0_r1/Android.bp
- Conscrypt license: https://android.googlesource.com/platform/external/conscrypt/+/refs/tags/android-15.0.0_r1/LICENSE
- Conscrypt notice: https://android.googlesource.com/platform/external/conscrypt/+/refs/tags/android-15.0.0_r1/NOTICE
- Conscrypt third-party licenses: https://android.googlesource.com/platform/external/conscrypt/+/refs/tags/android-15.0.0_r1/licenses/
- Google SDK package metadata: https://dl.google.com/android/repository/repository2-1.xml
- Original Linux archive: https://dl.google.com/android/repository/build-tools_r35_linux.zip
- Original Windows archive: https://dl.google.com/android/repository/build-tools_r35_windows.zip

Google's package metadata publishes SHA-1 checksums for these original ZIPs:
Linux `2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc` (61,958,799 bytes),
Windows `af059bb67cf7786f45ee0db85e2d24985df1b4b6` (59,878,107 bytes).
The Windows archive was downloaded from the official URL and checked against
its published checksum before comparing its JAR with the installed Linux JAR.
