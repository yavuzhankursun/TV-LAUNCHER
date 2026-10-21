# Sade Ana Ekran 2.0

**Xiaomi Mi Box S 4K (2. nesil, Google TV)** için yazılmış, hafif ve şık bir ana ekran (launcher).
Google TV'nin ağır arayüzünün yerine geçer; istediğin an tek tıkla eski arayüze dönebilirsin.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ (17:39 Sal 20 Eki | ☁ 12° · İstanbul)     (AirPlay ●)(Hız testi)(▣ %66 ● ⚡Boşalt)(⚙) │
│                                                                              │
│ ─ İyi günler                                                    ┌─────┐       │
│ Netflix                                                         │  N  │  ← odaktaki
│ Kaldığın yerden devam et                                        └─────┘    uygulamanın
│ (▶ OK ile aç) (ⓘ Uygulama bilgisi)                                         logosu ve rengi
│                                                                              │
│ Uygulamalar 16                                                               │
│ [Netflix][MUBI][YouTube][TV+][Prime Video][Disney+][Tarayıcı] …              │
│ Netflix · OK ile aç                                                          │
│ Hızlı ayarlar                                                                │
│ (⚡TV'yi hızlandır)(Wi‑Fi)(Bluetooth)(Görüntü)(Ses)(Depolama) …               │
│                                         [◀ ▶] gez  [OK] aç · basılı tut: seçenekler │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Özellikler

| Özellik | Ne yapar |
|---|---|
| **Sinematik ana ekran** | Seçili uygulamanın renginden arka plan, sağda büyük logosu, büyük başlık. Kartlar seçilince yaylanarak büyür, üzerinden ışık geçer. |
| **Saat · tarih · hava durumu** | Üst çubukta. Hava durumu ücretsiz Open‑Meteo'dan, konum IP'den otomatik (ya da Ayarlar'dan şehir seç). |
| **RAM göstergesi + ⚡ Boşalt** | Anlık RAM doluluğu (yeşil/sarı/kırmızı). **Boşalt** arka planda açık kalan uygulamaları kapatır: ör. `%89 → %67`. |
| **TV'yi hızlandır** | Gereksiz ön yüklü uygulamaları **siler değil, kapatır** (geri açılabilir) ve animasyonları 0.5x yapar. |
| **AirPlay çipi** | AirScreen / AirPin gibi bir AirPlay alıcısı kuruluysa üstte görünür; nokta yeşilse alıcı çalışıyor, iPhone/Mac'ten yansıtmaya hazır. |
| **Hız testi** | Gecikme, indirme ve yükleme hızı (Cloudflare). Sonuçta "4K için yeterli mi" yorumu. |
| **Hızlı ayarlar satırı** | Wi‑Fi, Bluetooth, Görüntü, Ses, Uygulamalar, Depolama, Ekran koruyucu, Tarih/saat, Geliştirici… menülerde aramadan tek tık. |
| **Tarayıcı** | Google TV'de olmayan internet tarayıcısı. Kumandanın yön tuşları **fare imleci** olur, OK tıklar, kenara itince sayfa kayar. Güncel siteler açılır (sistem WebView'i). Kapatınca belleği tamamen geri verir. |
| **Uzun basma menüsü** | Kartta OK'e basılı tut: Aç · Başa/sola/sağa taşı · Gizle · Zorla durdur · Uygulama bilgisi · Kaldır. |
| **HOME tuşu** | Google TV HOME'u kilitliyor; küçük bir erişilebilirlik servisi HOME'u bu ekrana yönlendirir. Ekran içeriğini **okumaz**. |
| **Eski arayüze dönüş** | ⚙ → "Google TV arayüzüne dön". Geri gelmek: Google TV → Uygulamalar → Sade Ana Ekran → ⚙ → "Bu ana ekranı kullan". |

Bilinçli olarak **androidx / Jetpack Compose kullanılmadı**: sadece Android'in kendi bileşenleri. 2 GB RAM'li kutuda
uygulama hafif kalıyor, APK küçük.

---

## Sık sorulan: Netflix / Turkcell TV+ etkilenir mi?

- **Güncellemeler devam eder.** Launcher sadece uygulamaları listeleyip açar. Güncellemeleri Play Store arka planda yapar.
- **Hiçbir uygulama kendiliğinden silinmez.** Silme sadece sen uzun basma menüsünden **Kaldır**'ı seçip onaylarsan olur.
- **"Boşalt" ve "Zorla durdur" veri silmez.** Ayarlar → Uygulamalar → Zorla durdur ile aynıdır. Oturumun, profilin, izleme geçmişin yerinde kalır.
- **"Yetkisiz cihaz" sorunu olmaz.** Netflix/TV+ kutunun Google sertifikasına ve DRM'ine bakar, hangi ana ekranı kullandığına bakmaz. Root yok, sistem değişikliği yok.
- **Tek çekince:** Boşalt için geliştirici seçeneklerinde *USB hata ayıklama* açık kalmalı. Netflix buna bakmaz. Bir uygulama nadiren de olsa sorun çıkarırsa hata ayıklamayı kapat; Boşalt hariç her şey çalışmaya devam eder.

---

## Kurulum

### 1. Gerekenler (bilgisayarda)

- **Android Studio** (en kolayı) ya da: JDK 17+ ve Android SDK (platform 35, build‑tools 35)
- `adb` (Android SDK Platform‑Tools)
- Kutu ve bilgisayar aynı ağda

`local.properties` dosyası yoksa oluştur (Android Studio kendisi oluşturur):
```
sdk.dir=/Users/KULLANICI/Library/Android/sdk
```

### 2. Kutuda geliştirici modunu aç

1. **Ayarlar → Sistem → Hakkında → "Android TV OS derlemesi"** satırına 7 kez bas.
2. **Ayarlar → Sistem → Geliştirici seçenekleri → USB hata ayıklama: Açık**.
3. Kutunun IP adresi: **Ayarlar → Ağ ve İnternet → (Wi‑Fi/Ethernet)**.

### 3. Derle ve yükle

```bash
./gradlew assembleRelease
adb connect 192.168.1.136:5555          # kutunun IP'si
adb install -r app/build/outputs/apk/release/app-release.apk
```

Paket adı eski sürümle aynı (`com.hizir.sadehome`). Aynı bilgisayardan derlendiyse eskisinin üzerine kurulur.
"İmza uyuşmuyor" hatası alırsan önce `adb uninstall com.hizir.sadehome` çalıştır.

### 4. İlk açılış (bir kez)

1. Google TV → **Uygulamalar → Sade Ana Ekran**'ı aç.
2. **Boşalt**'a (ya da ⚙ → ADB) bas → **Bağlan**.
3. TV'de *"Hata ayıklamaya izin verilsin mi?"* penceresi çıkar → **"Bu bilgisayardan her zaman izin ver"**'i işaretle → **İzin ver**.

Bu kadar. Launcher bu adımda kendine iki izin verir ve HOME yönlendirmesini kendisi açar:
- `WRITE_SECURE_SETTINGS`: HOME servisini açabilmek için.
- Kullanım erişimi: "Kaldığın yerden devam et" ve son kullanılana göre sıralama için.

> HOME yönlendirmesi kapalı kalırsa: ⚙ → **HOME tuşu yönlendirmesi** → Erişilebilirlik → *Sade Ana Ekran – HOME tuşu* → Aç.

Bilgisayardan elle yapmak istersen aynı adımlar:
```bash
adb shell pm grant com.hizir.sadehome android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.hizir.sadehome GET_USAGE_STATS allow
adb shell settings put secure enabled_accessibility_services com.hizir.sadehome/com.hizir.sadehome.HomeWatcherService
adb shell settings put secure accessibility_enabled 1
```

---

## Kullanım

| Kumanda | Ne olur |
|---|---|
| ◀ ▶ | Kartlar arasında gez (seçili kart solda sabit kalır, satır kayar) |
| ▲ ▼ | Üst çubuk ↔ "OK ile aç" düğmeleri ↔ Uygulamalar ↔ Hızlı ayarlar |
| OK | Aç |
| OK basılı tut (ya da Menü tuşu) | Seçenekler menüsü |
| Geri | Paneli kapatır (ana ekranda bir şey yapmaz) |
| HOME | Her yerden bu ana ekrana döner; ana ekrandayken ilk uygulamaya gider |

**Tarayıcıda:** yön tuşları imleci taşır, basılı tutunca hızlanır. OK tıklar, imleci kenara itince sayfa kayar.
Sayfanın en üstünde yukarı basarsan adres çubuğuna çıkarsın. ★ sayfayı açılış ekranına ekler, "Masaüstü/Mobil" site görünümünü değiştirir.
Kanal +/− tuşları sayfa sayfa kaydırır. Geri tuşu önceki sayfaya döner, ilk sayfadaysa tarayıcıyı kapatır.

---

## TV'yi hızlandır

⚙ → **TV'yi hızlandır** (ya da Hızlı ayarlar satırında ⚡). ADB kurulumu gerekir.

Uygulamalar **silinmez**, `pm disable-user` ile **kapatılır**: bellekte yer tutmaz, açılışta çalışmaz,
listede görünmez. Her biri tek tıkla, ya da **"Hepsini geri aç"** ile geri gelir. Fabrika ayarına dönüş de her şeyi geri getirir.

| Önerilen (güvenle kapatılır) | Kullanmıyorsan kapat |
|---|---|
| Xiaomi Galeri, Xiaomi Media Player | YouTube Kids, YouTube Music |
| Google Geri Bildirim, Play Oyunlar | Miracast (Android telefondan yansıtma) |
| Yazdırma hizmeti, eski ekran koruyucular | Ambient mod (Google fotoğraf koruyucusu) |
| TalkBack (ekran okuyucu) | Google TV öneri şeritleri |

**Asla dokunulmayanlar:** Netflix, TV+ ve tüm yayın uygulamaları, Play Store, Google Play Hizmetleri,
Google Asistan / sesli arama, Chromecast, klavye, kumanda servisi, Google TV ana ekranı (eski arayüze dönüş için).

Ayrıca **Animasyonlar → 0.5x**: menüler ve uygulama açılışları gözle görülür şekilde daha seri.

---

## Sorun giderme

| Sorun | Çözüm |
|---|---|
| Boşalt "ADB kurulumu" ekranını açıyor | Geliştirici seçeneklerinde USB hata ayıklama açık mı? ⚙ → ADB → Bağlan; TV'deki pencerede "her zaman izin ver"i işaretle. |
| "ADB ağ portu (5555) kapalı" | Kutu yeniden başlayınca kapanmış olabilir. Bilgisayardan: `adb connect <IP>:5555` sonra `adb tcpip 5555`. Ya da USB hata ayıklamayı kapatıp aç. |
| HOME, Google TV'ye gidiyor | ⚙ → HOME tuşu yönlendirmesi. Ayarlar'dan "Zorla durdur" yapılırsa Android servisi kapatır; ADB kuruluysa launcher bir sonraki açılışta servisi kendisi açar. |
| HOME'a uzun basınca Google TV paneli açılmıyor | HOME tuşu yönlendirme servisine gidiyor. Ayarlar için ⚙ ya da Hızlı ayarlar satırını kullan. |
| Hava durumu görünmüyor | İnternet yoksa gizlenir. ⚙ → Hava durumu → şehri elle seç. |
| AirPlay çipi yok | AirPlay alıcısı kurulu değil. Play Store'dan **AirScreen** kurulunca çip kendiliğinden çıkar. |

## Tamamen kaldırma

```bash
adb uninstall com.hizir.sadehome
```
HOME yönlendirmesi ve izinler kendiliğinden sıfırlanır; Google TV olduğu gibi geri gelir.
"TV'yi hızlandır" ile kapattıkların kapalı kalır. Kaldırmadan önce **Hepsini geri aç**'a bas, ya da bilgisayardan geri aç:
```bash
adb shell pm enable <paket.adi>
```

---

## Proje yapısı

```
app/src/main/
├── AndroidManifest.xml
├── java/com/hizir/sadehome/
│   ├── LauncherActivity.kt     Ana ekran: düzen, odak, paneller (ayarlar, menü, hız testi, ADB, hızlandır)
│   ├── HomeActivity.kt         HOME'un görünmez yönlendiricisi (yeni / stok arayüz)
│   ├── HomeWatcherService.kt   Erişilebilirlik servisi: Google TV'nin HOME kilidini aşar
│   ├── adb/
│   │   ├── AdbProtocol.kt      Bağımlılıksız ADB istemcisi (RSA anahtar, AUTH, shell)
│   │   └── Adb.kt              Tek kalıcı bağlantı, durum, ilk kurulumda izinleri verme
│   ├── data/
│   │   ├── AppRepository.kt    Uygulama listesi, ikondan renk, son kullanım, hero logosu
│   │   ├── Prefs.kt            Tüm ayarlar
│   │   ├── Weather.kt          Open-Meteo hava durumu + şehir arama
│   │   ├── SpeedTest.kt        Cloudflare hız testi
│   │   └── Memory.kt, Net.kt, Bg.kt
│   ├── system/
│   │   ├── Cleaner.kt          Boşalt (force-stop) + korunan paketler listesi
│   │   ├── Debloat.kt          TV'yi hızlandır: güvenli kapatma listesi, animasyon ölçeği
│   │   ├── HomeControl.kt      Stok ana ekran, HOME servisini açma/kapama
│   │   └── AirPlay.kt          AirPlay alıcısını bulma
│   ├── ui/                     Çip, kart, kaydırmalı satır, arka plan, yan panel, çizilmiş ikonlar
│   └── browser/                Tarayıcı (WebView + kumanda imleci), açılış sayfası
└── res/                        İkon/banner (vektör), tema, erişilebilirlik ayarı
```

Teknik: Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1 · compileSdk 35 · minSdk 26 · targetSdk 34 · bağımlılık yok.

### Geliştirme durumu

Kod Android 14 framework'üne karşı **Kotlin derlemesinden temiz geçti**. ADB istemcisi, gerçek adbd gibi davranan bir test
sunucusuna karşı **uçtan uca test edildi**: anahtar onayı, imza ve shell çıktısı çalışıyor.
Henüz yapılmayanlar: **Gradle ile APK derlemesi** (geliştirme ortamında Google'ın Android SDK sunucuları kapalıydı) ve
**gerçek kutuda deneme**. İlk `./gradlew assembleRelease` sırasında çıkabilecek küçük kaynak/manifest hataları
ve cihazdaki ince ayarlar ayrıca kontrol edilmeli.
