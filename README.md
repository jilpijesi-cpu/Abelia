# Mythos (Abelia) — source Android

Kode inti dapat dibuka langsung di GitHub:

- [Source agent](https://github.com/jilpijesi-cpu/Abelia/tree/main/app/src/main/java/com/mythos/app/agent)
- [Source UI Compose](https://github.com/jilpijesi-cpu/Abelia/tree/main/app/src/main/java/com/mythos/app/ui)
- [Unit tests agent](https://github.com/jilpijesi-cpu/Abelia/tree/main/app/src/test/java/com/mythos/app/agent)
- [Unit tests UI dan Typography](https://github.com/jilpijesi-cpu/Abelia/tree/main/app/src/test/java/com/mythos/app/ui)

Untuk **seluruh source proyek**—termasuk konfigurasi Gradle, resource, dokumentasi, dan file lain—unduh [ABELIA-Mythos-full-source.zip](https://github.com/jilpijesi-cpu/Abelia/raw/refs/heads/main/ABELIA-Mythos-full-source.zip).

## Build

Ekstrak ZIP, masuk ke folder proyek, lalu jalankan:

```bash
./gradlew :app:testDebugUnitTest assembleDebug
```

APK debug dibuat di `app/build/outputs/apk/debug/`.
