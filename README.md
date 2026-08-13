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
