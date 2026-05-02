# ⏪ Rewind Mod — Minecraft 1.21.11 Fabric

Mod Fabric untuk Minecraft **1.21.11** yang memungkinkan pemain memutar balik seluruh dunia 5 detik ke belakang!

---

## ✨ Fitur

| Fitur | Detail |
|-------|--------|
| **Rewind 5 Detik** | Seluruh dunia — player, mob, entity — mundur 5 detik |
| **Multiplayer Support** | Semua pemain yang online ikut ter-rewind |
| **Cooldown 10 Detik** | Tombol rewind punya cooldown agar tidak disalahgunakan |
| **HUD Indicator** | Kotak indikator cooldown di sebelah hotbar |
| **Tombol `<` (koma)** | Tekan `,` untuk rewind (bisa diubah di Controls) |

---

## 🎮 Cara Pakai

1. Tekan tombol **`,`** (koma / tombol `<`) untuk memicu rewind
2. Seluruh world akan mundur **5 detik** ke belakang
3. Semua pemain, mob, dan entity yang ada dalam radius 128 blok dari pemain akan ter-restore
4. Setelah rewind, ada **cooldown 10 detik** — lihat indikator di HUD
5. Di multiplayer, **semua pemain** bisa memicu rewind, dan **semua pemain** akan terdampak

---

## 📦 Instalasi

1. Download dan install [Fabric Loader 0.18.1+](https://fabricmc.net/use/installer/)
2. Download [Fabric API untuk 1.21.11](https://modrinth.com/mod/fabric-api)
3. Download file `.jar` mod ini dari halaman Releases
4. Letakkan `fabric-api-*.jar` dan `rewind-mod-*.jar` ke folder `mods/`
5. Jalankan Minecraft 1.21.11!

---

## 🔨 Build dari Source

```bash
git clone https://github.com/yourusername/rewind-mod.git
cd rewind-mod
./gradlew build
# Output: build/libs/rewind-mod-1.0.0.jar
```

Butuh **Java 21** dan Gradle 8.14+.

---

## ⚙️ Kompatibilitas

- **Minecraft**: 1.21.11
- **Fabric Loader**: 0.18.1+
- **Fabric API**: 0.139.5+1.21.11
- **Java**: 21+
- **Environment**: Client + Server (required on both for multiplayer)

---

## 🛠️ Cara Kerja (Teknis)

Setiap tick server (20x per detik), mod merekam **snapshot** dari:
- Posisi, health, inventory, XP semua pemain
- Posisi & state semua entity (mob, item, dsb) dalam radius 128 blok

Snapshot disimpan selama 6 detik (120 snapshots).

Saat rewind dipicu:
1. Snapshot 5 detik yang lalu diambil
2. Semua pemain diteleport ke posisi lama mereka (health, inventory, XP di-restore)
3. Entity yang ada di snapshot tapi tidak ada sekarang di-spawn ulang
4. Entity yang tidak ada di snapshot tapi ada sekarang di-hapus
5. Semua pemain mendapat cooldown 10 detik

---

## 📝 Lisensi

MIT License — bebas digunakan dan dimodifikasi.
