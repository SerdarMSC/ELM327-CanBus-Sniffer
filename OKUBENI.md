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
