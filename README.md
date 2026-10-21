# Sade Ana Ekran 2.0

**Xiaomi Mi Box S 4K (2. nesil, Google TV)** için yazılmış, hafif ve şık bir ana ekran (launcher).
Google TV'nin ağır arayüzünün yerine geçer; istediğin an tek tıkla eski arayüze dönebilirsin.
İçinde **yerleşik AirPlay alıcısı** var: iPhone, iPad ve Mac'ten ek uygulama kurmadan ekran yansıtma ve ses.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ (17:39 Sal 20 Eki | ☁ 12° · İstanbul)        (AirPlay ●)(Hız testi)(▣ %66 ●)(⚙) │
│                                                                              │
│ ─ İyi günler                                                    ┌─────┐       │
│ Netflix                                                         │  N  │  ← odaktaki
│ Kaldığın yerden devam et                                        └─────┘    uygulamanın
│                                                                            logosu ve rengi
│                                                                              │
│ Uygulamalar 16                                                               │
│ [Netflix][MUBI][YouTube][TV+][Prime Video][Disney+][Tarayıcı] …              │
│ Netflix                                                                      │
│                                         [◀ ▶] gez  [OK] aç · basılı tut: seçenekler │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Özellikler

| Özellik | Ne yapar |
|---|---|
| **Sinematik ana ekran** | Seçili uygulamanın renginden arka plan, sağda büyük logosu, büyük başlık. Kartlar seçilince yaylanarak büyür, üzerinden ışık geçer. |
| **Saat · tarih · hava durumu** | Üst çubukta. Hava durumu ücretsiz Open‑Meteo'dan, konum IP'den otomatik (ya da Ayarlar'dan şehir seç). |
| **RAM göstergesi** | Anlık RAM doluluğu (yeşil/sarı/kırmızı). |
| **Yerleşik AirPlay** | iPhone / iPad / Mac → Denetim Merkezi → Ekran Yansıtma → TV'nin adı. Görüntü donanım çözücüyle tam ekran, ses dahil; müzik uygulamalarından sadece ses de gönderilebilir. Üst çubuktaki çip: yeşil nokta hazır, pembe nokta bağlı. |
| **Akıcı mod** | Açtığın uygulama (YouTube, TV+, Apple TV…) ayağa kalkınca arka planda kalan diğer uygulamaları kapatır; RAM'in tamamı öndeki uygulamaya kalır. |
| **Uygulamaları optimize et** | Uygulamaları önceden derler (ART speed-profile): daha hızlı açılış, kaydırmada daha az takılma. |
| **TV'yi hızlandır** | Gereksiz ön yüklü uygulamaları **siler değil, kapatır** (geri açılabilir) ve animasyonları 0.5x yapar. |
| **Performans göstergesi** | İsteğe bağlı: köşede kare sayısı ve takılma oranı; akıcılığı ölçmek için. |
| **Hız testi** | Gecikme, indirme ve yükleme hızı (Cloudflare). Sonuçta "4K için yeterli mi" yorumu. |
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
- **Akıcı mod ve "Zorla durdur" veri silmez.** Ayarlar → Uygulamalar → Zorla durdur ile aynıdır. Oturumun, profilin, izleme geçmişin yerinde kalır.
- **"Yetkisiz cihaz" sorunu olmaz.** Netflix/TV+ kutunun Google sertifikasına ve DRM'ine bakar, hangi ana ekranı kullandığına bakmaz. Root yok, sistem değişikliği yok.
- **Tek çekince:** Akıcı mod, optimizasyon ve TV'yi hızlandır için geliştirici seçeneklerinde *USB hata ayıklama* açık kalmalı. Netflix buna bakmaz. Bir uygulama nadiren de olsa sorun çıkarırsa hata ayıklamayı kapat; bu üçü hariç her şey (AirPlay dahil) çalışmaya devam eder.

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
2. ⚙ → **ADB** → **Bağlan**'a bas.
3. TV'de *"Hata ayıklamaya izin verilsin mi?"* penceresi çıkar → **"Bu bilgisayardan her zaman izin ver"**'i işaretle → **İzin ver**.

Bu kadar. Launcher bu adımda kendine üç izin verir ve HOME yönlendirmesini kendisi açar:
- `WRITE_SECURE_SETTINGS`: HOME servisini açabilmek için.
- Kullanım erişimi: "Kaldığın yerden devam et" ve son kullanılana göre sıralama için.
- Üstte gösterme: AirPlay yansıtması başlayınca tam ekran görüntünün başka bir uygulamanın üstünde açılabilmesi için.

> HOME yönlendirmesi kapalı kalırsa: ⚙ → **HOME tuşu yönlendirmesi** → Erişilebilirlik → *Sade Ana Ekran – HOME tuşu* → Aç.

Bilgisayardan elle yapmak istersen aynı adımlar:
```bash
adb shell pm grant com.hizir.sadehome android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.hizir.sadehome GET_USAGE_STATS allow
adb shell appops set com.hizir.sadehome SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services com.hizir.sadehome/com.hizir.sadehome.HomeWatcherService
adb shell settings put secure accessibility_enabled 1
```

---

## Kullanım

| Kumanda | Ne olur |
|---|---|
| ◀ ▶ | Kartlar arasında gez (seçili kart solda sabit kalır, satır kayar) |
| ▲ ▼ | Üst çubuk ↔ Uygulamalar |
| OK | Aç |
| OK basılı tut (ya da Menü tuşu) | Seçenekler menüsü |
| Geri | Paneli kapatır (ana ekranda bir şey yapmaz) |
| HOME | Her yerden bu ana ekrana döner; ana ekrandayken ilk uygulamaya gider |

**Tarayıcıda:** yön tuşları imleci taşır, basılı tutunca hızlanır. OK tıklar, imleci kenara itince sayfa kayar.
Sayfanın en üstünde yukarı basarsan adres çubuğuna çıkarsın. ★ sayfayı açılış ekranına ekler, "Masaüstü/Mobil" site görünümünü değiştirir.
Kanal +/− tuşları sayfa sayfa kaydırır. Geri tuşu önceki sayfaya döner, ilk sayfadaysa tarayıcıyı kapatır.

---

## AirPlay (iPhone / iPad / Mac)

Ayrı bir uygulama gerekmez; alıcı launcher'ın içinde ve TV açıldığında kendiliğinden başlar.

- **Ekran yansıtma:** iPhone/iPad'de Denetim Merkezi → **Ekran Yansıtma** → TV'nin adı. Mac'te menü çubuğundaki Denetim Merkezi → Ekran Yansıtma.
- **Sadece ses:** Müzik/Podcast/Spotify'da AirPlay simgesi → TV.
- **Bitirmek:** iPhone'da "Yansıtmayı Durdur" ya da TV'de **Geri** tuşu.
- **Ad değiştirme / kapatma:** ⚙ → **AirPlay alıcısı** (ya da üst çubuktaki AirPlay çipi).

TV ile Apple cihazı aynı ağda olmalı. Görüntü 1080p/60'a kadar H.264, kutunun donanım çözücüsüyle doğrudan ekrana gider;
launcher'ın çizim hattından geçmez. Yansıtma sırasında Wi‑Fi güç tasarrufu kapatılır, paket gecikmesi azalır.

Teknik: AirPlay 2 "eski eşleştirme" yolu (Ed25519 + X25519 pair-verify, FairPlay oturum anahtarı, AES-CTR görüntü,
AES-CBC ses, AAC-ELD / ALAC). Protokol akışı açık kaynak [UxPlay](https://github.com/FDH2/UxPlay) projesinden referans
alınarak Kotlin'de yazıldı. FairPlay el sıkışması (`airplay/FairPlay.kt`) UxPlay'deki *playfair* kodunun Kotlin portudur
ve **GPL-3.0** lisanslıdır. Uygulama kişisel kullanım içindir; dağıtılacaksa kaynak kodu da GPL-3.0 ile paylaşılmalıdır.

## Akıcılık

⚙ → **Akıcılık ayarları**:

| Ayar | Ne yapar |
|---|---|
| **Akıcı mod** (varsayılan açık) | Bir uygulamayı açtıktan ~4 sn sonra arka planda kalan diğer uygulamaları kapatır. YouTube / TV+ / Apple TV gibi ağır uygulamalar RAM sıkışıklığından doğan takılmalar olmadan çalışır. Açılışı yavaşlatmamak için bekleyerek çalışır. |
| **Uygulamaları optimize et** | Tüm uygulamaları ART ile önceden derler (`cmd package compile -m speed-profile`). Android bunu normalde cihaz "boşta" iken yapar, sürekli açık bir TV kutusunda nadiren olur. Birkaç dakika sürer; uygulama güncellemelerinden sonra tekrarlamak iyidir. |
| **TV'yi hızlandır** | Aşağıya bak. |
| **Performans göstergesi** | Köşede saniyelik kare sayısı, takılma oranı ve en yavaş kare. Ana ekranın akıcılığını cihazda ölçmek için. |

Ana ekranın kendisi de kare başına iş yapmayacak şekilde düzenlendi:
- Pencere ve kök arka plan dolguları kaldırıldı (her karede iki tam ekran boyama eksildi).
- Kartlar `clipPath` yerine donanım dış hat kırpması kullanıyor.
- Odak değişiminde yeniden yerleşim ve bellek ayırma yok.
- Saydamlık animasyonları ara katman açmıyor.
- RAM göstergesi ADB süreci başlatmıyor.
- Ana ekran arka plandayken önbelleğini bırakıyor.

Ayrıca: Ayarlar → Ekran ve Ses → Gelişmiş görüntü → **İçerik kare hızını eşleştir** açıkken TV+ ve Apple TV'deki filmler
(24 kare/sn) sarsıntısız oynar.

## TV'yi hızlandır

⚙ → **Akıcılık ayarları → TV'yi hızlandır**. ADB kurulumu gerekir.

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
| Akıcı mod / optimizasyon "ADB kurulumu" ekranını açıyor | Geliştirici seçeneklerinde USB hata ayıklama açık mı? ⚙ → ADB → Bağlan; TV'deki pencerede "her zaman izin ver"i işaretle. |
| "ADB ağ portu (5555) kapalı" | Kutu yeniden başlayınca kapanmış olabilir. Bilgisayardan: `adb connect <IP>:5555` sonra `adb tcpip 5555`. Ya da USB hata ayıklamayı kapatıp aç. |
| HOME, Google TV'ye gidiyor | ⚙ → HOME tuşu yönlendirmesi. Ayarlar'dan "Zorla durdur" yapılırsa Android servisi kapatır; ADB kuruluysa launcher bir sonraki açılışta servisi kendisi açar. |
| HOME'a uzun basınca Google TV paneli açılmıyor | HOME tuşu yönlendirme servisine gidiyor. Ayarlar için ⚙'yi kullan. |
| Hava durumu görünmüyor | İnternet yoksa gizlenir. ⚙ → Hava durumu → şehri elle seç. |
| iPhone'da TV görünmüyor | TV ve telefon aynı Wi‑Fi'da mı? ⚙ → AirPlay alıcısı açık mı (çipte yeşil nokta)? Misafir ağı / "istemci yalıtımı" açık modemlerde mDNS engellenir. |
| Yansıtma başlıyor ama görüntü gelmiyor | `adb logcat -s SadeAirPlay` çıktısına bak; ADB kurulumunu bir kez yap (üstte gösterme izni için). |

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
│   │   ├── Cleaner.kt          Akıcı mod (force-stop) + korunan paketler listesi
│   │   ├── Debloat.kt          TV'yi hızlandır, uygulama optimizasyonu, animasyon ölçeği
│   │   └── HomeControl.kt      Stok ana ekran, HOME servisini açma/kapama
│   ├── airplay/                Yerleşik AirPlay alıcısı
│   │   ├── AirPlayServer.kt    RTSP sunucusu: /info, pair-setup/verify, fp-setup, SETUP, TEARDOWN… (saf JVM)
│   │   ├── MirrorReceiver.kt   Görüntü akışı (TCP, AES-CTR → H.264 Annex-B)
│   │   ├── AudioReceiver.kt    Ses akışı (UDP/RTP, AES-CBC)
│   │   ├── NtpTiming.kt        Zaman eşitleme
│   │   ├── FairPlay.kt         FairPlay oturum anahtarı (playfair portu, GPL-3.0) + assets/airplay/fairplay.bin
│   │   ├── Ed25519.kt, Curve25519.kt, BPlist.kt, AlacDecoder.kt   Kripto, ikili plist, ALAC çözücü
│   │   ├── VideoDecoder.kt     MediaCodec H.264 (düşük gecikme) + MirrorHub köprüsü
│   │   ├── AudioPlayer.kt      AAC-ELD (MediaCodec) / ALAC → AudioTrack
│   │   ├── AirPlayService.kt   Ön plan servisi, mDNS (NSD) duyurusu, durum
│   │   └── MirrorActivity.kt   Tam ekran yansıtma
│   ├── ui/                     Çip, kart, kaydırmalı satır, arka plan, yan panel, çizilmiş ikonlar
│   └── browser/                Tarayıcı (WebView + kumanda imleci), açılış sayfası
└── res/                        İkon/banner (vektör), tema, erişilebilirlik ayarı
```

Teknik: Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1 · compileSdk 35 · minSdk 26 · targetSdk 34 · bağımlılık yok.

### Geliştirme durumu

Kod Android 14 framework'üne karşı **Kotlin derlemesinden temiz geçti**. ADB istemcisi, gerçek adbd gibi davranan bir test
sunucusuna karşı **uçtan uca test edildi**: anahtar onayı, imza ve shell çıktısı çalışıyor.

AirPlay çekirdeği masaüstü JVM'de test edildi:
- **FairPlay:** UxPlay'in orijinal C koduyla 20 000'i aşkın girdide bayt bayt aynı sonuç verdi.
- **X25519 / Ed25519:** RFC ve Wycheproof test vektörlerinden, Python `cryptography` ile karşılaştırmadan geçti.
- **İkili plist:** `plistlib` ile karşılıklı okuma/yazma ve 220 bini aşkın bozuk girdiyle dayanıklılık (fuzz) testinden geçti.
- **Protokolün tamamı:** sahte bir iPhone istemcisiyle (Python) uçtan uca denendi: eşleştirme, FairPlay, SETUP, şifreli H.264 kareleri, AAC ses paketleri, TEARDOWN.

Henüz yapılmayanlar: **Gradle ile APK derlemesi** (geliştirme ortamında Google'ın Android SDK sunucuları kapalıydı) ve
**gerçek iPhone/Mac ile deneme**. İlk `./gradlew assembleRelease` sırasında çıkabilecek küçük kaynak/manifest hataları
ve cihazdaki ince ayarlar ayrıca kontrol edilmeli.
