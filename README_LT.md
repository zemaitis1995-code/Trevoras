# TREVORAS Multimedia 0.9

Pilnas Android Studio projektas, paruoštas kompiliuoti į APK.

## Kas jau VEIKIA telefone
- GPS greitis realiu laiku.
- Maksimalus greitis.
- Kelionės kilometrai ir laikas.
- Važiavimo kryptis.
- Google Maps navigacijos paleidimas į įvestą adresą.
- Waze navigacijos paleidimas.
- Spotify / kitos aktyvios Android medijos dainos pavadinimo ir atlikėjo rodymas.
- Play / Pause / Previous / Next per Android MediaSession.
- Spotify paleidimo mygtukas.
- TFT IP/porto TCP patikrinimas.
- Android MediaProjection + H.264/AVC ekrano kodavimas.
- Eksperimentinis H.264 siuntimas TCP į pasirinktą TFT IP/portą.
- TREVORAS juoda/raudona ADV sąsaja.

## Kas dar NEGALI būti laikoma patvirtinta
Originalaus `智享骑投屏版` APK analizė patvirtino MediaProjection, MediaCodec/video-avc ir funkcijas
`checkCastServer`, `askCastService`, `startCast`, `nativeGetPort`, `deviceIp`.
Tačiau vien iš APK statinės analizės nepatvirtintas tikslus Trevoro TFT handshake/framing protokolas.
Todėl eksperimentinė projekcija gali nepradėti vaizdo TFT, net jei TCP portas atsidaro.

`8888` yra tik pradinis kandidatas, NE patvirtintas Trevoro projekcijos portas.

RPM, pavara, variklio temperatūra, kuras ir ABS sąmoningai rodomi `—`.
Programa neprisigalvoja ECU duomenų. Tam reikės atskiro TFT/ECU duomenų kanalo.

## Kaip pasidaryti APK kompiuteryje

1. Įsidiek Android Studio.
2. Išarchyvuok `TREVORAS_Full_Source.zip`.
3. Android Studio -> Open -> pasirink aplanką `TREVORAS_Full`.
4. Palauk, kol Gradle Sync baigsis. Pirmą kartą Android Studio gali paprašyti įdiegti Android SDK 35.
5. Viršuje: Build -> Build App Bundle(s) / APK(s) -> Build APK(s).
6. Gautas failas paprastai:
   `app/build/outputs/apk/debug/app-debug.apk`
7. Persiųsk jį į Samsung ir įdiek. Jei Android blokuoja, leisk tam failų tvarkytuvui „Install unknown apps“.

## Pirmas paleidimas
1. Leisk Location.
2. „LEISTI MUZIKOS VALDYMĄ“ -> įjunk TREVORAS Notification access.
3. Paleisk Spotify. Grįžus į TREVORAS turėtų atsirasti dainos pavadinimas ir veikti valdikliai.
4. Navigacijoje įrašyk tikslą ir spausk Google Maps arba Waze.
5. TFT dalyje pirmiausia tikrink IP/portą.
6. Tik po to bandyk eksperimentinę projekciją. Android parodys sistemos langą dėl ekrano bendrinimo.

## Svarbu dėl hotspot
Originalaus kiniško APK `cast_help.md` instrukcija nurodo:
- telefone įjungti asmeninį hotspot;
- hotspot SSID ir slaptažodis turi sutapti su tuo, ką rodo motociklo TFT;
- TFT automatiškai prisijungia prie telefono hotspot;
- nestabilumo atveju rekomenduojamas 5 GHz hotspot.

Taigi TFT IP reikia ieškoti tarp prie telefono hotspot prisijungusių klientų.

## Kodėl Spotify čia nereikia Client ID
Ši versija Spotify valdo per Android `MediaSession`, o ne Spotify Web API.
Tai paprasčiau asmeniniam motociklo ekranui: Spotify lieka oficialiame telefone įdiegtame Spotify app,
o TREVORAS tik rodo aktyvios medijos metaduomenis ir siunčia standartines medijos komandas.

## Kitas techninis etapas
Kad projekcija į TFT taptų 100% suderinama su originalu, reikia vienos gyvos originalios programos sesijos:
- TFT IP;
- atidaryti portai;
- originalios programos ir TFT TCP/UDP srautas;
- pirmi handshake paketai;
- H.264 framing.

Tada `CastService.java` transporto dalį galima pakeisti tiksliu originalaus TFT protokolu.
