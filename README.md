# Mythos (Abelia) — source Android

Kode inti dapat dibuka langsung di GitHub:

- [Source agent](app/src/main/java/com/mythos/app/agent/)
- [Source UI Compose](app/src/main/java/com/mythos/app/ui/)
- [Unit tests agent](app/src/test/java/com/mythos/app/agent/)
- [Unit tests UI dan Typography](app/src/test/java/com/mythos/app/ui/)

Untuk **seluruh source proyek**—termasuk konfigurasi Gradle, resource, dokumentasi, dan file lain—unduh [ABELIA-Mythos-full-source.zip](ABELIA-Mythos-full-source.zip) dari halaman repo.

## Build

Ekstrak ZIP, masuk ke folder proyek, lalu jalankan:

```bash
./gradlew :app:testDebugUnitTest assembleDebug
```

APK debug dibuat di `app/build/outputs/apk/debug/`.
