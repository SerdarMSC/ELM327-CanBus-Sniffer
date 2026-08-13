# CAN Monitor — ELM327 Bluetooth CAN Logger

Tek bir CAN ID'sini dinleyip CSV'ye kaydeden, sonra dosyayı WhatsApp vb. ile paylaştıran
minimal Android uygulaması.

## Kurulum

1. Android Studio → **Open** → `CanMonitor` klasörünü seç.
2. Gradle sync (ilk açılışta kendisi ister).
3. ELM327 cihazını **önce telefon Bluetooth ayarlarından eşleştir** (PIN genelde `1234` veya `0000`).
   Uygulama tarama yapmaz, sadece eşleşmiş cihazları listeler — bu bilinçli bir tercih,
   ELM327 klonlarının çoğu eşleşmeden bağlanamıyor.
4. Run.

## Kullanım akışı

| Adım | Ne olur |
|---|---|
| Protokol seç | Varsayılan `6` = ISO 15765-4 CAN 11bit 500K |
| CIHAZA BAĞLAN | SPP soketi açılır, `ATZ ATE0 ATL0 ATS1 ATH1 ATCAF0 ATSP6` gönderilir |
| Filter ID | Örn. `250`. Boş bırakılırsa **tüm** trafik dinlenir |
| START LOG | `ATCRA 250` + `ATMA` → akış başlar, CSV yazılır |
| STOP | Akış kesilir, dosya kapatılır |
| PAYLAŞ | Android paylaşım menüsü → WhatsApp / Drive / Mail |

## CSV formatı

Ayraç `;` (Türkçe Excel çift tıklamayla doğru açar), UTF-8 + BOM.

```
zaman;gecen_ms;can_id;dlc;veri_hex;b0;b1;b2;b3;b4;b5;b6;b7
2026-08-13 14:22:01.245;0;250;3;11 34 A2;11;34;A2;;;;;
```

Dosyalar: `Android/data/com.example.canmonitor/files/logs/can_250_20260813_142201.csv`

## Bilmen gereken teknik detaylar

**Filtreleme donanımda yapılıyor.** `ATCRA <id>` komutu ELM327'nin kendi alıcı filtresini
kurar; istenmeyen frame'ler Bluetooth hattına hiç çıkmaz. Bu önemli — yazılımda filtrelemek
115200 baud'luk seri hattı boğar ve `BUFFER FULL` alırsın.

**`ATCAF0` şart.** Auto-formatting açıkken ELM327 ISO-TP mesajlarını birleştirmeye çalışır
ve ham frame'leri göremezsin.

**Bluetooth SPP'nin çıplak tavanı ~1000 frame/s civarı.** 500 kbps'lik dolu bir CAN hattında
tek ID genelde 10–100 Hz aralığındadır, sorun çıkmaz. Ama `BUFFER FULL` görürsen adapter
yetişemiyor demektir — o durumda ELM327 v1.5 klonu yerine STN1110/OBDLink tabanlı bir cihaz
gerekir.

**Zaman damgası telefondan alınıyor**, adapterden değil. Bluetooth kuyruğu yüzünden birkaç ms
jitter olur. Mikrosaniye hassasiyeti gerekiyorsa donanım tarafında zaman damgası veren bir
arayüz lazım (ELM327 bunu vermiyor).

**29-bit ID kullanacaksan** protokol listesinden `7` veya `9` seç ve `ATCRA`'ya 8 haneli ID gir.
Parser 8 karaktere kadar header'ı kabul ediyor, ama 29-bit'te ELM327 header'ı bazen boşluklu
2'şerli bloklar hâlinde basar (`18 DA F1 10`) — o durumda `CanParser.parse()` içinde ilk 4
token'ı ID olarak birleştirmen gerekir. Kodda not düştüm.

**Ekran kapanınca kayıt durur.** `FLAG_KEEP_SCREEN_ON` var ama uygulama arka plana atılırsa
Android soketi bir süre sonra kesebilir. Uzun süreli (saatlerce) kayıt gerekiyorsa
`ForegroundService`'e taşımak gerekir — v1'de kasten dışarıda bıraktım, söyle ekleyeyim.

## Test etmeden emin olamayacağın nokta

ELM327 klonlarının firmware'i çok değişken. İlk denemede `ATCRA` komutuna `?` dönerse
adapter bu komutu desteklemiyordur; alternatif olarak `ATCF 250` + `ATCM 7FF` çifti denenebilir.
Bağlantı ekranında init dökümü listeleniyor, oradan hangi komuta ne cevap geldiğini görebilirsin.

## GitHub Actions

`.github/workflows/build.yml` üç durumda çalışır: `main`/`master`'a push, PR, elle tetikleme.
Her koşuda **debug APK**'yı artifact olarak yükler (Actions → ilgili run → Artifacts, 30 gün).

Projede Gradle wrapper yok, o yüzden workflow `gradle/actions/setup-gradle` ile Gradle 8.7'yi
kendisi kuruyor. Wrapper'ı repoya eklersen (`gradle wrapper --gradle-version 8.7`)
`gradle-version` satırını silip komutları `./gradlew` yapabilirsin — o zaman lokal ve CI
aynı Gradle sürümünü kullanır, tercih edilen yol budur.

### İmzalı release

`v1.0` gibi bir tag pushlarsan release APK derlenir ve GitHub Release oluşturulur.
İmzalamak için repo → Settings → Secrets and variables → Actions altına dört secret ekle:

| Secret | Ne |
|---|---|
| `KEYSTORE_B64` | keystore dosyasının base64'ü |
| `KEYSTORE_PASSWORD` | store parolası |
| `KEY_ALIAS` | anahtar alias'ı |
| `KEY_PASSWORD` | anahtar parolası |

Keystore üretmek ve base64'lemek:

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias canmonitor

base64 -w0 release.jks > release.jks.b64   # macOS: base64 -i release.jks -o release.jks.b64
```

`release.jks.b64` içeriğini `KEYSTORE_B64` secret'ına yapıştır. **Keystore dosyasını repoya
commit etme** — `.gitignore`'da `*.jks` zaten var.

Secret'lar yoksa imzalama adımları atlanır, release'e imzasız APK yüklenir (yan yükleme için
yine de `apksigner` ile lokalde imzalaman gerekir; Android imzasız APK kurmaz).

## İkon, splash ve renk teması

Görsel `tools/gen_icons.py` ile tüm yoğunluklara üretildi. Kaynağı değiştirirsen:

```bash
python3 tools/gen_icons.py yeni_gorsel.png
```

Üretilenler:

| Varlık | Boyut | Nerede |
|---|---|---|
| `ic_launcher.png` | 48dp (mdpi→xxxhdpi) | eski usul, API 25 ve altı — yuvarlatılmış kare |
| `ic_launcher_round.png` | 48dp | dairesel maske |
| `ic_launcher_foreground.png` | 108dp tuval, içerik %70 | adaptive icon ön katmanı |
| `splash_icon.png` | 288dp tuval, içerik 192dp | Android 12+ açılış ekranı |
| `playstore_icon_512.png` | 512×512 | Play Console (APK'ya girmez) |

**Adaptive icon'da içerik neden %70?** Android 8+ launcher ikonun kenarlarını kendi maskesiyle
kırpar (daire, squircle, kare — üreticiye göre değişir). Görseli tam kenara yaslasaydım Pixel
launcher'ın dairesel maskesinde "CAN MONITOR" yazısının altı ve CSV rozeti kesilirdi. %70'te
her maskede tamamı görünüyor. Arka plan katmanı düz `#061428`, yani kırpılan bölge tema
rengiyle doluyor.

**`monochrome` katmanı bilinçli olarak eklenmedi.** Android 13 temalı ikonlar için gereken
katman tek renkli siluet ister; renkli PNG konursa launcher onu tek renge boyar ve okunmaz bir
leke çıkar. Temalı ikon desteği istersen ayrı bir siluet SVG çizmek gerekir.

Splash `androidx.core:core-splashscreen` ile kuruldu, API 21'e kadar geriye uyumlu.
`MainActivity.onCreate` içinde `installSplashScreen()` **`super.onCreate`'den önce** çağrılıyor —
sırası değişirse splash görünmez.

### Palet (`res/values/colors.xml`)

| Token | Hex | Kullanım |
|---|---|---|
| `obd_bg` | `#061428` | ana zemin, status/nav bar |
| `obd_surface` | `#0E1C33` | kart / pasif buton |
| `obd_surface_alt` | `#152743` | aktif buton |
| `obd_line` | `#1E3555` | ayırıcı |
| `obd_primary` | `#29B6F6` | başlık, CAN-H, vurgu |
| `obd_primary_dim` | `#1B6FA8` | kenarlık, basılı hâl |
| `obd_cyan` | `#6FD6FF` | CAN ID sütunu |
| `obd_amber` | `#FFC400` | DATA sütunu, filtre girişi |
| `obd_green` | `#2E9E4F` | CSV / başarı |
| `obd_text` / `obd_text_dim` | `#E8F1FA` / `#8CA3BC` | metin |

Renkler ikondan örneklendi. Diğer OBD uygulamalarında farklı hex'ler kullanıyorsanız tek
yapmanız gereken `colors.xml`'i değiştirmek — layout'lar ve tema hep bu token'lara bakıyor,
hiçbir yerde gömülü hex kalmadı.

## Repoya ilk yükleme (lokal test olmadan)

Zip'i açtıktan sonra **`CanMonitor` klasörünün içine** gir — `settings.gradle.kts` repo kökünde
olmalı, bir alt klasörde değil. Gradle settings dosyasını göremezse plugin çözümlemesi çöker.

```bash
cd CanMonitor
git init
git add -A
git status --short          # settings.gradle.kts listede olmalı
git commit -m "CAN Monitor v1.0.2"
git branch -M main
git remote add origin https://github.com/<kullanici>/ELM327-CanBus-Sniffer.git
git push -u origin main --force
```

`git status --short` çıktısında `settings.gradle.kts` görünmüyorsa dur — repoda eski bir
`.gitignore` kalmış demektir. Sil, zip'ten geleni kullan.

Push'tan sonra Actions sekmesinde iş başlar. İlk adım repo kökünü listeleyip kritik dosyaları
doğruluyor; eksik varsa Gradle'ın kriptik hatası yerine hangi dosyanın olmadığını yazıyor.
Derleme biterse APK: run sayfası → Artifacts → `can-monitor-debug`.

## Sürüm geçmişi

| Sürüm | Değişiklik |
|---|---|
| 1.0.3 | Edge-to-edge inset düzeltmesi (içerik saat ve sanal tuşların altında kalıyordu); başlıkta sürüm etiketi |
| 1.0.2 | İkon, splash, OBD renk teması |
| 1.0.1 | GitHub Actions workflow |
| 1.0 | İlk sürüm |

### Edge-to-edge notu

`targetSdk = 35` (Android 15) ile birlikte sistem, uygulama penceresini **zorla** edge-to-edge
çiziyor; `fitsSystemWindows` veya klasik pencere bayrakları artık bunu kapatmıyor. Çözüm
kök görünüme sistem çubuğu yüksekliklerini padding olarak eklemek — `MainActivity.applyInsets()`
bunu yapıyor. Klavye açıldığında alt padding IME yüksekliğine çıkıyor, böylece Filter ID
kutusuna yazarken alttaki butonlar klavyenin altında kaybolmuyor.

Layout'ta `padding="14dp"` yerine dört ayrı `paddingStart/End/Top/Bottom` var; bunun sebebi
listener'ın `setPadding()` ile hepsini yeniden yazması — tek `padding` bırakılsaydı Android
Studio önizlemesi ile gerçek cihaz arasında fark oluşurdu.

Sürüm etiketi `BuildConfig.VERSION_NAME`'den okunuyor, elle yazılmıyor. `app/build.gradle.kts`
içindeki `versionName`'i değiştirmen yeterli; başlıktaki yazı kendiliğinden güncellenir.

---

**1.0.4 — STOP kilitlenmesi**

Belirti: START LOG'dan sonra STOP tepki vermiyor, PAYLAŞ pasif kalıyor.

Sebep: `MainActivity` tek bir `newSingleThreadExecutor()` kullanıyordu. `elm.startMonitor()`
tasarım gereği bloklayıcı — ATMA akışı bitene kadar geri dönmez. Dolayısıyla o tek thread'i
süresiz işgal ediyordu. STOP'a basınca `io.execute { elm.stopMonitor() }` görevi kuyruğa
giriyor ama önündeki iş hiç bitmediği için sırası hiç gelmiyordu. Dinlemeyi durduracak komut,
dinlemenin bitmesini bekliyordu — klasik kilitlenme.

Çözüm: dinleme döngüsü ayrı bir havuza (`monitorExec`) taşındı, `io` havuzu kısa komutlara
(bağlan / durdur / paylaş) ayrıldı. STOP artık boş bir thread'de anında çalışıyor.

Ek olarak bir bekçi eklendi: adapter gönderilen CR'a 3 saniye içinde cevap vermezse
(bazı klonlarda ATMA akışı takılabiliyor) soket kapatılıp arayüz serbest bırakılıyor, kullanıcı
uygulamayı öldürmek zorunda kalmıyor.
